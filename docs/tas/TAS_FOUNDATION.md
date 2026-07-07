# TAS Foundation

Frame-accurate tool-assisted-speedrun infrastructure shared by the editor UI,
the headless CLI, and the Python RL/optimization stack. The goal: record,
replay, mutate, and score input movies against **any** ROM — original or
edited/exported by SMEDIT — so human demos, RL policies, hill climbing, and
genetic search all speak one format and one evaluation path.

## Architecture

```
shared/src/jvmMain/.../tas/          Pure TAS engine (no UI)
├── TasInput.kt      12-button env order (B,Y,Sel,Start,U,D,L,R,A,X,L,R) + mnemonics
├── TasMovie.kt      Immutable input movie, JSON (.tasmovie.json), splice/truncate ops
├── Bk2Io.kt         stable-retro .bk2 read/write (Input Log + Header + Core.bin),
│                    gzip-sniffing .state loader
├── SmRam.kt         WRAM map (mirrors custom_integrations/SuperMetroid-Snes/data.json)
│                    + SmSnapshot parsing: Samus, room, IGT, 8 enemy slots
├── TasSession.kt    Headless LibretroCore session: frame step, savestates,
│                    greenzone anchors, seek(frame, movie)
├── TasGoal.kt       Serializable goals (room/position/item/boss/survive) + results
├── TasEvaluator.kt  Movie → TasRunResult (framesToGoal, IGT, transitions, trace)
├── TasMutator.kt    Seeded frame mutations (delete/insert/replace/toggle/revert/
│                    shift/swap spans) + firstChangedFrame seek hints
└── TasOptimizer.kt  Greenzone hill climb: mutate → seek → replay suffix → keep wins

cli/.../TasCli.kt    tas-run / tas-batch / tas-optimize / tas-info / tas-convert
desktopApp/.../ui/TasWorkspaceState.kt + TasCard   Record/playback in the emulator tab
```

Key invariants, verified by `shared/src/jvmTest/.../tas/TasSessionTest.kt`
against the real snes9x core:

- **Determinism**: same start state + same inputs ⇒ byte-identical outcomes.
- **Greenzone**: `seek(frame, movie)` via periodic savestate anchors equals
  linear replay — cheap mid-movie mutation for optimizers and rerecording.
- **Interop**: the Python stack's gzipped `.state` files and `.bk2` recordings
  (including embedded `Core.bin` start states) load directly into the editor's
  snes9x core. Verified with a 7,210-frame human recording replaying through a
  door transition.

## Movie formats

- **`.tasmovie.json`** (native): metadata + one 12-char mnemonic string per
  frame (`"B......r...."` = B+Right). Diffable, trivially generated from Python.
- **`.bk2`**: the stable-retro container used by `platformer_common`
  (`bk2_extract.py`, `record_tasker.py`). Read and written losslessly;
  `tas-convert` moves between the two.

Button order everywhere is env order — identical to
`super_metroid_rl.editor_runtime.BUTTON_ORDER` and stable-retro's SNES buttons.

## CLI usage (the Python-facing surface)

```bash
# Play a movie headlessly against any ROM, score it against a goal
./gradlew -q :cli:runCli -Pargs="--rom /abs/path/rom.smc --compact tas-run \
    --movie /abs/run.tasmovie.json \
    --state /abs/custom_integrations/SuperMetroid-Snes/ZebesStart.state \
    --goal '{\"type\":\"room\",\"roomId\":37629,\"maxFrames\":3600}'"
# → TasRunResult JSON on stdout: achieved, framesToGoal, endIgtFrames,
#   endSnapshot, room transitions, position/health trace

# Inspect / convert movies
... tas-info --movie run.bk2
... tas-convert --movie run.bk2 --out run.tasmovie.json --extract-state start.state

# Evaluate many candidates in ONE core session (the optimizer fan-in path).
# Spec: {"core": "optional/core.so", "jobs": [{"movie", "state", "goal",
# "traceEvery", "stopAtGoal"}, ...]}; --out avoids core log noise on stdout.
... tas-batch --jobs /abs/spec.json --out /abs/results.json

# Improve a VERIFIED run in-process (greenzone hill climb). The seed must
# already achieve the goal; fitness is fewer framesToGoal, then lower IGT.
# Writes best.tasmovie.json / history.jsonl / summary.json under --out.
... tas-optimize --movie /abs/seed.tasmovie.json --state /abs/start.state \
    --goal '{\"type\":\"room\",\"roomId\":37629,\"maxFrames\":3600}' \
    --iterations 500 [--rng-seed N] [--window a:b] --out /abs/opt_dir
```

