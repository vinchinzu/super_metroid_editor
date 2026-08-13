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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.supermetroid.editor.tas.TasInput
import kotlinx.coroutines.launch

private val BUTTON_LABELS = TasInput.BUTTON_ORDER

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
                Text("TAS Movie Editor", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                                "save" -> routeState.saveMovie()
                                "clear" -> routeState.clearMovie()
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
                    Text("Frame Timeline", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    RouteTimelinePanel(
                        routeState = routeState,
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
                    Text("Movie Library", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    RouteLibraryList(
                        routeState = routeState,
                        onLoadRoute = { scope.launch { routeState.loadMovie(it) } },
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
    val movie = routeState.currentMovie
    val playbackState = routeState.playbackState
    val canRecord = emulatorState?.session?.active == true
    val hasMovie = movie != null

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (movie != null) {
            Text(
                "Movie: ${movie.frameCount} frames  ·  ${movie.trace.size} trace points  ·  Start: ${movie.meta.startState ?: "none"}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Current frame: ${routeState.currentFrame} / ${movie.frameCount}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            
            val residual = routeState.residualProfile
            if (residual != null) {
                val fdSubpixel = residual.firstDifferingSubpixel?.toString() ?: "n.m."
                val fdPixel = residual.firstDifferingPixel?.toString() ?: "n.m."
                val fdPose = residual.firstDifferingPose?.toString() ?: "n.m."
                val fdRoom = residual.firstDifferingRoom?.toString() ?: "n.m."
                
                Text(
                    "Residual: R(τ) = (σ+=$fdSubpixel, σ=$fdPixel, π=$fdPose, †=$fdRoom)",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (residual.firstDifferingField != null) {
                    Text(
                        "First diff: ${residual.firstDifferingField} — ${residual.cause ?: "unknown"}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (residual.cause != null) {
                    Text(
                        residual.cause,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                        enabled = hasMovie,
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
                enabled = hasMovie && routeState.currentFrame > 0,
            ) {
                Text("◀ Step", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = { onAction("step_forward") },
                enabled = hasMovie && routeState.currentFrame < (movie?.frameCount ?: 0) - 1,
            ) {
                Text("Step ▶", fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onAction("save") },
                enabled = hasMovie,
            ) {
                Text("Save Movie", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { onAction("clear") },
                enabled = hasMovie,
            ) {
                Text("Clear", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RouteTimelinePanel(
    routeState: RouteEditorState,
    modifier: Modifier = Modifier,
) {
    val movie = routeState.currentMovie
    if (movie == null || movie.frameCount == 0) {
        Text(
            "No movie loaded",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 8.dp),
        )
        return
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            value = routeState.currentFrame.toFloat(),
            onValueChange = { routeState.seekToFrame(it.toInt()) },
            valueRange = 0f..(movie.frameCount - 1).toFloat().coerceAtLeast(0f),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { routeState.seekToFrame((routeState.currentFrame - 10).coerceAtLeast(0)) },
                enabled = routeState.currentFrame > 0,
            ) {
                Text("-10", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { routeState.seekToFrame(routeState.currentFrame + 10) },
                enabled = routeState.currentFrame < movie.frameCount - 1,
            ) {
                Text("+10", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { routeState.truncateMovie(routeState.currentFrame) },
                enabled = routeState.currentFrame > 0,
            ) {
                Text("Truncate here", fontSize = 11.sp)
            }
        }

        val listState = rememberLazyListState()

        LaunchedEffect(routeState.currentFrame) {
            listState.animateScrollToItem(routeState.currentFrame.coerceIn(0, movie.frameCount - 1))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(movie.frameCount) { frameIdx ->
                val trust = routeState.getFrameTrust(frameIdx)
                FrameInputRow(
                    frame = frameIdx,
                    input = movie.frameAt(frameIdx) ?: TasInput.noop(),
                    isSelected = frameIdx == routeState.currentFrame,
                    frameTrust = trust,
                    onSelect = { routeState.seekToFrame(frameIdx) },
                    onUpdate = { newButtons -> routeState.updateFrame(frameIdx, newButtons) },
                )
            }
        }
    }
}

@Composable
private fun FrameInputRow(
    frame: Int,
    input: IntArray,
    isSelected: Boolean,
    frameTrust: FrameTrust? = null,
    onSelect: () -> Unit,
    onUpdate: (IntArray) -> Unit,
) {
    val baseColor = when (frameTrust) {
        FrameTrust.TRUSTWORTHY -> MaterialTheme.colorScheme.surfaceVariant
        FrameTrust.SPOT_CHECK -> Color(0xFFFFF9C4)
        FrameTrust.DEAD -> Color(0xFFFFCDD2)
        FrameTrust.UNMEASURED -> MaterialTheme.colorScheme.surfaceVariant
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    val selectedColor = when (frameTrust) {
        FrameTrust.TRUSTWORTHY -> MaterialTheme.colorScheme.primaryContainer
        FrameTrust.SPOT_CHECK -> Color(0xFFFFF59D)
        FrameTrust.DEAD -> Color(0xFFEF9A9A)
        FrameTrust.UNMEASURED -> MaterialTheme.colorScheme.primaryContainer
        null -> MaterialTheme.colorScheme.primaryContainer
    }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onSelect() },
        color = if (isSelected) selectedColor else baseColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${frame.toString().padStart(5, '0')}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(40.dp),
            )

            for (buttonIdx in 0 until 12) {
                val isPressed = input.getOrNull(buttonIdx) == 1
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isPressed) Color(0xFF4CAF50) else Color(0xFF424242))
                        .border(
                            width = 1.dp,
                            color = if (isPressed) Color(0xFF66BB6A) else Color(0xFF616161),
                            shape = RoundedCornerShape(3.dp),
                        )
                        .clickable {
                            val newInput = input.toMutableList()
                            while (newInput.size < 12) newInput.add(0)
                            newInput[buttonIdx] = if (isPressed) 0 else 1
                            onUpdate(newInput.toIntArray())
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        BUTTON_LABELS.getOrNull(buttonIdx)?.take(1) ?: "",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPressed) Color.White else Color(0xFF9E9E9E),
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
            val isCurrentRoute = false
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
