package com.maenalyn.couples

data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String,
    val dateEpochDay: Long,
    val allDay: Boolean,
    val startTime: String,
    val endTime: String,
    val reminderMinutes: Int,
    val createdBy: String,
    val createdAt: Long
)

data class LoveNote(
    val id: String,
    val title: String,
    val text: String,
    val createdBy: String,
    val createdAt: Long,
    val pinned: Boolean
)

data class Memory(
    val id: String,
    val caption: String,
    val photoUrl: String,
    val storagePath: String,
    val fileName: String,
    val status: String,
    val storageMode: String,
    val createdBy: String,
    val createdAt: Long,
    val favorite: Boolean = false,
    val sizeBytes: Long = 0L
)

data class ActivityEntry(
    val id: String,
    val actorUid: String,
    val type: String,
    val title: String,
    val summary: String,
    val targetCollection: String,
    val targetId: String,
    val createdAt: Long
)

data class CalendarEventDraft(
    val title: String,
    val description: String,
    val dateEpochDay: Long,
    val allDay: Boolean,
    val startTime: String,
    val endTime: String,
    val reminderMinutes: Int
)
