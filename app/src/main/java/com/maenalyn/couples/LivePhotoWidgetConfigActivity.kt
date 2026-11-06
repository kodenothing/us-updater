package com.maenalyn.couples

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class LivePhotoWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            val paletteIndex = getSharedPreferences("us", MODE_PRIVATE)
                .getInt("palette", 0)
            UsTheme(
                dark = androidx.compose.foundation.isSystemInDarkTheme(),
                paletteIndex = paletteIndex
            ) {
                var selected by remember {
                    mutableStateOf(OurInstantWidgetDesigns.load(this, appWidgetId))
                }
                BackHandler {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            "Customize Our Instant",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Choose the frame for this home-screen photo widget. Your partner's widget design won't change.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        OurInstantWidgetDesigns.options.forEach { option ->
                            OurInstantWidgetDesignCard(
                                option = option,
                                selected = selected == option.id,
                                onClick = { selected = option.id }
                            )
                        }

                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = {
                                OurInstantWidgetDesigns.save(this@LivePhotoWidgetConfigActivity, appWidgetId, selected)
                                LivePhotoWidgetProvider.updateWidget(this@LivePhotoWidgetConfigActivity, appWidgetId)
                                val result = Intent().putExtra(
                                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                                    appWidgetId
                                )
                                setResult(Activity.RESULT_OK, result)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add widget")
                        }
                        OutlinedButton(
                            onClick = {
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OurInstantWidgetDesignCard(
    option: OurInstantWidgetDesigns.Option,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(20.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) cs.primaryContainer.copy(alpha = 0.55f) else cs.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) cs.primary else cs.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OurInstantWidgetStylePreview(
                style = option.id,
                modifier = Modifier.size(width = 112.dp, height = 82.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(option.title, fontWeight = FontWeight.SemiBold)
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = cs.primary)
            }
        }
    }
}

@Composable
private fun OurInstantWidgetStylePreview(
    style: String,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val shape = when (style) {
        OurInstantWidgetDesigns.STYLE_POLAROID -> RoundedCornerShape(5.dp)
        OurInstantWidgetDesigns.STYLE_MINIMAL -> RoundedCornerShape(8.dp)
        else -> RoundedCornerShape(19.dp)
    }
    val frameColor = when (style) {
        OurInstantWidgetDesigns.STYLE_POLAROID -> Color(0xFFFFFFFF)
        OurInstantWidgetDesigns.STYLE_MINIMAL -> cs.surface
        OurInstantWidgetDesigns.STYLE_OUTLINE -> cs.surface
        else -> Color(0xFFFCE8F2)
    }
    val imageShape = when (style) {
        OurInstantWidgetDesigns.STYLE_POLAROID -> RoundedCornerShape(3.dp)
        OurInstantWidgetDesigns.STYLE_MINIMAL -> RoundedCornerShape(6.dp)
        OurInstantWidgetDesigns.STYLE_OUTLINE -> RoundedCornerShape(13.dp)
        else -> RoundedCornerShape(14.dp)
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(frameColor)
            .then(
                if (style == OurInstantWidgetDesigns.STYLE_OUTLINE ||
                    style == OurInstantWidgetDesigns.STYLE_MINIMAL
                ) Modifier.border(
                    if (style == OurInstantWidgetDesigns.STYLE_OUTLINE) 2.dp else 1.dp,
                    if (style == OurInstantWidgetDesigns.STYLE_OUTLINE) cs.primary else cs.outlineVariant,
                    shape
                ) else Modifier
            )
            .padding(if (style == OurInstantWidgetDesigns.STYLE_POLAROID) 7.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(imageShape)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFFB79BE8), Color(0xFFEFA6CB))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(25.dp)
            )
        }
        if (style == OurInstantWidgetDesigns.STYLE_POLAROID) {
            Text(
                "our instant",
                color = Color(0xFF594B5D),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
