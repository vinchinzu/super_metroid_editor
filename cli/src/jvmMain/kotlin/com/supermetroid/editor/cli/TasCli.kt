package com.supermetroid.editor.cli

import com.supermetroid.editor.libretro.LibretroCoreDiscovery
import com.supermetroid.editor.tas.Bk2Io
import com.supermetroid.editor.tas.TasEvaluator
import com.supermetroid.editor.tas.TasGoal
import com.supermetroid.editor.tas.TasMovie
import com.supermetroid.editor.tas.TasSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * Headless TAS commands: run movies against original or edited ROMs and emit
 * JSON results for the Python optimization stack (hill climbing, genetic
 * search, model evaluation).
 */
object TasCli {

    private val json = Json { ignoreUnknownKeys = true }

    fun run(command: String, romPath: String?, args: List<String>, pretty: Boolean) {
        val out = Json { prettyPrint = pretty; ignoreUnknownKeys = true }
        when (command) {
            "tas-info" -> cmdInfo(args, out)
            "tas-convert" -> cmdConvert(args)
            "tas-run" -> cmdRun(romPath, args, out)
            "tas-batch" -> cmdBatch(romPath, args, out)
            else -> {
                System.err.println("Unknown TAS command: $command")
                exitProcess(1)
            }
        }
    }

    private fun opt(args: List<String>, name: String): String? {
        val idx = args.indexOf(name)
        return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
    }

    private fun loadMovie(path: String): Pair<TasMovie, ByteArray?> {
        val file = File(path)
        if (!file.isFile) {
            System.err.println("Movie not found: $path")
            exitProcess(1)
        }
        return if (file.extension.equals("bk2", ignoreCase = true)) {
            val archive = Bk2Io.read(file)
            archive.movie to archive.coreState
        } else {
            TasMovie.load(file) to null
        }
    }

    @Serializable
    private data class MovieInfo(
        val path: String,
        val container: String,
        val frames: Int,
        val durationSeconds: Double,
        val hasEmbeddedState: Boolean,
        val meta: com.supermetroid.editor.tas.TasMovieMeta,
    )

    private fun cmdInfo(args: List<String>, out: Json) {
        val path = opt(args, "--movie") ?: args.firstOrNull() ?: run {
            System.err.println("Usage: tas-info --movie <file.tasmovie.json|file.bk2>")
            exitProcess(1)
        }
        val (movie, coreState) = loadMovie(path)
        val info = MovieInfo(
            path = path,
            container = if (path.endsWith(".bk2", true)) "bk2" else "json",
            frames = movie.frameCount,
            durationSeconds = movie.frameCount / 60.0,
            hasEmbeddedState = coreState != null,
            meta = movie.meta,
        )
        println(out.encodeToString(info))
    }

    private fun cmdConvert(args: List<String>) {
        val inPath = opt(args, "--movie")
        val outPath = opt(args, "--out")
        if (inPath == null || outPath == null) {
            System.err.println("Usage: tas-convert --movie <in> --out <out>  (format from extension: .bk2 or .json)")
            System.err.println("       [--extract-state <file>]  dump a bk2's embedded Core.bin start state")
            exitProcess(1)
        }
        val (movie, coreState) = loadMovie(inPath)
        opt(args, "--extract-state")?.let { statePath ->
            if (coreState == null) {
                System.err.println("No embedded state in $inPath")
            } else {
                File(statePath).writeBytes(coreState)
                System.err.println("Wrote embedded state to $statePath (${coreState.size} bytes)")
            }
        }
        if (outPath.endsWith(".bk2", true)) {
            Bk2Io.write(File(outPath), movie, coreState)
        } else {
            movie.save(File(outPath), pretty = true)
        }
        System.err.println("Wrote $outPath (${movie.frameCount} frames)")
    }

