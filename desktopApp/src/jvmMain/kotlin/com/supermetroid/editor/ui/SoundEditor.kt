package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.*
import kotlin.math.abs
import kotlin.math.max

// ─── Audio playback engine ──────────────────────────────────────────────

class SoundPlayer {
    private var clip: Clip? = null
    private var totalFrames: Long = 0
    var onComplete: (() -> Unit)? = null

    fun play(pcmSamples: ShortArray, sampleRate: Int = 32000, loop: Boolean = false, startFrame: Long = 0) {
        stop()
        if (pcmSamples.isEmpty()) return

        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val byteData = pcmToBytes(pcmSamples)

        try {
            val newClip = AudioSystem.getClip()
            newClip.open(format, byteData, 0, byteData.size)
            totalFrames = newClip.frameLength.toLong()
            if (startFrame > 0) {
                newClip.framePosition = startFrame.toInt().coerceIn(0, newClip.frameLength - 1)
            }
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP && !loop) {
                    onComplete?.invoke()
                }
            }
            if (loop) newClip.loop(Clip.LOOP_CONTINUOUSLY) else newClip.start()
            clip = newClip
        } catch (e: Exception) {
            System.err.println("Audio playback error: ${e.message}")
        }
    }

    fun stop() {
        clip?.let {
            try { if (it.isRunning) it.stop(); it.close() } catch (_: Exception) {}
        }
        clip = null
        totalFrames = 0
    }

    fun isActive(): Boolean = clip?.isRunning == true

    fun positionFraction(): Float {
        val c = clip ?: return 0f
        if (totalFrames <= 0) return 0f
        return (c.framePosition.toFloat() / totalFrames).coerceIn(0f, 1f)
    }

    fun seekFraction(fraction: Float) {
        val c = clip ?: return
        val frame = (fraction * totalFrames).toInt().coerceIn(0, c.frameLength - 1)
        c.framePosition = frame
    }
}

private fun pcmToBytes(pcm: ShortArray): ByteArray {
    val bytes = ByteArray(pcm.size * 2)
    for (i in pcm.indices) {
        val s = pcm[i].toInt()
        bytes[i * 2] = (s and 0xFF).toByte()
        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return bytes
}

fun exportWav(pcm: ShortArray, sampleRate: Int, file: File) {
    val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    val bytes = pcmToBytes(pcm)
    val bais = ByteArrayInputStream(bytes)
    val ais = AudioInputStream(bais, format, pcm.size.toLong())
    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file)
}

// ─── Waveform / bar visualization ───────────────────────────────────────

private val GRADIENT_COLORS = arrayOf(
    java.awt.Color(0x6C, 0x5C, 0xE7),
    java.awt.Color(0x00, 0xB8, 0x94),
    java.awt.Color(0x00, 0xCC, 0x76),
    java.awt.Color(0xFD, 0xCB, 0x6E),
    java.awt.Color(0xE1, 0x7E, 0x55),
    java.awt.Color(0xE0, 0x56, 0x6B),
)

private fun lerpColor(a: java.awt.Color, b: java.awt.Color, t: Float): java.awt.Color {
    val r = (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255)
    val g = (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255)
    val bl = (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255)
    return java.awt.Color(r, g, bl)
}

private fun gradientColor(amplitude: Float): java.awt.Color {
    val t = amplitude.coerceIn(0f, 1f) * (GRADIENT_COLORS.size - 1)
    val i = t.toInt().coerceIn(0, GRADIENT_COLORS.size - 2)
    return lerpColor(GRADIENT_COLORS[i], GRADIENT_COLORS[i + 1], t - i)
}