`tas-optimize` vs the Python `climb_optimizer.py`: the Python tool mutates
externally and pays a full replay per candidate via `tas-batch` — use it for
*discovery* (it has shaped progress fitness for non-achieving movies). The
native optimizer only replays from each mutation's first changed frame
(`TasSession.seek` + greenzone), so late-movie edits cost a fraction of a
replay — use it to *tighten verified runs*. It re-verifies the winner with a
linear replay before reporting, so greenzone bookkeeping can never fabricate
an improvement. `--window a:b` protects frames outside `[a, b)` (e.g. a
verified opening); mutations never touch Select/Start or hold opposing
directions.

For plain-`java` invocation without a gradle build per call (parallel search
workers), dump the classpath once with `:cli:printCliClasspath` and run
`java -cp <cp> com.supermetroid.editor.cli.CliMainKt ...`. Note core
auto-discovery is CWD-relative — pass the core path explicitly.

Notes:
- Paths must be absolute (`runCli` executes with `cli/` as CWD).
- `--core` overrides discovery; default finds `tools/snes9x/libretro/` or `cores/`.
- A `.bk2` with an embedded `Core.bin` starts from it automatically unless
  `--state` is given; a state from an incompatible core build falls back to
  power-on with a warning.
- Goal fields: `type` (`room|position|item|boss|survive`), `roomId`, `x`, `y`,
  `tolerance`, `itemMask`, `maxFrames`, `failOnDeath`.
- For parallel search, launch multiple `tas-run` processes; `TasSession` is
  single-threaded by design.

## Editor workflow

In the Emulator tab, the **TAS Movie** card:
1. Start a session, position via save states, hit **Record** — an anchor state
   `tas_<name>_start` is saved first so the movie is always replayable.
2. Play with keyboard/gamepad; every input frame is captured (turbo-safe).
3. **Stop**, then **Save** as `.tasmovie.json` (or `<name>.bk2`) into
   `editor_recordings/`.
4. **Load** + **Play** replays a movie; playback restores the movie's start
   state when it exists and forces 1× stepping for frame accuracy.

Same movies then run headlessly via `tas-run` against the edited ROM exported
from the editor — that closes the loop: edit level → export ROM → replay/score
existing runs → optimize.

## RAM map

`SmRam` mirrors the stable-retro `data.json` (keep them in sync): Samus
position/velocity/pose/health, room id, game state, door transition, in-game
time (`$09DA`, the speedrun clock), escape timer, 8 enemy slots (x/y/hp,
stride `0x40` — slot 0 is the boss), beam charge, controller mirrors.
`SmSnapshot` is the serializable observation shared by the evaluator, CLI
output, and future models.

## The runs library (`../tas/` in super_metroid_rl)

All historical recordings are promoted into a standardized library one level
up: `super_metroid_rl/tas/runs/` — every run as `.tasmovie.json` with
`meta.startState`, provenance in `index.json`, replay verdicts in
`manifest.json`. See `super_metroid_rl/tas/README.md` for the standard,
`promote_runs.py` (legacy converters), `verify_runs.py` (library-wide replay
verification via `tas-batch`), and `climb_optimizer.py` (mutation search).

## Roadmap hooks (what this foundation is for)

- **Hill climbing / genetic**: `tas-optimize` is the native single-process
  hill climb (greenzone-accelerated, improvement-only). Python stacks
  (`platformer_common.hillclimb` / `genetic`, `tas/climb_optimizer.py`) keep
  owning discovery and population search, fanning out `tas-run`/`tas-batch` —
  same fitness signal (`framesToGoal`, `endIgtFrames`) on original *and
  edited* ROMs. `TasMovie.spliced()` + `TasSession.seek()` +
  `invalidateAfter()` remain available for custom mutation loops;
  `TasMutator` packages the standard operators.
- **Maze-finder models**: `Generate` tab levels → export ROM → `tas-run` with
  `position`/`room` goals gives the reward; nav export (`nav_graph.json`)
  provides waypoints.
- **Combat models**: `boss` goal + enemy slots in `SmSnapshot` (slot 0 HP) is
  the fitness for boss fights; `survive` for damage-avoidance curricula.
- **Model switching / human+AI hybrid**: movies are the splice medium — record
  a human segment, let a model continue from the anchor state, stitch with
  `spliced()`/`appendedAll()`, verify the whole run with `tas-run --no-stop-at-goal`.
- **Timing**: `endIgtFrames` is real-time-attack-comparable in-game time;
  segment deltas come free from `transitions` frame numbers.
