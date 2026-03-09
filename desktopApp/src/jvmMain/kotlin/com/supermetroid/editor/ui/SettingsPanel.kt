package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.supermetroid.editor.data.AppConfig

@Composable
fun SettingsPopup(
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val themeState = LocalEditorTheme.current
        var currentTheme by themeState.theme
        var currentFontSize by themeState.fontSize

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.width(320.dp).padding(top = 4.dp, end = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Settings",
                    fontSize = currentFontSize.heading,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // ── Theme Section ──
                Text(
                    "Theme",
                    fontSize = currentFontSize.body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (theme in EditorTheme.entries) {
                        val selected = theme == currentTheme
                        val colors = theme.colorScheme
                        Surface(
                            modifier = Modifier.clickable {
                                currentTheme = theme
                                AppConfig.update { copy(theme = theme.name) }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(14.dp).clip(CircleShape)
                                        .background(colors.background)
                                        .border(1.dp, colors.outline, CircleShape)
                                )
                                Box(
                                    modifier = Modifier.size(14.dp).clip(CircleShape)
                                        .background(colors.primary)
                                        .border(1.dp, colors.outline, CircleShape)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    theme.displayName,
                                    fontSize = currentFontSize.detail,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ── Font Size Section ──
                Text(
                    "Font Size",
                    fontSize = currentFontSize.body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (size in FontSize.entries) {
                        val selected = size == currentFontSize
                        Surface(
                            modifier = Modifier.clickable {
                                currentFontSize = size
                                AppConfig.update { copy(fontSize = size.name) }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                size.displayName,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                fontSize = size.body,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ── Preview ──
                Spacer(Modifier.height(2.dp))
                Text(
                    "Preview",
                    fontSize = currentFontSize.detail,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("Tab label text", fontSize = currentFontSize.tabLabel, color = MaterialTheme.colorScheme.onSurface)
                        Text("Body text — room names, lists", fontSize = currentFontSize.body, color = MaterialTheme.colorScheme.onSurface)
                        Text("Detail — hex values, coords", fontSize = currentFontSize.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Status bar", fontSize = currentFontSize.statusBar, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