private fun renderWaveformBars(
    samples: ShortArray,
    width: Int,
    height: Int,
    loopStart: Int = -1,
    playbackFraction: Float = -1f
): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = java.awt.Color(0x0C, 0x0C, 0x18)
    g.fillRect(0, 0, width, height)
    if (samples.isEmpty()) { g.dispose(); return img }

    val midY = height / 2
    val barWidth = max(1, 2)
    val gap = 1
    val barCount = width / (barWidth + gap)

    for (i in 0 until barCount) {
        val startS = (i.toLong() * samples.size / barCount).toInt()
        val endS = minOf(((i.toLong() + 1) * samples.size / barCount).toInt(), samples.size)
        if (startS >= samples.size) break

        var sumSq = 0.0
        var peak = 0
        for (j in startS until endS) {
            val v = abs(samples[j].toInt())
            sumSq += v.toDouble() * v
            if (v > peak) peak = v
        }
        val rms = kotlin.math.sqrt(sumSq / (endS - startS)).toInt()
        val peakH = (peak * (midY - 2) / 32768f).toInt().coerceAtLeast(1)
        val rmsH = (rms * (midY - 2) / 32768f).toInt().coerceAtLeast(1)

        val x = i * (barWidth + gap)
        val amplitude = peak / 32768f

        val barColor = gradientColor(amplitude)
        g.color = barColor
        g.fillRect(x, midY - rmsH, barWidth, rmsH * 2)

        g.color = java.awt.Color(barColor.red, barColor.green, barColor.blue, 80)
        g.fillRect(x, midY - peakH, barWidth, peakH * 2)
    }

    g.color = java.awt.Color(0xFF, 0xFF, 0xFF, 0x18)
    g.drawLine(0, midY, width, midY)

    if (loopStart in 0 until samples.size) {
        val loopX = (loopStart.toLong() * width / samples.size).toInt()
        g.color = java.awt.Color(0xFD, 0xCB, 0x6E, 0xCC)
        g.drawLine(loopX, 0, loopX, height)
        g.color = java.awt.Color(0xFD, 0xCB, 0x6E, 0x44)
        g.fillRect(loopX, 0, 3, height)
    }

    if (playbackFraction in 0f..1f) {
        val px = (playbackFraction * width).toInt().coerceIn(0, width - 1)
        g.color = java.awt.Color(0xFF, 0x22, 0x22, 0xDD)
        g.fillRect(px - 1, 0, 3, height)
    }

    g.dispose()
    return img
}

// ─── Left column: sound list panel ──────────────────────────────────────

