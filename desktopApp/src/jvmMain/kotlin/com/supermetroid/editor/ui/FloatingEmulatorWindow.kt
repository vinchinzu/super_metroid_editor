package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.supermetroid.editor.rom.RomParser
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val SNES_ASPECT = 4f / 3f // CRT display aspect ratio
private const val TARGET_EMU_FPS = 60.0
private const val MAX_STEP_REPEAT = 4
private const val FRAME_REFRESH_INTERVAL = 2
private const val TRACE_REFRESH_INTERVAL = 10
private const val FRAME_DURATION_NANOS = (1_000_000_000.0 / TARGET_EMU_FPS).toLong()
private const val CONTROL_BAR_HEIGHT = 36
private const val TITLE_BAR_HEIGHT = 24
private const val MIN_WIDTH = 280f
private const val MAX_WIDTH = 900f

@Composable
fun FloatingEmulatorWindow(
    workspaceState: EmulatorWorkspaceState,
    editorState: EditorState,
    romParser: RomParser?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Draggable position
    var offsetX by remember { mutableStateOf(40f) }
    var offsetY by remember { mutableStateOf(40f) }

    // Resizable width (height derived from aspect ratio)
    var windowWidth by remember { mutableStateOf(512f) }

    // Fast forward state
    var fastForwarding by remember { mutableStateOf(false) }

    // Save/load slot dropdown state
    var showSaveMenu by remember { mutableStateOf(false) }

    // Derived height: title bar + video (proportional) + control bar
    val videoHeight = windowWidth / SNES_ASPECT
    val totalHeight = TITLE_BAR_HEIGHT + videoHeight + CONTROL_BAR_HEIGHT

    // Auto-set ROM path from editor
    LaunchedEffect(editorState.project.romPath) {
        val romPath = editorState.project.romPath.takeIf { it.isNotBlank() }
        workspaceState.updateRomPath(romPath)
    }

    // Emulator frame stepping loop
    LaunchedEffect(workspaceState.isRunning, workspaceState.session.active) {
        var tick = 0L
        var pendingFrames = 0.0
        var lastWallClockNanos = System.nanoTime()
        while (workspaceState.isRunning && workspaceState.session.active) {
            val now = System.nanoTime()
            pendingFrames += (now - lastWallClockNanos).toDouble() / FRAME_DURATION_NANOS.toDouble()
            lastWallClockNanos = now
            if (pendingFrames < 1.0) {
                val waitMs = ceil(((1.0 - pendingFrames) * FRAME_DURATION_NANOS) / 1_000_000.0).toLong().coerceAtLeast(1L)
                delay(waitMs)
                continue
            }
            val baseRepeat = pendingFrames.toInt().coerceIn(1, MAX_STEP_REPEAT)
            val repeat = if (fastForwarding) (baseRepeat * 4).coerceAtMost(16) else baseRepeat
            pendingFrames = (pendingFrames - baseRepeat).coerceAtMost(MAX_STEP_REPEAT.toDouble())
            workspaceState.stepFrame(
                repeat = repeat,
                includeFrame = tick % FRAME_REFRESH_INTERVAL == 0L,
                includeTrace = tick % TRACE_REFRESH_INTERVAL == 0L,
            )
            // Process gamepad combo actions (save/load/slot cycle)
            workspaceState.pendingComboAction?.let { combo ->
                workspaceState.pendingComboAction = null
                val ws = workspaceState
                when (combo) {
                    "save" -> ws.saveQuickState("slot_${ws.saveSlotIndex}")
                    "load" -> ws.loadNamedState("slot_${ws.saveSlotIndex}")
                    "slot_up" -> ws.saveSlotIndex = (ws.saveSlotIndex + 1) % 129
                    "slot_down" -> ws.saveSlotIndex = (ws.saveSlotIndex - 1 + 129) % 129
                }
            }
            tick += 1
            if (pendingFrames > MAX_STEP_REPEAT * 2) {
                pendingFrames = MAX_STEP_REPEAT.toDouble()
            }
        }
    }

    // Auto-focus when a frame appears
    LaunchedEffect(workspaceState.session.active, workspaceState.frameBitmap) {
        if (workspaceState.session.active && workspaceState.frameBitmap != null) {
            delay(16)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(windowWidth.dp)
            .height(totalHeight.dp),
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 12.dp,
        tonalElevation = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Title bar (draggable) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TITLE_BAR_HEIGHT.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Emulator",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (workspaceState.session.active) {
                        Text(
                            "${workspaceState.emulatedFps.toInt()} fps",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (workspaceState.gamepadManager.isConnected) {
                        Icon(
                            Icons.Default.Gamepad,
                            contentDescription = "Gamepad connected",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF4CAF50),
                        )
                    }
                    // Resize handle: drag to resize
                    Text(
                        "\u2921", // diagonal resize arrow
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(24.dp, 24.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    windowWidth = (windowWidth + dragAmount.x).coerceIn(MIN_WIDTH, MAX_WIDTH)
                                }
                            },
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close emulator",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Video viewport (keyboard-focusable) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0B0F12))
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (event.key == Key.Spacebar) {
                                    workspaceState.setLoopRunning(!workspaceState.isRunning)
                                    true
                                } else if (event.key == Key.F1) {
                                    scope.launch {
                                        if (event.isShiftPressed) {
                                            workspaceState.clearCheckpointModifierKeys()
                                            workspaceState.loadSlot(workspaceState.saveSlots[0])
                                        } else workspaceState.saveSlot(0)
                                    }
                                    true
                                } else if (event.key == Key.F2) {
                                    scope.launch {
                                        if (event.isShiftPressed) {
                                            workspaceState.clearCheckpointModifierKeys()
                                            workspaceState.loadSlot(workspaceState.saveSlots[1])
                                        } else workspaceState.saveSlot(1)
                                    }
                                    true
                                } else if (event.key == Key.F3) {
                                    scope.launch {
                                        if (event.isShiftPressed) {
                                            workspaceState.clearCheckpointModifierKeys()
                                            workspaceState.loadSlot(workspaceState.saveSlots[2])
                                        } else workspaceState.saveSlot(2)
                                    }
                                    true
                                } else if (event.key == Key.F4) {
                                    scope.launch {
                                        if (event.isShiftPressed) {
                                            workspaceState.clearCheckpointModifierKeys()
                                            workspaceState.loadSlot(workspaceState.saveSlots[3])
                                        } else workspaceState.saveSlot(3)
                                    }
                                    true
                                } else if (event.key == Key.F5) {
                                    scope.launch { workspaceState.saveQuickState() }
                                    true
                                } else if (event.key == Key.Grave) {
                                    scope.launch { workspaceState.reloadLastCheckpoint() }
                                    true
                                } else {
                                    workspaceState.updateKey(event.key, down = true)
                                    false
                                }
                            }
                            KeyEventType.KeyUp -> {
                                workspaceState.updateKey(event.key, down = false)
                                false
                            }
                            else -> false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = workspaceState.frameBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Emulator frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            workspaceState.statusMessage,
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Arrows + Z/X/A/S/Q/W | F1-F4 save/load",
                            color = Color(0xFFB6C3CC),
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            // ── Control bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CONTROL_BAR_HEIGHT.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // Play / Pause
                IconButton(
                    onClick = {
                        scope.launch {
                            if (!workspaceState.isConnected) workspaceState.connectBridge()
                            if (workspaceState.isConnected && !workspaceState.session.active) {
                                val rp = romParser
                                if (rp != null) {
                                    val patchedPath = editorState.exportToRom(rp)
                                    if (patchedPath != null) {
                                        workspaceState.updateRomPath(patchedPath)
                                    }
                                }
                                workspaceState.startSession()
                                workspaceState.setLoopRunning(true)
                            } else if (workspaceState.session.active) {
                                workspaceState.setLoopRunning(!workspaceState.isRunning)
                            }
                        }
                    },
                    enabled = !workspaceState.isBusy,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                ) {
                    Icon(
                        if (workspaceState.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (workspaceState.isRunning) "Pause" else "Play",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Fast forward (hold to speed up)
                IconButton(
                    onClick = { fastForwarding = !fastForwarding },
                    enabled = workspaceState.session.active && !workspaceState.isBusy,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.FastForward, "Fast forward",
                        Modifier.size(18.dp),
                        tint = if (fastForwarding) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Restart (rebuild patched ROM + restart session)
                IconButton(
                    onClick = {
                        scope.launch {
                            workspaceState.disconnectBridge()
                            workspaceState.connectBridge()
                            if (workspaceState.isConnected) {
                                val rp = romParser
                                if (rp != null) {
                                    val patchedPath = editorState.exportToRom(rp)
                                    if (patchedPath != null) {
                                        workspaceState.updateRomPath(patchedPath)
                                    }
                                }
                                workspaceState.startSession()
                                workspaceState.setLoopRunning(true)
                            }
                        }
                    },
                    enabled = workspaceState.session.active && !workspaceState.isBusy,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.Refresh, "Restart with latest patches", Modifier.size(18.dp))
                }

                Spacer(Modifier.width(4.dp))

                // Pipe separator
                Text(
                    "|",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )

                Spacer(Modifier.width(4.dp))

                // SAVE text button
                Surface(
                    onClick = {
                        scope.launch { workspaceState.saveQuickState("slot_${workspaceState.saveSlotIndex}") }
                    },
                    enabled = workspaceState.session.active && !workspaceState.isBusy,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "SAVE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }

                Spacer(Modifier.width(2.dp))

                // LOAD text button
                Surface(
                    onClick = {
                        scope.launch { workspaceState.loadNamedState("slot_${workspaceState.saveSlotIndex}") }
                    },
                    enabled = workspaceState.session.active && !workspaceState.isBusy,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "LOAD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }

                Spacer(Modifier.width(2.dp))

                // Slot number + dropdown
                Box {
                    Surface(
                        onClick = { showSaveMenu = !showSaveMenu },
                        color = Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        ) {
                            Text(
                                "${workspaceState.saveSlotIndex}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                Icons.Default.ArrowDropDown, "Select slot",
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showSaveMenu,
                        onDismissRequest = { showSaveMenu = false },
                        modifier = Modifier.widthIn(max = 80.dp),
                    ) {
                        for (i in 0..128) {
                            DropdownMenuItem(
                                text = { Text("$i", fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                onClick = { workspaceState.saveSlotIndex = i; showSaveMenu = false },
                                modifier = Modifier.height(24.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Mute toggle
                IconButton(
                    onClick = { workspaceState.toggleAudioMute() },
                    enabled = workspaceState.isConnected,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        if (workspaceState.audioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Toggle mute",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
