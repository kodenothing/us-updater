package com.maenalyn.couples

import android.content.Context
import androidx.annotation.LayoutRes

/**
 * Appearance is stored per launcher widget ID. Each widget instance resolves
 * its own member-owned photo cache; only its frame style is customized here.
 */
internal object OurInstantWidgetDesigns {
    const val STYLE_SOFT = "soft"
    const val STYLE_OUTLINE = "outline"
    const val STYLE_POLAROID = "polaroid"
    const val STYLE_MINIMAL = "minimal"

    data class Option(
        val id: String,
        val title: String,
        val description: String
    )

    val options = listOf(
        Option(STYLE_SOFT, "Soft corners", "Pastel background and rounded edges"),
        Option(STYLE_OUTLINE, "Fine outline", "A slim lavender border"),
        Option(STYLE_POLAROID, "Polaroid", "A white photo frame and caption"),
        Option(STYLE_MINIMAL, "Minimal", "Clean edges with a subtle line")
    )

    private fun preferenceKey(appWidgetId: Int) = "ourInstantWidgetStyle_$appWidgetId"

    fun save(context: Context, appWidgetId: Int, style: String) {
        val valid = options.any { it.id == style }
        require(valid) { "Unknown Our Instant widget style." }
        context.getSharedPreferences("us", Context.MODE_PRIVATE)
            .edit()
            .putString(preferenceKey(appWidgetId), style)
            .apply()
    }

    fun load(context: Context, appWidgetId: Int): String {
        val value = context.getSharedPreferences("us", Context.MODE_PRIVATE)
            .getString(preferenceKey(appWidgetId), STYLE_SOFT)
        return options.firstOrNull { it.id == value }?.id ?: STYLE_SOFT
    }

    fun remove(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences("us", Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { editor.remove(preferenceKey(it)) }
        editor.apply()
    }

    @LayoutRes
    fun layoutFor(style: String): Int = when (style) {
        STYLE_OUTLINE -> R.layout.widget_live_photo_outline
        STYLE_POLAROID -> R.layout.widget_live_photo_polaroid
        STYLE_MINIMAL -> R.layout.widget_live_photo_minimal
        else -> R.layout.widget_live_photo
    }
}
