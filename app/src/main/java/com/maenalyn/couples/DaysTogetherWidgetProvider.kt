package com.maenalyn.couples

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class DaysTogetherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(context, manager, it) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, DaysTogetherWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it) }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("us", Context.MODE_PRIVATE)
            val paired = !prefs.getString("code", null).isNullOrBlank()
            val epoch = prefs.getLong("anniversaryCache", DEFAULT_ANNIVERSARY)
            val start = LocalDate.ofEpochDay(epoch)
            val days = ChronoUnit.DAYS.between(start, LocalDate.now())
            val views = RemoteViews(context.packageName, R.layout.widget_days_together)
            views.setTextViewText(R.id.widget_title, prefs.getString("title", "Us 💜") ?: "Us 💜")
            views.setTextViewText(R.id.widget_days, if (paired && days >= 0) days.toString() else "—")
            views.setTextViewText(
                R.id.widget_subtitle,
                when {
                    !paired -> "pair to start your story"
                    days >= 0 -> "days together"
                    else -> "our story is about to begin"
                }
            )
            val intent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
