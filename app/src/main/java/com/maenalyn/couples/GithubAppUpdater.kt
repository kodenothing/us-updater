package com.maenalyn.couples

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class GithubReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?
)

data class GithubUpdateCheck(
    val installedVersion: String,
    val release: GithubReleaseInfo,
    val updateAvailable: Boolean
)

object GithubAppUpdater {
    // Replace these two values with the owner and repository that publish your APK releases.
    const val GITHUB_OWNER = "YOUR_GITHUB_USERNAME"
    const val GITHUB_REPOSITORY = "YOUR_REPOSITORY"

    private const val APK_ASSET_NAME = "" // Leave blank to use the first APK attached to the release.
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun isRepositoryConfigured(): Boolean =
        GITHUB_OWNER.isNotBlank() && GITHUB_REPOSITORY.isNotBlank() &&
            !GITHUB_OWNER.startsWith("YOUR_") && !GITHUB_REPOSITORY.startsWith("YOUR_")

    @SuppressLint("PackageManagerGetSignatures")
    fun installedVersionName(context: Context): String = runCatching {
        packageInfo(context, 0).versionName ?: "Unknown"
    }.getOrDefault("Unknown")

    suspend fun checkLatestRelease(context: Context): GithubUpdateCheck = withContext(Dispatchers.IO) {
        check(isRepositoryConfigured()) {
            "Set GITHUB_OWNER and GITHUB_REPOSITORY in GithubAppUpdater.kt before checking for updates."
        }

        val endpoint = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPOSITORY/releases/latest"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "UsApp-Android-Updater")
        }

        val json = try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val details = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("GitHub update check failed ($status). ${details.take(220)}")
            }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }

        check(!json.optBoolean("draft", false)) { "The latest GitHub release is still a draft." }
        check(!json.optBoolean("prerelease", false)) { "The latest GitHub release is marked as a prerelease." }

        val tag = json.optString("tag_name").trim()
        check(tag.isNotBlank()) { "The GitHub release does not have a version tag." }
        val assets = json.optJSONArray("assets")
            ?: throw IllegalStateException("The GitHub release has no APK assets.")
        val asset = (0 until assets.length()).mapNotNull { index -> assets.optJSONObject(index) }
            .firstOrNull { item ->
                val name = item.optString("name")
                name.endsWith(".apk", ignoreCase = true) &&
                    (APK_ASSET_NAME.isBlank() || name.equals(APK_ASSET_NAME, ignoreCase = true))
            } ?: throw IllegalStateException(
                if (APK_ASSET_NAME.isBlank()) "No APK file was attached to the latest GitHub release."
                else "The latest release does not contain the configured APK: $APK_ASSET_NAME"
            )

        val assetUrl = asset.optString("browser_download_url")
        check(assetUrl.startsWith("https://github.com/")) { "The APK download URL is not a public GitHub release URL." }
        val digest = asset.optString("digest").takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')?.lowercase()

        val installed = installedVersionName(context)
        val release = GithubReleaseInfo(
            tagName = tag,
            title = json.optString("name").ifBlank { tag },
            notes = json.optString("body").trim(),
            apkName = asset.optString("name"),
            apkUrl = assetUrl,
            apkSize = asset.optLong("size", 0L),
            sha256 = digest
        )
        GithubUpdateCheck(
            installedVersion = installed,
            release = release,
            updateAvailable = compareVersions(tag, installed) > 0
        )
    }

    suspend fun downloadAndValidate(context: Context, release: GithubReleaseInfo): File = withContext(Dispatchers.IO) {
        val folder = File(context.filesDir, "updates").apply { mkdirs() }
        val safeName = release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "Us-update.apk" }
        val destination = File(folder, safeName)
        val temporary = File(folder, "$safeName.part")
        temporary.delete()

        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "UsApp-Android-Updater")
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            val status = connection.responseCode
            check(status in 200..299) { "APK download failed (HTTP $status)." }
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
                ?.takeIf { it > 0L } ?: release.apkSize
            check(contentLength <= MAX_APK_BYTES) { "The APK is larger than the 250 MB safety limit." }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_APK_BYTES) { "The APK is larger than the 250 MB safety limit." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(total > 0L) { "The downloaded APK is empty." }
            if (contentLength > 0L) check(total == contentLength) { "The APK download was incomplete. Please try again." }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            release.sha256?.let { expected ->
                check(actualHash.equals(expected, ignoreCase = true)) {
                    "The downloaded APK failed GitHub's SHA-256 integrity check."
                }
            }
            validateApk(context, temporary)
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Couldn't prepare the downloaded update." }
            destination
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun launchInstaller(context: Context, apk: File): Boolean {
        check(apk.exists() && apk.length() > 0L) { "The downloaded update file is missing." }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permissionIntent)
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    private fun validateApk(context: Context, apk: File) {
        val archive = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            signingFlags()
        ) ?: throw IllegalStateException("The downloaded file is not a valid Android APK.")

        check(archive.packageName == context.packageName) {
            "This APK belongs to ${archive.packageName}, not ${context.packageName}. Check your GitHub release asset."
        }

        val currentInfo = packageInfo(context, signingFlags())
        val currentCode = longVersionCode(currentInfo)
        val downloadedCode = longVersionCode(archive)
        check(downloadedCode > currentCode) {
            "The release APK version code ($downloadedCode) is not newer than the installed version code ($currentCode)."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installedSigners = currentInfo.signingInfo?.apkContentsSigners
                ?.map { it.toCharsString() }?.toSet().orEmpty()
            val downloadedSigners = archive.signingInfo?.apkContentsSigners
                ?.map { it.toCharsString() }?.toSet().orEmpty()
            if (installedSigners.isNotEmpty() && downloadedSigners.isNotEmpty()) {
                check(installedSigners == downloadedSigners) {
                    "This update is signed with a different certificate. Android cannot install it over the current app."
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, flags)
        }

    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun compareVersions(remoteTag: String, installedName: String): Int {
        fun parts(value: String): List<Int> = Regex("\\d+").findAll(value)
            .take(6).mapNotNull { it.value.toIntOrNull() }.toList()
        val remote = parts(remoteTag)
        val installed = parts(installedName)
        if (remote.isEmpty() || installed.isEmpty()) {
            return remoteTag.removePrefix("v").compareTo(installedName.removePrefix("v"), ignoreCase = true)
        }
        for (index in 0 until maxOf(remote.size, installed.size)) {
            val a = remote.getOrElse(index) { 0 }
            val b = installed.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
}