@Composable
fun SoundListPanel(
    romParser: RomParser?,
    @Suppress("UNUSED_PARAMETER") editorState: EditorState,
    soundEditorState: SoundEditorState,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth().height(32.dp)) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, modifier = Modifier.height(32.dp)) {
                Text("Tracks", fontSize = 12.sp)
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, modifier = Modifier.height(32.dp)) {
                Text("Samples", fontSize = 12.sp)
            }
        }
        key(selectedTab) {
            when (selectedTab) {
                0 -> TrackListContent(romParser, soundEditorState, Modifier.fillMaxSize())
                1 -> SampleListContent(romParser, soundEditorState, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TrackListContent(
    romParser: RomParser?,
    state: SoundEditorState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        if (romParser == null) {
            Text("Load a ROM to browse tracks", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        } else {
            for (track in SpcData.KNOWN_TRACKS) {
                val isSel = state.selectedTrackId == track.id
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { state.selectTrack(track) }
                        .padding(horizontal = 2.dp),
                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                            Text("${track.area}  0x${track.songSet.toString(16).uppercase().padStart(2, '0')}:${track.playIndex.toString(16).uppercase().padStart(2, '0')}",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleListContent(
    romParser: RomParser?,
    state: SoundEditorState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(romParser) {
        if (romParser != null && state.samples.isEmpty()) {
            try {
                state.loadSamples(romParser)
            } catch (e: Exception) {
                System.err.println("[SPC] Sample load error: ${e.message}")
            }
        }
    }

    val currentSamples = state.samples
    val loading = state.isLoadingSamples

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                Text("Loading samples...", fontSize = 12.sp)
            }
        } else if (currentSamples.isEmpty()) {
            Text("No samples found.\nLoad a ROM first.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else {
            for (sample in currentSamples) {
                val isSel = state.selectedSampleIndex == sample.dirEntry.index
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { scope.launch { state.selectSample(sample) } }
                        .padding(horizontal = 2.dp),
                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("#${sample.dirEntry.index}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sample ${sample.dirEntry.index}", fontSize = 12.sp, maxLines = 1, lineHeight = 15.sp)
                            Text("${sample.pcmData.size} pcm" + if (sample.loopStart >= 0) " (loop)" else "",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 13.sp)
                        }
                        Icon(Icons.Default.PlayArrow, "Play",
                            modifier = Modifier.size(18.dp).clickable {
                                state.playSample(sample)
                            }, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ─── Right canvas: sound editor view ─────────────────────────────────────

@Composable
fun SoundEditorCanvas(
    romParser: RomParser?,
    @Suppress("UNUSED_PARAMETER") editorState: EditorState,
    soundEditorState: SoundEditorState,
    modifier: Modifier = Modifier
) {
    val state = soundEditorState
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.selectedTrackId, state.sampleRate) {
        if (state.selectedTrack != null && romParser != null) {
            try {
                state.loadTrackSamples(romParser)
            } catch (e: Exception) {
                System.err.println("[SPC] Track load error in LaunchedEffect: ${e.message}")
            }
        }
    }

    // Handle play-all auto-advance (must happen on composition thread)
    LaunchedEffect(state.pendingNextTrack) {
        state.checkPendingAdvance()
    }

    // Snapshot state at the top to prevent mid-composition changes
    val track = state.selectedTrack
    val sample = state.selectedSample
    val waveform = state.currentWaveform
    val loading = state.isLoadingTrack
    val loopStart = state.currentLoopStart
    val status = state.statusMessage

    // Derive a stable view mode to use as a key, preventing the Stack.pop crash
    // by forcing Compose to recreate the subtree when the mode changes instead
    // of trying to diff incompatible branches.
    val viewMode = when {
        track != null -> 1
        sample != null -> 2
        else -> 0
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Sound", fontWeight = FontWeight.Bold, fontSize = 12.sp)

            if (track != null) {
                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                Text(track.name, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(track.area, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (sample != null) {
                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                Text("Sample #${sample.dirEntry.index}", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text("${sample.pcmData.size} pcm" + if (sample.loopStart >= 0) " loop" else "",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(1f))

            if (status.isNotEmpty()) {
                Text(status, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.height(4.dp))

        key(viewMode) {
            if (viewMode != 0) {
                SoundEditorActiveContent(
                    state = state,
                    scope = scope,
                    track = track,
                    sample = sample,
                    waveform = waveform,
                    loading = loading,
                    loopStart = loopStart,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Select a track or sample", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("Browse tracks and BRR samples from the ROM", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundEditorActiveContent(
    state: SoundEditorState,
    scope: kotlinx.coroutines.CoroutineScope,
    track: SpcData.TrackInfo?,
    sample: SoundEditorState.DecodedSample?,
    waveform: ShortArray?,
    loading: Boolean,
    loopStart: Int,
    modifier: Modifier = Modifier
) {
    // Poll playback position
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            state.updatePlaybackPosition()
            kotlinx.coroutines.delay(50)
        }
    }

    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    scope.launch {
                        if (sample != null) state.playSample(sample, loop = state.loopEnabled)
                        else state.playTrackPreview(state.playbackPosition)
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(28.dp),
                enabled = (sample != null || waveform != null) && !loading
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Play", fontSize = 10.sp)
            }

            // Play All button
            val playAllOn = state.playAllEnabled
            Surface(
                modifier = Modifier.height(28.dp).clickable {
                    state.playAllEnabled = !state.playAllEnabled
                    if (state.playAllEnabled && !state.isPlaying) {
                        scope.launch { state.playTrackPreview() }
                    }
                },
                color = if (playAllOn) Color(0xFF2196F3) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (playAllOn) Color(0xFF2196F3) else MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QueueMusic, null, Modifier.size(14.dp),
                        tint = if (playAllOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text("Play All", fontSize = 10.sp,
                        color = if (playAllOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedButton(
                onClick = { state.stopPlayback(); state.playAllEnabled = false },
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Stop, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Stop", fontSize = 10.sp)
            }

            // Loop toggle
            val loopOn = state.loopEnabled
            Surface(
                modifier = Modifier.height(28.dp).clickable { state.loopEnabled = !state.loopEnabled },
                color = if (loopOn) Color(0xFF7C4DFF) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (loopOn) Color(0xFF7C4DFF) else MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Loop, null, Modifier.size(14.dp),
                        tint = if (loopOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text("Loop", fontSize = 10.sp,
                        color = if (loopOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Export WAV
            if (waveform != null && waveform.isNotEmpty()) {
                OutlinedButton(
                    onClick = { scope.launch { state.exportCurrentWav() } },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.SaveAlt, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Export WAV", fontSize = 10.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            Text("Rate:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (rate in listOf(8000, 16000, 32000)) {
                FilterChip(
                    selected = state.sampleRate == rate,
                    onClick = { state.sampleRate = rate },
                    label = { Text("${rate / 1000}k", fontSize = 8.sp) },
                    modifier = Modifier.height(22.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Waveform display
        val waveformKey = waveform?.size ?: 0
        val pbPos = state.playbackPosition
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0C0C18), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF2A2A3A), RoundedCornerShape(6.dp))
        ) {
            if (waveform != null && waveform.isNotEmpty()) {
                val waveImg = remember(waveformKey, loopStart, pbPos) {
                    renderWaveformBars(waveform, 900, 240, loopStart, if (state.isPlaying || pbPos > 0f) pbPos else -1f)
                        .toComposeImageBitmap()
                }
                Image(bitmap = waveImg, contentDescription = "Waveform",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {}
                        .pointerInput(waveform) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                state.seekTo(frac)
                                if (state.isPlaying) {
                                    scope.launch {
                                        val s = state.selectedSample
                                        if (s != null) state.playSample(s, loop = state.loopEnabled)
                                        else state.playTrackPreview(frac)
                                    }
                                }
                            }
                        })

                val durationMs = waveform.size * 1000L / state.sampleRate
                val peak = waveform.maxOf { abs(it.toInt()) }
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("${durationMs}ms", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    Text("${waveform.size} samples", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    Text("${state.sampleRate}Hz", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    Text("peak: $peak", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    if (loopStart >= 0) {
                        Spacer(Modifier.weight(1f))
                        Text("loop @ $loopStart", fontSize = 9.sp, color = Color(0xFFFDCB6E))
                    }
                }
            } else if (loading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("Loading instruments...",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Select a track or sample to see waveform",
                        color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
                }
            }
        }

        // Detail row
        Spacer(Modifier.height(4.dp))
        if (track != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 4.dp)) {
                Text("Song Set: 0x${track.songSet.toString(16).uppercase().padStart(2, '0')}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Play Index: 0x${track.playIndex.toString(16).uppercase().padStart(2, '0')}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Area: ${track.area}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (sample != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 4.dp)) {
                Text("SPC: 0x${sample.dirEntry.startAddr.toString(16).uppercase().padStart(4, '0')}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sample.loopStart >= 0) {
                    Text("Loop: 0x${sample.dirEntry.loopAddr.toString(16).uppercase().padStart(4, '0')}",
                        fontSize = 9.sp, color = Color(0xFFFDCB6E))
                }
            }
        }
    }
}

// ─── Sound editor state ──────────────────────────────────────────────

class SoundEditorState {
    private val player = SoundPlayer()

    var selectedTrackId by mutableStateOf(-1)
        private set
    var selectedTrack by mutableStateOf<SpcData.TrackInfo?>(null)
        private set
    var selectedSampleIndex by mutableStateOf(-1)
        private set
    var selectedSample by mutableStateOf<DecodedSample?>(null)
        private set
    var currentWaveform by mutableStateOf<ShortArray?>(null)
        private set
    var currentLoopStart by mutableStateOf(-1)
        private set
    var sampleRate by mutableStateOf(32000)
    var loopEnabled by mutableStateOf(false)
    var autoPlayEnabled by mutableStateOf(false)
    var playAllEnabled by mutableStateOf(false)
    var statusMessage by mutableStateOf("")
        private set
    var isLoadingSamples by mutableStateOf(false)
        private set
    var isLoadingTrack by mutableStateOf(false)
        private set
    var samples by mutableStateOf<List<DecodedSample>>(emptyList())
        private set
    var trackSamples by mutableStateOf<List<DecodedSample>>(emptyList())
        private set
    var trackUniqueSamples by mutableStateOf<List<DecodedSample>>(emptyList())
        private set

    var playbackPosition by mutableStateOf(0f)
    var isPlaying by mutableStateOf(false)
        private set

    private var spcRam: ByteArray? = null
    private var romParserRef: RomParser? = null
    private var lastLoadedSongSet = -1
    private var loadVersion = 0

    data class DecodedSample(
        val dirEntry: SpcData.SampleDirEntry,
        val pcmData: ShortArray,
        val loopStart: Int
    )

    fun selectTrack(track: SpcData.TrackInfo) {
        val wasPlaying = player.isActive()
        player.stop()
        playbackPosition = 0f

        val clearSamples = track.songSet != lastLoadedSongSet
        selectedTrackId = track.id
        selectedTrack = track
        selectedSample = null
        selectedSampleIndex = -1
        statusMessage = "Track: ${track.name}"
        if (wasPlaying) autoPlayEnabled = true
        if (clearSamples) {
            trackSamples = emptyList()
            trackUniqueSamples = emptyList()
            currentWaveform = null
            currentLoopStart = -1
        }
    }

    suspend fun selectSample(sample: DecodedSample) {
        val extended = withContext(Dispatchers.Default) {
            extendWithLoop(sample.pcmData, sample.loopStart, sampleRate, 4.0)
        }
        selectedSampleIndex = sample.dirEntry.index
        selectedSample = sample
        selectedTrack = null
        selectedTrackId = -1
        currentWaveform = extended
        currentLoopStart = sample.loopStart
        statusMessage = "Sample #${sample.dirEntry.index}: ${sample.pcmData.size} pcm" +
            if (sample.loopStart >= 0) " (extended to ${extended.size})" else ""
    }

    suspend fun loadSamples(romParser: RomParser) {
        isLoadingSamples = true
        statusMessage = "Loading SPC data..."
        try {
            val result = withContext(Dispatchers.Default) {
                val ram = SpcData.buildInitialSpcRam(romParser)
                val dir = SpcData.findSampleDirectory(ram)
                System.err.println("[SPC] Sample directory: ${dir.size} entries")
                val decoded = dir.mapNotNull { entry ->
                    try {
                        val (pcm, loop) = SpcData.decodeBrrWithLoop(ram, entry)
                        if (pcm.isNotEmpty()) DecodedSample(entry, pcm, loop) else null
                    } catch (e: Exception) {
                        System.err.println("[SPC]   Failed to decode #${entry.index}: ${e.message}")
                        null
                    }
                }
                Pair(ram, decoded)
            }
            spcRam = result.first
            samples = result.second
            statusMessage = "Loaded ${result.second.size} BRR samples"
        } catch (e: Exception) {
            statusMessage = "Error: ${e.message}"
            System.err.println("[SPC] Load failed: ${e.message}")
        } finally {
            isLoadingSamples = false
        }
    }

    suspend fun loadTrackSamples(romParser: RomParser) {
        val track = selectedTrack ?: return
        val version = ++loadVersion
        romParserRef = romParser

        if (track.songSet == lastLoadedSongSet && trackSamples.isNotEmpty()) {
            buildCompositeWaveform(track, version)
            return
        }
        isLoadingTrack = true
        statusMessage = "Loading instruments for ${track.name}..."

        try {
            val result = withContext(Dispatchers.Default) {
                val baseRam = spcRam ?: SpcData.buildInitialSpcRam(romParser)
                val ram = baseRam.copyOf()

                val blocks = SpcData.findSongSetTransferData(romParser, track.songSet)
                System.err.println("[SPC] Track '${track.name}' songSet=0x${track.songSet.toString(16)}: ${blocks.size} transfer blocks")

                val baseDir = SpcData.findSampleDirectory(baseRam)
                val baseDirMap = baseDir.associate { it.index to it }

                if (blocks.isNotEmpty()) {
                    SpcData.applyTransferBlocks(ram, blocks)
                }
                val dir = SpcData.findSampleDirectory(ram)

                val modifiedRanges = blocks.map { it.destAddr until (it.destAddr + it.data.size) }

                val allDecoded = mutableListOf<DecodedSample>()
                val uniqueDecoded = mutableListOf<DecodedSample>()

                for (entry in dir) {
                    try {
                        val (pcm, loop) = SpcData.decodeBrrWithLoop(ram, entry)
                        if (pcm.size <= 16 || pcm.all { it.toInt() == 0 }) continue
                        val sample = DecodedSample(entry, pcm, loop)
                        allDecoded.add(sample)

                        val brrModified = modifiedRanges.any { range ->
                            entry.startAddr in range || (entry.startAddr + 9) in range
                        }
                        val baseEntry = baseDirMap[entry.index]
                        val dirChanged = baseEntry == null ||
                            baseEntry.startAddr != entry.startAddr ||
                            baseEntry.loopAddr != entry.loopAddr

                        if (brrModified || dirChanged) uniqueDecoded.add(sample)
                    } catch (_: Exception) {}
                }

                System.err.println("[SPC] Directory: ${dir.size} entries, ${allDecoded.size} decoded, ${uniqueDecoded.size} unique to song set")
                arrayOf(baseRam, allDecoded, uniqueDecoded, blocks.size)
            }

            if (version != loadVersion) return
            @Suppress("UNCHECKED_CAST")
            val baseRam = result[0] as ByteArray
            @Suppress("UNCHECKED_CAST")
            val allSamples = result[1] as List<DecodedSample>
            @Suppress("UNCHECKED_CAST")
            val uniqueSamples = result[2] as List<DecodedSample>
            val blockCount = result[3] as Int
            if (spcRam == null) spcRam = baseRam
            lastLoadedSongSet = track.songSet
            trackSamples = allSamples
            trackUniqueSamples = uniqueSamples
            statusMessage = "${uniqueSamples.size} unique / ${allSamples.size} total instruments ($blockCount blocks) for ${track.name}"
            buildCompositeWaveform(track, version)
        } catch (e: Exception) {
            if (version == loadVersion) {
                statusMessage = "Error loading track: ${e.message}"
            }
            System.err.println("[SPC] Track load error: ${e.message}")
        } finally {
            if (version == loadVersion) {
                isLoadingTrack = false
            }
        }
    }

    private suspend fun buildCompositeWaveform(track: SpcData.TrackInfo, version: Int) {
        val rate = sampleRate

        val trimmed = withContext(Dispatchers.Default) {
            // Primary: render via N-SPC sequence parser
            val ram = spcRam
            if (ram != null) {
                try {
                    val fullRam = ram.copyOf()
                    val blocks = SpcData.findSongSetTransferData(
                        romParserRef ?: return@withContext ShortArray(0),
                        track.songSet
                    )
                    if (blocks.isNotEmpty()) SpcData.applyTransferBlocks(fullRam, blocks)

                    val rendered = NspcRenderer.renderTrack(fullRam, track.playIndex, rate, 90.0)
                    val renderPeak = if (rendered.isNotEmpty()) rendered.maxOf { abs(it.toInt()) } else 0
                    if (rendered.size > rate / 2 && renderPeak > 200) {
                        System.err.println("[NSPC] Rendered ${track.name}: ${rendered.size} samples (${rendered.size * 1000L / rate}ms), peak=$renderPeak")
                        return@withContext rendered
                    }
                    System.err.println("[NSPC] Render insufficient (${rendered.size} samples, peak=$renderPeak), falling back to instrument preview")
                } catch (e: Exception) {
                    System.err.println("[NSPC] Render failed for ${track.name}: ${e.message}, falling back")
                }
            }

            // Fallback: concatenate instrument samples (use all if unique set is empty)
            val allSrcs = trackSamples.ifEmpty { return@withContext ShortArray(0) }
            val srcs = trackUniqueSamples.ifEmpty { allSrcs }
            val uniqueSamples = srcs
                .distinctBy { it.dirEntry.startAddr }
                .filter { it.pcmData.size >= 100 }
                .ifEmpty {
                    allSrcs.distinctBy { it.dirEntry.startAddr }
                        .filter { it.pcmData.size >= 100 }
                }

            if (uniqueSamples.isEmpty()) return@withContext ShortArray(0)

            val gapSamples = rate / 10
            val segments = mutableListOf<ShortArray>()

            for (s in uniqueSamples) {
                val loopRegionSize = if (s.loopStart in 0 until s.pcmData.size)
                    s.pcmData.size - s.loopStart else 0
                val isLoopable = loopRegionSize > 200

                val segment: ShortArray = if (isLoopable) {
                    extendWithLoop(s.pcmData, s.loopStart, rate, 1.2)
                } else {
                    s.pcmData.copyOf()
                }

                val fadeLen = minOf(segment.size, rate / 5)
                for (i in 0 until fadeLen) {
                    val idx = segment.size - fadeLen + i
                    segment[idx] = (segment[idx] * (fadeLen - i) / fadeLen).toShort()
                }
                segments.add(segment)
                segments.add(ShortArray(gapSamples))
            }

            val totalLen = segments.sumOf { it.size }
            val mixed = ShortArray(totalLen)
            var pos = 0
            for (seg in segments) {
                seg.copyInto(mixed, pos)
                pos += seg.size
            }

            val peak = mixed.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
            if (peak < 200) return@withContext mixed
            val gain = 26000.0 / peak
            ShortArray(mixed.size) { (mixed[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
        }

        if (version != loadVersion) return
        currentWaveform = trimmed
        currentLoopStart = -1
        statusMessage = "${track.name}: ${trimmed.size * 1000L / rate}ms"

        if ((autoPlayEnabled || playAllEnabled) && trimmed.isNotEmpty()) {
            autoPlayEnabled = false
            playTrackPreview()
        }
    }

    companion object {
        fun extendWithLoop(
            pcm: ShortArray,
            loopStart: Int,
            sampleRate: Int,
            targetSeconds: Double
        ): ShortArray {
            val targetLen = (sampleRate * targetSeconds).toInt()
            if (pcm.size >= targetLen) return pcm

            if (loopStart in 0 until pcm.size) {
                val attack = pcm.copyOfRange(0, loopStart)
                val loopRegion = pcm.copyOfRange(loopStart, pcm.size)
                if (loopRegion.isEmpty()) return pcm

                val out = ShortArray(targetLen)
                attack.copyInto(out)
                var pos = attack.size
                while (pos < targetLen) {
                    val remaining = targetLen - pos
                    val copyLen = minOf(loopRegion.size, remaining)
                    loopRegion.copyInto(out, pos, 0, copyLen)
                    pos += copyLen
                }
                val fadeLen = minOf(out.size, (sampleRate * 0.3).toInt())
                for (i in 0 until fadeLen) {
                    val idx = out.size - fadeLen + i
                    out[idx] = (out[idx] * (fadeLen - i) / fadeLen).toShort()
                }
                return out
            }

            val fadeLen = minOf(pcm.size, (sampleRate * 0.2).toInt())
            val out = pcm.copyOf()
            for (i in 0 until fadeLen) {
                val idx = out.size - fadeLen + i
                out[idx] = (out[idx] * (fadeLen - i) / fadeLen).toShort()
            }
            return out
        }

        /**
         * Resample at a different pitch via linear interpolation, then
         * extend with loop to fill targetSeconds.
         */
        fun resamplePitch(
            pcm: ShortArray,
            loopStart: Int,
            pitchFactor: Double,
            sampleRate: Int,
            targetSeconds: Double
        ): ShortArray {
            if (pcm.isEmpty()) return pcm
            val extended = extendWithLoop(pcm, loopStart, sampleRate, targetSeconds * pitchFactor + 0.1)
            val outLen = (extended.size / pitchFactor).toInt().coerceAtMost((sampleRate * targetSeconds).toInt())
            val out = ShortArray(outLen)
            for (i in 0 until outLen) {
                val srcPos = i * pitchFactor
                val idx = srcPos.toInt()
                if (idx + 1 >= extended.size) break
                val frac = (srcPos - idx).toFloat()
                val v = (extended[idx] * (1f - frac) + extended[idx + 1] * frac).toInt()
                out[i] = v.coerceIn(-32768, 32767).toShort()
            }
            return out
        }
    }

    fun playSample(sample: DecodedSample, loop: Boolean = false) {
        if (sample.pcmData.isEmpty()) { statusMessage = "Empty sample"; return }
        val extended = extendWithLoop(sample.pcmData, sample.loopStart, sampleRate, 4.0)
        val pcm = normalizePcm(extended)
        statusMessage = "Playing sample #${sample.dirEntry.index}..."
        player.onComplete = { isPlaying = false; playbackPosition = 0f }
        player.play(pcm, sampleRate, loop)
        isPlaying = true
    }

    fun playTrackPreview(startFraction: Float = 0f) {
        val waveform = currentWaveform
        if (waveform == null || waveform.isEmpty()) {
            statusMessage = "No waveform loaded"
            return
        }
        val startFrame = (startFraction * waveform.size).toLong().coerceIn(0, waveform.size.toLong() - 1)
        val loopStr = if (loopEnabled) " (loop)" else ""
        statusMessage = "Playing ${selectedTrack?.name ?: "preview"}$loopStr..."
        player.onComplete = {
            isPlaying = false
            playbackPosition = 0f
            if (playAllEnabled) advanceToNextTrack()
        }
        player.play(waveform, sampleRate, loop = loopEnabled, startFrame = startFrame)
        isPlaying = true
    }

    fun stopPlayback() {
        player.stop()
        isPlaying = false
        playbackPosition = 0f
        statusMessage = "Stopped"
    }

    fun seekTo(fraction: Float) {
        if (player.isActive()) {
            player.seekFraction(fraction)
        } else {
            playbackPosition = fraction
        }
    }

    fun updatePlaybackPosition() {
        if (player.isActive()) {
            playbackPosition = player.positionFraction()
            isPlaying = true
        } else if (isPlaying) {
            isPlaying = false
        }
    }

    var pendingNextTrack by mutableStateOf(false)
        private set

    fun checkPendingAdvance() {
        if (!pendingNextTrack) return
        pendingNextTrack = false
        val tracks = SpcData.KNOWN_TRACKS
        val currentIdx = tracks.indexOfFirst { it.id == selectedTrackId }
        if (currentIdx >= 0 && currentIdx + 1 < tracks.size) {
            val next = tracks[currentIdx + 1]
            selectTrack(next)
        } else {
            playAllEnabled = false
            statusMessage = "Finished playing all tracks"
        }
    }

    private fun advanceToNextTrack() {
        pendingNextTrack = true
    }

    fun exportCurrentWav() {
        val waveform = currentWaveform ?: return
        val name = selectedTrack?.name?.replace(Regex("[^a-zA-Z0-9_\\- ]"), "")?.trim()
            ?: selectedSample?.let { "sample_${it.dirEntry.index}" }
            ?: "sound"

        val dir = File(System.getProperty("user.home"), "Desktop")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${name}.wav")
        try {
            exportWav(waveform, sampleRate, file)
            statusMessage = "Exported: ${file.absolutePath}"
            System.err.println("[SPC] Exported WAV: ${file.absolutePath} (${waveform.size} samples, ${sampleRate}Hz)")
        } catch (e: Exception) {
            statusMessage = "Export failed: ${e.message}"
            System.err.println("[SPC] WAV export error: ${e.message}")
        }
    }

    private fun normalizePcm(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        val peak = pcm.maxOf { abs(it.toInt()) }
        if (peak < 100 || peak > 20000) return pcm
        val gain = 26000.0 / peak
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