    private fun cmdRun(romPath: String?, args: List<String>, out: Json) {
        if (romPath == null) {
            System.err.println("tas-run requires --rom <path>")
            exitProcess(1)
        }
        val moviePath = opt(args, "--movie") ?: run {
            System.err.println(
                "Usage: --rom <rom> tas-run --movie <file> [--state <file.state>] [--core <core.so>]\n" +
                    "       [--goal <goal.json|inline-json>] [--trace-every N] [--no-stop-at-goal] [--anchor-interval N]"
            )
            exitProcess(1)
        }
        val corePath = LibretroCoreDiscovery.findCore(opt(args, "--core")) ?: run {
            System.err.println("No SNES libretro core found; pass --core <path to snes9x_libretro.so>")
            exitProcess(1)
        }
        val (movie, embeddedState) = loadMovie(moviePath)
        val goal = opt(args, "--goal")?.let { spec ->
            val file = File(spec)
            json.decodeFromString<TasGoal>(if (file.isFile) file.readText() else spec)
        }
        val traceEvery = opt(args, "--trace-every")?.toIntOrNull() ?: 30
        val anchorInterval = opt(args, "--anchor-interval")?.toIntOrNull() ?: 600
        val stopAtGoal = "--no-stop-at-goal" !in args

        TasSession(corePath, romPath, anchorInterval = anchorInterval).use { session ->
            val statePath = opt(args, "--state")
            when {
                statePath != null -> session.loadStateFile(File(statePath))
                embeddedState != null -> {
                    val loaded = runCatching { session.loadStateBytes(embeddedState) }
                    if (loaded.isFailure) {
                        System.err.println(
                            "Warning: embedded bk2 state is from a different core build; starting from power-on"
                        )
                    }
                }
            }
            val result = TasEvaluator.run(
                session = session,
                movie = movie,
                goal = goal,
                traceEvery = traceEvery,
                stopAtGoal = stopAtGoal,
            )
            println(out.encodeToString(result))
        }
    }

    @Serializable
    private data class BatchJob(
        val movie: String,
        /** Start state file; null starts from power-on. */
        val state: String? = null,
        val goal: TasGoal? = null,
        val traceEvery: Int = 0,
        val stopAtGoal: Boolean = true,
    )

    @Serializable
    private data class BatchSpec(
        val core: String? = null,
        val jobs: List<BatchJob> = emptyList(),
    )

    @Serializable
    private data class BatchEntry(
        val movie: String,
        val error: String? = null,
        val result: com.supermetroid.editor.tas.TasRunResult? = null,
    )

    /**
     * Evaluate many movies in one process/core session — the fan-in path for
     * Python optimizers, ~one JVM+core load per hundreds of candidates instead
     * of per candidate. Reads a job spec JSON, emits a result array.
     */
    private fun cmdBatch(romPath: String?, args: List<String>, out: Json) {
        if (romPath == null) {
            System.err.println("tas-batch requires --rom <path>")
            exitProcess(1)
        }
        val specPath = opt(args, "--jobs") ?: run {
            System.err.println(
                "Usage: --rom <rom> tas-batch --jobs <spec.json> [--out <results.json>]\n" +
                    "  spec: {\"core\": \"optional/core.so\", \"jobs\": [{\"movie\": ..., " +
                    "\"state\": ..., \"goal\": {...}, \"traceEvery\": 0, \"stopAtGoal\": true}]}"
            )
            exitProcess(1)
        }
        val spec = json.decodeFromString<BatchSpec>(File(specPath).readText())
        val corePath = LibretroCoreDiscovery.findCore(spec.core ?: opt(args, "--core")) ?: run {
            System.err.println("No SNES libretro core found; set \"core\" in the spec or pass --core")
            exitProcess(1)
        }
        // Linear evaluation never seeks backwards, so greenzone anchors are pure overhead.
        TasSession(corePath, romPath, anchorInterval = 0).use { session ->
            val stateCache = HashMap<String, ByteArray>()
            val entries = spec.jobs.map { job ->
                runCatching {
                    require(File(job.movie).isFile) { "Movie not found: ${job.movie}" }
                    val (movie, embeddedState) = loadMovie(job.movie)
                    when {
                        job.state != null -> session.loadStateBytes(
                            stateCache.getOrPut(job.state) { Bk2Io.loadStateFile(File(job.state)) }
                        )
                        embeddedState != null -> session.loadStateBytes(embeddedState)
                        else -> session.reset()
                    }
                    TasEvaluator.run(
                        session = session,
                        movie = movie,
                        goal = job.goal,
                        traceEvery = job.traceEvery,
                        stopAtGoal = job.stopAtGoal,
                    )
                }.fold(
                    onSuccess = { BatchEntry(movie = job.movie, result = it) },
                    onFailure = { BatchEntry(movie = job.movie, error = it.message ?: it.toString()) },
                )
            }
            val encoded = out.encodeToString(entries)
            // Emulator cores may log to stdout, so support writing results to a
            // file the caller can parse cleanly.
            val outPath = opt(args, "--out")
            if (outPath != null) File(outPath).writeText(encoded) else println(encoded)
        }
    }
}
