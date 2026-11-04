package com.maenalyn.couples

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

object BackupManager {
    private val db get() = FirebaseFirestore.getInstance()
    private const val ITERATIONS = 180_000
    private const val KEY_BITS = 256
    private const val MAX_BACKUP_BYTES = 200L * 1024L * 1024L
    private val magic = "USBACKUP1".toByteArray(Charsets.US_ASCII)
    private val collections = listOf("items", "events", "notes", "activity", "memories", "memoryAlbums", "memoryAlbumCovers", "places")

    data class BackupResult(val restoredRecords: Int, val restoredMemories: Int, val skippedExisting: Int)
    data class BackupPreview(
        val listItems: Int,
        val events: Int,
        val notes: Int,
        val activityEntries: Int,
        val savedPlaces: Int,
        val dailyDays: Int,
        val dailyAnswers: Int,
        val dailyCheckins: Int,
        val memories: Int,
        val photoFiles: Int,
        val exportedAt: Long
    )

    /** Decrypts and validates the backup manifest without writing any Firestore data. */
    suspend fun preview(context: Context, code: String, source: Uri, password: String): BackupPreview {
        require(password.length >= 10) { "Enter the password used to create this backup." }
        val manifestBytes = ByteArrayOutputStream()
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(source)
                ?: error("Couldn't open the selected backup file.")
            input.use { raw ->
                val header = ByteArray(magic.size + 28)
                readFully(raw, header)
                require(header.copyOfRange(0, magic.size).contentEquals(magic)) { "This isn't a Us backup file." }
                val salt = header.copyOfRange(magic.size, magic.size + 16)
                val iv = header.copyOfRange(magic.size + 16, header.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
                ZipInputStream(BufferedInputStream(CipherInputStream(raw, cipher))).use { zip ->
                    var expanded = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(!entry.name.startsWith("/") && !entry.name.contains("..") && !entry.name.contains('\\')) {
                            "The backup contains an unsafe file path."
                        }
                        val isManifest = entry.name == "manifest.json"
                        val buffer = ByteArray(16 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            expanded += count
                            require(expanded <= MAX_BACKUP_BYTES) { "The backup expands beyond the supported size limit." }
                            if (isManifest) {
                                require(entryBytes <= 20L * 1024L * 1024L) { "The backup manifest is too large." }
                                manifestBytes.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
        val manifest = JSONObject(manifestBytes.toByteArray().toString(Charsets.UTF_8))
        require(manifest.optInt("schemaVersion") == 2) { "This backup version isn't supported by this app." }
        require(manifest.optString("coupleCode") == code) { "This backup belongs to a different shared space." }
        val backupMembers = manifest.optJSONArray("memberIds").toStringList()
        val current = db.collection("couples").document(code).get(Source.SERVER).await()
        val currentMembers = (current.get("members") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Your account isn't ready.")
        require(backupMembers.toSet() == currentMembers.toSet() && uid in currentMembers) {
            "Both partner accounts must match the accounts in this backup."
        }
        val collections = manifest.optJSONObject("collections") ?: JSONObject()
        val memoryRecords = collections.optJSONArray("memories") ?: JSONArray()
        val photoCount = (0 until memoryRecords.length()).count { memoryRecords.optJSONObject(it)?.has("imageEntry") == true }
        val dailyRecords = collections.optJSONArray("daily") ?: JSONArray()
        var dailyAnswerCount = 0
        var dailyCheckinCount = 0
        for (index in 0 until dailyRecords.length()) {
            val day = dailyRecords.optJSONObject(index) ?: continue
            dailyAnswerCount += day.optJSONArray("answers")?.length() ?: 0
            dailyCheckinCount += day.optJSONArray("checkins")?.length() ?: 0
        }
        return BackupPreview(
            listItems = (collections.optJSONArray("items")?.length() ?: 0),
            events = (collections.optJSONArray("events")?.length() ?: 0),
            notes = (collections.optJSONArray("notes")?.length() ?: 0),
            activityEntries = (collections.optJSONArray("activity")?.length() ?: 0),
            savedPlaces = (collections.optJSONArray("places")?.length() ?: 0),
            dailyDays = dailyRecords.length(),
            dailyAnswers = dailyAnswerCount,
            dailyCheckins = dailyCheckinCount,
            memories = memoryRecords.length(),
            photoFiles = photoCount,
            exportedAt = manifest.optLong("exportedAt", 0L)
        )
    }

    suspend fun export(context: Context, code: String, destination: Uri, password: String) {
        require(password.length >= 10) { "Use a backup password with at least 10 characters." }
        val root = db.collection("couples").document(code)
        val parent = root.get(Source.SERVER).await()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Your account isn't ready.")
        val members = (parent.get("members") as? List<*>)?.filterIsInstance<String>().orEmpty()
        require(uid in members && members.isNotEmpty()) { "This account isn't part of the selected shared space." }

        val tempZip = File(context.cacheDir, "us-backup-${System.currentTimeMillis()}.zip")
        try {
            withContext(Dispatchers.IO) {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zip ->
                    val manifest = JSONObject()
                    manifest.put("schemaVersion", 2)
                    manifest.put("app", "Us")
                    manifest.put("coupleCode", code)
                    manifest.put("memberIds", JSONArray(members))
                    manifest.put("exportedAt", System.currentTimeMillis())
                    val memberNames = parent.get("memberNames") as? Map<*, *> ?: emptyMap<Any, Any>()
                    manifest.put("couple", JSONObject().apply {
                        put("anniversary", parent.getLong("anniversary") ?: DEFAULT_ANNIVERSARY)
                        put("coupleName", parent.getString("coupleName") ?: "Us 💜")
                        put("myNickname", memberNames[uid] as? String ?: "Me")
                    })

                    val data = JSONObject()
                    collections.forEach { name ->
                        val docs = fetchAll(root.collection(name))
                        val records = JSONArray()
                        docs.forEach { doc ->
                            val record = JSONObject().put("id", doc.id).put("data", toJsonObject(doc.data.orEmpty()))
                            if (name == "memories") {
                                val imageSnapshot = doc.reference.collection("content").document("image").get(Source.SERVER).await()
                                val imageBytes = imageSnapshot.getBlob("image")?.toBytes()
                                if (imageBytes != null && imageBytes.isNotEmpty()) {
                                    val imageEntry = "photos/${encodedId(doc.id)}_image.bin"
                                    zip.putNextEntry(ZipEntry(imageEntry))
                                    zip.write(imageBytes)
                                    zip.closeEntry()
                                    record.put("imageEntry", imageEntry)
                                }
                                val thumbSnapshot = doc.reference.collection("content").document("thumbnail").get(Source.SERVER).await()
                                val thumbBytes = thumbSnapshot.getBlob("image")?.toBytes()
                                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                                    val thumbEntry = "photos/${encodedId(doc.id)}_thumbnail.bin"
                                    zip.putNextEntry(ZipEntry(thumbEntry))
                                    zip.write(thumbBytes)
                                    zip.closeEntry()
                                    record.put("thumbnailEntry", thumbEntry)
                                }
                            }
                            records.put(record)
                        }
                        data.put(name, records)
                    }

                    val dailyDocs = fetchAll(root.collection("daily"))
                    val dailyRecords = JSONArray()
                    dailyDocs.forEach { dailyDoc ->
                        val record = JSONObject().put("id", dailyDoc.id).put("data", toJsonObject(dailyDoc.data.orEmpty()))
                        val answers = JSONArray()
                        val ownAnswer = dailyDoc.reference.collection("answers").document(uid).get(Source.SERVER).await()
                        if (ownAnswer.exists()) {
                            answers.put(JSONObject().put("id", ownAnswer.id).put("data", toJsonObject(ownAnswer.data.orEmpty())))
                        }
                        val checkins = JSONArray()
                        val ownCheckin = dailyDoc.reference.collection("checkins").document(uid).get(Source.SERVER).await()
                        if (ownCheckin.exists()) {
                            checkins.put(JSONObject().put("id", ownCheckin.id).put("data", toJsonObject(ownCheckin.data.orEmpty())))
                        }
                        record.put("answers", answers)
                        record.put("checkins", checkins)
                        dailyRecords.put(record)
                    }
                    data.put("daily", dailyRecords)
                    manifest.put("collections", data)
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            require(tempZip.length() <= MAX_BACKUP_BYTES) { "This backup is larger than 200 MB. Export fewer memories at a time." }
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(destination)
                    ?: error("Couldn't open the selected backup destination.")
                output.use { raw ->
                    val random = SecureRandom()
                    val salt = ByteArray(16).also(random::nextBytes)
                    val iv = ByteArray(12).also(random::nextBytes)
                    raw.write(magic)
                    raw.write(salt)
                    raw.write(iv)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
                    CipherOutputStream(BufferedOutputStream(raw), cipher).use { encrypted ->
                        FileInputStream(tempZip).use { input -> input.copyTo(encrypted) }
                    }
                }
            }
        } finally {
            tempZip.delete()
        }
    }

    suspend fun restore(context: Context, code: String, source: Uri, password: String): BackupResult {
        require(password.length >= 10) { "Enter the password used to create this backup." }
        val decryptedZip = File(context.cacheDir, "us-restore-${System.currentTimeMillis()}.zip")
        val photoFolder = File(context.cacheDir, "us-restore-photos-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(source)
                    ?: error("Couldn't open the selected backup file.")
                input.use { raw ->
                    val header = ByteArray(magic.size + 28)
                    readFully(raw, header)
                    require(header.copyOfRange(0, magic.size).contentEquals(magic)) { "This isn't a Us backup file." }
                    val salt = header.copyOfRange(magic.size, magic.size + 16)
                    val iv = header.copyOfRange(magic.size + 16, header.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
                    CipherInputStream(BufferedInputStream(raw), cipher).use { decrypted ->
                        FileOutputStream(decryptedZip).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val count = decrypted.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BACKUP_BYTES) { "This backup is larger than the supported 200 MB limit." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            decryptedZip.delete()
            photoFolder.deleteRecursively()
            if (error is IllegalArgumentException && error.message?.contains("backup") == true) throw error
            throw IllegalArgumentException("Couldn't decrypt this backup. Check the password and file.")
        }

        return try {
            val entries = mutableMapOf<String, File>()
            var manifest: JSONObject? = null
            withContext(Dispatchers.IO) {
                var expandedBytes = 0L
                ZipInputStream(BufferedInputStream(FileInputStream(decryptedZip))).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(
                            !entry.name.startsWith("/") && !entry.name.contains("..") && !entry.name.contains(
                                '\\'
                            )
                        ) {
                            "The backup contains an unsafe file path."
                        }
                        if (entry.name == "manifest.json") {
                            val bytes = zip.readBytes()
                            expandedBytes += bytes.size
                            require(bytes.size <= 20L * 1024L * 1024L && expandedBytes <= MAX_BACKUP_BYTES) {
                                "The backup manifest is too large."
                            }
                            manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                        } else if (entry.name.startsWith("photos/") && !entry.isDirectory) {
                            val file = File(photoFolder, entry.name.substringAfter("photos/"))
                            var entryBytes = 0L
                            file.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count
                                    expandedBytes += count
                                    require(entryBytes <= MAX_MEMORY_IMAGE_BYTES && expandedBytes <= MAX_BACKUP_BYTES) {
                                        "A photo in this backup exceeds the supported size limit."
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                            entries[entry.name] = file
                        }
                        zip.closeEntry()
                    }
                }
            }
            val rootJson = manifest ?: error("The backup manifest is missing.")
            require(rootJson.optInt("schemaVersion") == 2) { "This backup version isn't supported by this app." }
            require(rootJson.optString("coupleCode") == code) { "This backup belongs to a different shared space." }
            val backupMembers = rootJson.optJSONArray("memberIds").toStringList()
            val uid =
                FirebaseAuth.getInstance().currentUser?.uid ?: error("Your account isn't ready.")
            val root = db.collection("couples").document(code)
            val current = root.get(Source.SERVER).await()
            val currentMembers =
                (current.get("members") as? List<*>)?.filterIsInstance<String>().orEmpty()
            require(backupMembers.toSet() == currentMembers.toSet() && uid in currentMembers) {
                "Both partner accounts must match the accounts in this backup."
            }

            val coupleData = rootJson.optJSONObject("couple") ?: JSONObject()
            val anniversary = coupleData.optLong("anniversary", DEFAULT_ANNIVERSARY)
            val coupleName = coupleData.optString("coupleName", "Us 💜").take(32).ifBlank { "Us 💜" }
            root.update("anniversary", anniversary).await()
            root.update("coupleName", coupleName).await()
            val ownNickname = coupleData.optString("myNickname", "").trim().take(24)
            if (ownNickname.isNotBlank()) {
                root.update("memberNames.$uid", ownNickname).await()
            }

            val allData = rootJson.getJSONObject("collections")
            var restoredRecords = 0
            var restoredMemories = 0
            var skippedExisting = 0

            for (name in listOf("items", "events", "notes", "activity", "memoryAlbums", "memoryAlbumCovers", "places")) {
                val array = allData.optJSONArray(name) ?: JSONArray()
                val pending = mutableListOf<Pair<String, Map<String, Any?>>>()
                for (i in 0 until array.length()) {
                    val record = array.getJSONObject(i)
                    val id = record.getString("id")
                    val ref = root.collection(name).document(id)
                    if (ref.get(Source.SERVER).await().exists()) {
                        skippedExisting++
                        continue
                    }
                    val data = jsonObjectToMap(record.getJSONObject("data")).toMutableMap()
                    val originalAuthor = (data["createdBy"] ?: data["actorUid"]) as? String
                    when (name) {
                        "items" -> {
                            data["createdBy"] = uid
                            if (originalAuthor in currentMembers) data["originalCreatedBy"] =
                                originalAuthor!!
                        }

                        "events", "notes" -> {
                            data["createdBy"] = uid
                            if (originalAuthor in currentMembers) data["originalCreatedBy"] =
                                originalAuthor!!
                        }

                        "activity" -> {
                            data["actorUid"] = uid
                            if (originalAuthor in currentMembers) data["originalActorUid"] =
                                originalAuthor!!
                        }

                        "memoryAlbums" -> {
                            data["createdBy"] = uid
                            data["updatedBy"] = uid
                        }

                        "memoryAlbumCovers" -> {
                            data["updatedBy"] = uid
                        }

                        "places" -> {
                            data["createdBy"] = uid
                        }
                    }
                    pending += id to data
                }
                pending.chunked(350).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { (id, data) ->
                        batch.set(
                            root.collection(name).document(id),
                            data
                        )
                    }
                    batch.commit().await()
                    restoredRecords += chunk.size
                }
            }

            val dailyArray = allData.optJSONArray("daily") ?: JSONArray()
            for (i in 0 until dailyArray.length()) {
                val dailyRecord = dailyArray.getJSONObject(i)
                val dayId = dailyRecord.getString("id")
                val dayRef = root.collection("daily").document(dayId)
                if (!dayRef.get(Source.SERVER).await().exists()) {
                    dayRef.set(jsonObjectToMap(dailyRecord.getJSONObject("data"))).await()
                }
                for (sub in listOf("answers", "checkins")) {
                    val docs = dailyRecord.optJSONArray(sub) ?: JSONArray()
                    for (j in 0 until docs.length()) {
                        val record = docs.getJSONObject(j)
                        if (record.getString("id") != uid) continue
                        val ref = dayRef.collection(sub).document(uid)
                        if (ref.get(Source.SERVER).await().exists()) {
                            skippedExisting++
                            continue
                        }
                        val data = jsonObjectToMap(record.getJSONObject("data"))
                        ref.set(data).await()
                    }
                }
            }

            val memoryArray = allData.optJSONArray("memories") ?: JSONArray()
            for (i in 0 until memoryArray.length()) {
                val record = memoryArray.getJSONObject(i)
                val imageEntry = record.optString("imageEntry")
                val imageFile = entries[imageEntry] ?: continue
                val id = record.getString("id")
                val memoryRef = root.collection("memories").document(id)
                if (memoryRef.get(Source.SERVER).await().exists()) {
                    skippedExisting++
                    continue
                }
                val oldData = jsonObjectToMap(record.getJSONObject("data"))
                val originalAuthor = oldData["createdBy"] as? String
                val createdAt = (oldData["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                val fallbackAlbumId = java.time.YearMonth.from(
                    java.time.Instant.ofEpochMilli(createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                ).toString()
                val albumId = (oldData["albumId"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackAlbumId
                val bytes = imageFile.readBytes()
                require(bytes.isNotEmpty() && bytes.size <= MAX_MEMORY_IMAGE_BYTES) { "A photo in this backup is outside the supported size limit." }
                val thumbFile = entries[record.optString("thumbnailEntry")]
                val thumbBytes =
                    thumbFile?.readBytes()?.takeIf { it.isNotEmpty() && it.size <= 80 * 1024 }
                val fileName =
                    (oldData["fileName"] as? String)?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,60}\\.jpg$")) }
                        ?: "$id.jpg"
                memoryRef.set(
                    mapOf(
                        "caption" to (oldData["caption"] as? String).orEmpty().take(280),
                        "fileName" to fileName,
                        "photoUrl" to "",
                        "storagePath" to "",
                        "storageMode" to "firestore_blob",
                        "status" to "pending",
                        "createdBy" to uid,
                        "createdAt" to createdAt,
                        "albumId" to albumId,
                        "favorite" to (oldData["favorite"] as? Boolean ?: false),
                        "sizeBytes" to 0L,
                        "thumbnailBytes" to 0L,
                        "originalCreatedBy" to originalAuthor
                    ).filterValues { it != null }).await()
                FeatureRepo.uploadMemoryBlob(code, id, uid, bytes, createdAt).await()
                val finalThumb = thumbBytes ?: createThumbnailFallback(bytes)
                FeatureRepo.uploadMemoryThumbnail(code, id, uid, finalThumb, createdAt).await()
                FeatureRepo.finishMemoryUpload(
                    code,
                    id,
                    uid,
                    bytes.size.toLong(),
                    finalThumb.size.toLong()
                ).await()
                restoredMemories++
            }

            FeatureRepo.createActivity(
                code,
                uid,
                "backup_restored",
                "restored a backup",
                "${restoredRecords} records and ${restoredMemories} photos were recovered.",
                "",
                ""
            ).await()
            BackupResult(restoredRecords, restoredMemories, skippedExisting)
        } finally {
            decryptedZip.delete()
            photoFolder.deleteRecursively()
        }
    }

    private suspend fun fetchAll(collection: com.google.firebase.firestore.CollectionReference): List<DocumentSnapshot> {
        val result = mutableListOf<DocumentSnapshot>()
        var cursor: DocumentSnapshot? = null
        while (true) {
            var query = collection.orderBy(FieldPath.documentId()).limit(300)
            if (cursor != null) query = query.startAfter(cursor!!)
            val page = query.get(Source.SERVER).await().documents
            if (page.isEmpty()) break
            result += page
            cursor = page.last()
            if (page.size < 300) break
        }
        return result
    }

    private fun deriveKey(password: String, salt: ByteArray): javax.crypto.SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return javax.crypto.spec.SecretKeySpec(key, "AES")
    }

    private fun encodedId(id: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(id.toByteArray(Charsets.UTF_8))

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IllegalArgumentException("The backup file is incomplete.")
            offset += read
        }
    }

    private fun toJsonObject(map: Map<String, Any?>): JSONObject = JSONObject().apply {
        map.forEach { (key, value) -> put(key, toJsonValue(value)) }
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, child) -> if (key is String) put(key, toJsonValue(child)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is ByteArray -> Base64.getEncoder().encodeToString(value)
        is String, is Number, is Boolean -> value
        JSONObject.NULL -> value
        else -> value.toString()
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = fromJsonValue(json.get(key))
        }
        return map
    }

    private fun fromJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { fromJsonValue(value.get(it)) }
        else -> value
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private fun createThumbnailFallback(original: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("A photo in this backup cannot be decoded.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        ) ?: error("A photo in this backup cannot be decoded.")
        try {
            var working = bitmap
            var owned = false
            while (true) {
                val ratio = minOf(1f, 320f / maxOf(working.width, working.height))
                if (ratio < 1f) {
                    val scaled = Bitmap.createScaledBitmap(working, (working.width * ratio).toInt().coerceAtLeast(1), (working.height * ratio).toInt().coerceAtLeast(1), true)
                    if (owned && !working.isRecycled) working.recycle()
                    working = scaled
                    owned = true
                }
                for (quality in listOf(65, 55, 45, 35, 25)) {
                    val output = ByteArrayOutputStream()
                    working.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    val bytes = output.toByteArray()
                    if (bytes.isNotEmpty() && bytes.size <= 80 * 1024) {
                        if (owned && !working.isRecycled) working.recycle()
                        return bytes
                    }
                }
                if (minOf(working.width, working.height) <= 72) error("Couldn't create a small preview for one of the photos.")
                val scaled = Bitmap.createScaledBitmap(working, (working.width * 0.72f).toInt().coerceAtLeast(1), (working.height * 0.72f).toInt().coerceAtLeast(1), true)
                if (owned && !working.isRecycled) working.recycle()
                working = scaled
                owned = true
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
