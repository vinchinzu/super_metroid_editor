package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val BUTTON_LABELS = listOf("B", "Y", "Sel", "Sta", "Up", "Dn", "Lt", "Rt", "A", "X", "L", "R")

@Composable
fun RouteEditorPanel(
    routeState: RouteEditorState,
    emulatorState: EmulatorWorkspaceState?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        routeState.refreshAvailableRoutes()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("TAS Route Editor", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    routeState.statusMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RouteTransportControls(
                    routeState = routeState,
                    emulatorState = emulatorState,
                    onAction = { action ->
                        scope.launch {
                            when (action) {
                                "record" -> {
                                    val stateName = emulatorState?.selectedStateName
                                    val startFrame = emulatorState?.session?.frameCounter ?: 0
                                    routeState.startRecording(stateName, startFrame)
                                }
                                "stop_record" -> routeState.stopRecording()
                                "play" -> routeState.startPlayback()
                                "pause" -> routeState.pausePlayback()
                                "resume" -> routeState.resumePlayback()
                                "stop" -> routeState.stopPlayback()
                                "step_back" -> routeState.stepBackward()
                                "step_forward" -> routeState.stepForward()
                                "save" -> routeState.saveRoute()
                                "clear" -> routeState.clearRoute()
                            }
                        }
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Input Timeline", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    RouteFrameTimeline(routeState)
                    Divider()
                    Text("Input List", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    RouteInputList(
                        routeState = routeState,
                        onFrameSelected = { routeState.seekToFrame(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Route Library", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    RouteLibraryList(
                        routeState = routeState,
                        onLoadRoute = { scope.launch { routeState.loadRoute(it) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteTransportControls(
    routeState: RouteEditorState,
    emulatorState: EmulatorWorkspaceState?,
    onAction: (String) -> Unit,
) {
    val route = routeState.currentRoute
    val playbackState = routeState.playbackState
    val canRecord = emulatorState?.session?.active == true
    val hasRoute = route != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (route != null) {
            Text(
                "Route: ${route.name}  ·  ${route.frameCount} frames  ·  ${route.inputs.size} inputs  ·  ${route.positions.size} position samples",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Current frame: ${routeState.currentFrame} / ${route.frameCount}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (playbackState) {
                RoutePlaybackState.IDLE -> {
                    Button(
                        onClick = { onAction("record") },
                        enabled = canRecord,
                    ) {
                        Text("Record", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onAction("play") },
                        enabled = hasRoute,
                    ) {
                        Text("Play", fontSize = 12.sp)
                    }
                }
                RoutePlaybackState.RECORDING -> {
                    Button(onClick = { onAction("stop_record") }) {
                        Text("Stop Recording", fontSize = 12.sp)
                    }
                }
                RoutePlaybackState.PLAYING -> {
                    Button(onClick = { onAction("pause") }) {
                        Text("Pause", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { onAction("stop") }) {
                        Text("Stop", fontSize = 12.sp)
                    }
                }
                RoutePlaybackState.PAUSED -> {
                    Button(onClick = { onAction("resume") }) {
                        Text("Resume", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { onAction("stop") }) {
                        Text("Stop", fontSize = 12.sp)
                    }
                }
            }

            OutlinedButton(
                onClick = { onAction("step_back") },
                enabled = hasRoute && routeState.currentFrame > 0,
            ) {
                Text("◀ Step", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { onAction("step_forward") },
                enabled = hasRoute && routeState.currentFrame < (route?.frameCount ?: 0) - 1,
            ) {
                Text("Step ▶", fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onAction("save") },
                enabled = hasRoute,
            ) {
                Text("Save Route", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { onAction("clear") },
                enabled = hasRoute,
            ) {
                Text("Clear", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RouteFrameTimeline(routeState: RouteEditorState) {
    val route = routeState.currentRoute
    if (route == null || route.frameCount == 0) {
        Text(
            "No route loaded",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Slider(
            value = routeState.currentFrame.toFloat(),
            onValueChange = { routeState.seekToFrame(it.toInt()) },
            valueRange = 0f..(route.frameCount - 1).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val frameCount = route.frameCount

                route.inputs.forEach { input ->
                    val x = (input.frame.toFloat() / frameCount) * width
                    drawLine(
                        color = Color(0xFF7DE1D1),
                        start = Offset(x, 0f),
                        end = Offset(x, height),
                        strokeWidth = 2f,
                    )
                }

                val currentX = (routeState.currentFrame.toFloat() / frameCount) * width
                drawLine(
                    color = Color(0xFFFF6B6B),
                    start = Offset(currentX, 0f),
                    end = Offset(currentX, height),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

@Composable
private fun RouteInputList(
    routeState: RouteEditorState,
    onFrameSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = routeState.currentRoute
    if (route == null || route.inputs.isEmpty()) {
        Text(
            "No inputs recorded yet",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 8.dp),
        )
        return
    }

    val listState = rememberLazyListState()

    LaunchedEffect(routeState.currentFrame) {
        val currentIndex = route.inputs.indexOfFirst { it.frame == routeState.currentFrame }
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(route.inputs) { input ->
            val isCurrentFrame = input.frame == routeState.currentFrame
            val buttonsText = input.buttons
                .mapIndexedNotNull { index, value ->
                    BUTTON_LABELS.getOrNull(index)?.takeIf { value != 0 }
                }
                .joinToString(" ")
                .ifEmpty { "none" }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onFrameSelected(input.frame) },
                color = if (isCurrentFrame) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "F${input.frame.toString().padStart(5, '0')}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isCurrentFrame) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        buttonsText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isCurrentFrame) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteLibraryList(
    routeState: RouteEditorState,
    onLoadRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val availableRoutes = routeState.availableRoutes

    if (availableRoutes.isEmpty()) {
        Text(
            "No saved routes found in ${routeState.routeDirectory}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 8.dp),
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        availableRoutes.forEach { routeName ->
            val isCurrentRoute = routeState.currentRoute?.name == routeName
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onLoadRoute(routeName) },
                color = if (isCurrentRoute) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(
                    routeName,
                    fontSize = 11.sp,
                    color = if (isCurrentRoute) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}
