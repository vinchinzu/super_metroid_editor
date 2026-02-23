package com.supermetroid.editor.ui

import androidx.compose.foundation.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import javax.sound.sampled.*
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

// ─── Audio playback engine ──────────────────────────────────────────────

class SoundPlayer {
    private var clip: Clip? = null

    fun play(pcmSamples: ShortArray, sampleRate: Int = 32000, loop: Boolean = false) {
        stop()
        if (pcmSamples.isEmpty()) return

        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val byteData = ByteArray(pcmSamples.size * 2)
        for (i in pcmSamples.indices) {
            val s = pcmSamples[i].toInt()
            byteData[i * 2] = (s and 0xFF).toByte()
            byteData[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        try {
            val newClip = AudioSystem.getClip()
            newClip.open(format, byteData, 0, byteData.size)
            if (loop) newClip.loop(Clip.LOOP_CONTINUOUSLY) else newClip.start()
            clip = newClip
        } catch (e: Exception) {
            println("Audio playback error: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stop() {
        clip?.let {
            try { if (it.isRunning) it.stop(); it.close() } catch (_: Exception) {}
        }
        clip = null
    }

    fun isActive(): Boolean = clip?.isRunning == true
}

// ─── Waveform / bar visualization ───────────────────────────────────────

private val GRADIENT_COLORS = arrayOf(
    java.awt.Color(0x6C, 0x5C, 0xE7),  // purple-blue
    java.awt.Color(0x00, 0xB8, 0x94),  // teal
    java.awt.Color(0x00, 0xCC, 0x76),  // green
    java.awt.Color(0xFD, 0xCB, 0x6E),  // gold
    java.awt.Color(0xE1, 0x7E, 0x55),  // orange
    java.awt.Color(0xE0, 0x56, 0x6B),  // red-pink
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
    loopStart: Int = -1
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

    // Compute RMS for each bar for smooth look
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

        // Main bar (RMS level) with gradient color
        val barColor = gradientColor(amplitude)
        g.color = barColor
        g.fillRect(x, midY - rmsH, barWidth, rmsH * 2)

        // Peak whisker (dimmer)
        g.color = java.awt.Color(barColor.red, barColor.green, barColor.blue, 80)
        g.fillRect(x, midY - peakH, barWidth, peakH * 2)
    }

    // Center line (subtle)
    g.color = java.awt.Color(0xFF, 0xFF, 0xFF, 0x18)
    g.drawLine(0, midY, width, midY)

    // Loop marker
    if (loopStart in 0 until samples.size) {
        val loopX = (loopStart.toLong() * width / samples.size).toInt()
        g.color = java.awt.Color(0xFD, 0xCB, 0x6E, 0xCC)
        g.drawLine(loopX, 0, loopX, height)
        g.color = java.awt.Color(0xFD, 0xCB, 0x6E, 0x44)
        g.fillRect(loopX, 0, 3, height)
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
        when (selectedTab) {
            0 -> TrackListContent(romParser, soundEditorState, Modifier.fillMaxSize())
            1 -> SampleListContent(romParser, soundEditorState, Modifier.fillMaxSize())
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
            return@Column
        }
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

@Composable
private fun SampleListContent(
    romParser: RomParser?,
    state: SoundEditorState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(romParser) {
        if (romParser != null && state.samples.isEmpty()) {
            state.loadSamples(romParser)
        }
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        if (state.isLoadingSamples) {
            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                Text("Loading samples...", fontSize = 12.sp)
            }
            return@Column
        }
        if (state.samples.isEmpty()) {
            Text("No samples found.\nLoad a ROM first.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            return@Column
        }
        for (sample in state.samples) {
            val isSel = state.selectedSampleIndex == sample.dirEntry.index
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { state.selectSample(sample) }
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
                            scope.launch { state.playSample(sample) }
                        }, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ─── Right canvas: sound editor view (compact) ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundEditorCanvas(
    romParser: RomParser?,
    @Suppress("UNUSED_PARAMETER") editorState: EditorState,
    soundEditorState: SoundEditorState,
    modifier: Modifier = Modifier
) {
    val state = soundEditorState
    val scope = rememberCoroutineScope()

    // Auto-load track samples when a track is selected
    LaunchedEffect(state.selectedTrackId) {
        if (state.selectedTrack != null && romParser != null) {
            state.loadTrackSamples(romParser)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        // Compact toolbar row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Sound", fontWeight = FontWeight.Bold, fontSize = 12.sp)

            if (state.selectedTrack != null) {
                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                Text(state.selectedTrack!!.name, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(state.selectedTrack!!.area, fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (state.selectedSample != null) {
                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                Text("Sample #${state.selectedSample!!.dirEntry.index}", fontSize = 11.sp,
                    fontWeight = FontWeight.Medium)
                val s = state.selectedSample!!
                Text("${s.pcmData.size} pcm" + if (s.loopStart >= 0) " loop" else "",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(1f))

            if (state.statusMessage.isNotEmpty()) {
                Text(state.statusMessage, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.height(4.dp))

        if (state.selectedTrack != null || state.selectedSample != null) {
            // Playback controls row (compact)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            if (state.selectedSample != null) state.playSample(state.selectedSample!!)
                            else state.playTrackPreview()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(28.dp),
                    enabled = (state.selectedSample != null || state.currentWaveform != null) &&
                        !state.isLoadingTrack
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Play", fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = { state.stopPlayback() },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.Stop, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Stop", fontSize = 10.sp)
                }
                if (state.selectedSample != null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch { state.selectedSample?.let { state.playSample(it, loop = true) } }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Loop, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Loop", fontSize = 10.sp)
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

            // Waveform display (fills available space)
            val waveformData = state.currentWaveform
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0C0C18), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF2A2A3A), RoundedCornerShape(6.dp))
            ) {
                if (waveformData != null && waveformData.isNotEmpty()) {
                    val waveImg = remember(waveformData, state.currentLoopStart) {
                        renderWaveformBars(waveformData, 900, 240, state.currentLoopStart)
                            .toComposeImageBitmap()
                    }
                    Image(bitmap = waveImg, contentDescription = "Waveform",
                        contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())

                    // Stats overlay at bottom
                    val durationMs = waveformData.size * 1000L / state.sampleRate
                    val peak = waveformData.maxOf { abs(it.toInt()) }
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("${durationMs}ms", fontSize = 9.sp, color = Color(0xFFAABBCC))
                        Text("${waveformData.size} samples", fontSize = 9.sp, color = Color(0xFFAABBCC))
                        Text("${state.sampleRate}Hz", fontSize = 9.sp, color = Color(0xFFAABBCC))
                        Text("peak: $peak", fontSize = 9.sp, color = Color(0xFFAABBCC))
                        if (state.currentLoopStart >= 0) {
                            Spacer(Modifier.weight(1f))
                            Text("loop @ ${state.currentLoopStart}", fontSize = 9.sp,
                                color = Color(0xFFFDCB6E))
                        }
                    }
                } else if (state.isLoadingTrack) {
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

            // Detail card (collapsed by default for tracks)
            if (state.selectedTrack != null) {
                val t = state.selectedTrack!!
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("Song Set: 0x${t.songSet.toString(16).uppercase().padStart(2, '0')}",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Play Index: 0x${t.playIndex.toString(16).uppercase().padStart(2, '0')}",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Area: ${t.area}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (state.selectedSample != null) {
                val s = state.selectedSample!!
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("SPC: 0x${s.dirEntry.startAddr.toString(16).uppercase().padStart(4, '0')}",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.loopStart >= 0) {
                        Text("Loop: 0x${s.dirEntry.loopAddr.toString(16).uppercase().padStart(4, '0')}",
                            fontSize = 9.sp, color = Color(0xFFFDCB6E))
                    }
                }
            }
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

    private var spcRam: ByteArray? = null
    private var lastLoadedSongSet = -1

    data class DecodedSample(
        val dirEntry: SpcData.SampleDirEntry,
        val pcmData: ShortArray,
        val loopStart: Int
    )

    fun selectTrack(track: SpcData.TrackInfo) {
        val clearSamples = track.songSet != lastLoadedSongSet
        Snapshot.withMutableSnapshot {
            selectedTrackId = track.id
            selectedTrack = track
            selectedSample = null
            selectedSampleIndex = -1
            statusMessage = "Track: ${track.name}"
            if (clearSamples) {
                trackSamples = emptyList()
                currentWaveform = null
                currentLoopStart = -1
            }
        }
    }

    fun selectSample(sample: DecodedSample) {
        val extended = extendWithLoop(sample.pcmData, sample.loopStart, sampleRate, 4.0)
        Snapshot.withMutableSnapshot {
            selectedSampleIndex = sample.dirEntry.index
            selectedSample = sample
            selectedTrack = null
            selectedTrackId = -1
            currentWaveform = extended
            currentLoopStart = sample.loopStart
            statusMessage = "Sample #${sample.dirEntry.index}: ${sample.pcmData.size} pcm" +
                if (sample.loopStart >= 0) " (extended to ${extended.size})" else ""
        }
    }

    suspend fun loadSamples(romParser: RomParser) {
        Snapshot.withMutableSnapshot {
            isLoadingSamples = true
            statusMessage = "Loading SPC data..."
        }
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
            Snapshot.withMutableSnapshot {
                samples = result.second
                statusMessage = "Loaded ${result.second.size} BRR samples"
            }
        } catch (e: Exception) {
            Snapshot.withMutableSnapshot {
                statusMessage = "Error: ${e.message}"
            }
            System.err.println("[SPC] Load failed: ${e.message}")
            e.printStackTrace()
        } finally {
            Snapshot.withMutableSnapshot {
                isLoadingSamples = false
            }
        }
    }

    /**
     * Load track's song set samples and build a composite waveform immediately.
     * Called from LaunchedEffect when a track is selected.
     * Suspend function: heavy work on Dispatchers.Default, state updates on caller (main).
     */
    suspend fun loadTrackSamples(romParser: RomParser) {
        val track = selectedTrack ?: return
        if (track.songSet == lastLoadedSongSet && trackSamples.isNotEmpty()) {
            buildCompositeWaveform(track)
            return
        }
        Snapshot.withMutableSnapshot {
            isLoadingTrack = true
            statusMessage = "Loading instruments for ${track.name}..."
        }

        try {
            val result = withContext(Dispatchers.Default) {
                val baseRam = spcRam ?: SpcData.buildInitialSpcRam(romParser)
                val ram = baseRam.copyOf()

                val blocks = SpcData.findSongSetTransferData(romParser, track.songSet)
                System.err.println("[SPC] Track '${track.name}' songSet=0x${track.songSet.toString(16)}: ${blocks.size} transfer blocks")
                if (blocks.isNotEmpty()) {
                    SpcData.applyTransferBlocks(ram, blocks)
                }
                val dir = SpcData.findSampleDirectory(ram)
                System.err.println("[SPC] Directory after song set load: ${dir.size} entries")

                val decoded = dir.mapNotNull { entry ->
                    try {
                        val (pcm, loop) = SpcData.decodeBrrWithLoop(ram, entry)
                        if (pcm.size > 16 && pcm.any { it.toInt() != 0 }) DecodedSample(entry, pcm, loop)
                        else null
                    } catch (_: Exception) { null }
                }
                Triple(baseRam, decoded, blocks.size)
            }

            if (spcRam == null) spcRam = result.first
            lastLoadedSongSet = track.songSet
            Snapshot.withMutableSnapshot {
                trackSamples = result.second
                statusMessage = "${result.second.size} instruments (${result.third} blocks) for ${track.name}"
            }
            buildCompositeWaveform(track)
        } catch (e: Exception) {
            Snapshot.withMutableSnapshot {
                statusMessage = "Error loading track: ${e.message}"
            }
            System.err.println("[SPC] Track load error: ${e.message}")
            e.printStackTrace()
        } finally {
            Snapshot.withMutableSnapshot {
                isLoadingTrack = false
            }
        }
    }

    /**
     * Build a composite preview waveform from all track samples.
     * Extends each sample with its loop, staggers them, and mixes into one buffer.
     */
    private fun buildCompositeWaveform(track: SpcData.TrackInfo) {
        val srcs = trackSamples.ifEmpty { return }
        val targetLen = sampleRate * 5 // 5 seconds
        val mixed = IntArray(targetLen)
        val gap = if (srcs.size > 1) targetLen / (srcs.size + 1) else 0

        for ((i, s) in srcs.withIndex()) {
            val extended = extendWithLoop(s.pcmData, s.loopStart, sampleRate, 3.0)
            val fadeLen = minOf(extended.size, sampleRate / 2)
            val offset = if (srcs.size > 1) i * gap else 0
            for (j in extended.indices) {
                val destIdx = offset + j
                if (destIdx >= targetLen) break
                var v = extended[j].toInt()
                val distFromEnd = extended.size - j
                if (distFromEnd < fadeLen) v = (v * distFromEnd / fadeLen)
                mixed[destIdx] += v
            }
        }

        val peak = mixed.maxOf { abs(it) }.coerceAtLeast(1)
        val gain = if (peak > 32000) 28000.0 / peak else 1.0
        val pcm = ShortArray(targetLen) { (mixed[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }

        // Trim trailing silence
        var end = pcm.size - 1
        while (end > 0 && abs(pcm[end].toInt()) < 50) end--
        val trimmed = if (end < pcm.size - 100) pcm.copyOfRange(0, end + 1) else pcm

        Snapshot.withMutableSnapshot {
            currentWaveform = trimmed
            currentLoopStart = -1
            statusMessage = "${track.name}: ${srcs.size} instruments, ${trimmed.size * 1000L / sampleRate}ms"
        }
    }

    fun playSample(sample: DecodedSample, loop: Boolean = false) {
        if (sample.pcmData.isEmpty()) { statusMessage = "Empty sample"; return }
        val extended = extendWithLoop(sample.pcmData, sample.loopStart, sampleRate, 4.0)
        val pcm = normalizePcm(extended)
        statusMessage = "Playing sample #${sample.dirEntry.index}..."
        player.play(pcm, sampleRate, loop)
    }

    fun playTrackPreview() {
        val waveform = currentWaveform
        if (waveform == null || waveform.isEmpty()) {
            statusMessage = "No waveform loaded"
            return
        }
        val pcm = normalizePcm(waveform)
        statusMessage = "Playing ${selectedTrack?.name ?: "preview"}..."
        player.play(pcm, sampleRate)
    }

    fun stopPlayback() {
        player.stop()
        statusMessage = "Stopped"
    }

    private fun normalizePcm(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        val peak = pcm.maxOf { abs(it.toInt()) }
        if (peak < 100 || peak > 20000) return pcm
        val gain = 26000.0 / peak
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    companion object {
        /**
         * Extend a short BRR sample by repeating its loop region until it fills
         * [targetSeconds] of audio at [sampleRate]. Non-looping samples get a
         * fade-out to avoid abrupt endings.
         */
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
                // Fade out the last 0.3s
                val fadeLen = minOf(out.size, (sampleRate * 0.3).toInt())
                for (i in 0 until fadeLen) {
                    val idx = out.size - fadeLen + i
                    out[idx] = (out[idx] * (fadeLen - i) / fadeLen).toShort()
                }
                return out
            }

            // No loop: fade out
            val fadeLen = minOf(pcm.size, (sampleRate * 0.2).toInt())
            val out = pcm.copyOf()
            for (i in 0 until fadeLen) {
                val idx = out.size - fadeLen + i
                out[idx] = (out[idx] * (fadeLen - i) / fadeLen).toShort()
            }
            return out
        }
    }
}
