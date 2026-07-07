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
└── TasEvaluator.kt  Movie → TasRunResult (framesToGoal, IGT, transitions, trace)

cli/.../TasCli.kt    tas-run / tas-info / tas-convert (JSON on stdout)
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
```

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

## Roadmap hooks (what this foundation is for)

- **Hill climbing / genetic**: `platformer_common.hillclimb` / `genetic`
  currently drive stable-retro directly. They can now also emit
  `.tasmovie.json` candidates and fan out `tas-run` processes — same fitness
  signal (`framesToGoal`, `endIgtFrames`) on original *and edited* ROMs.
  `TasMovie.spliced()` + `TasSession.seek()` are the segment-mutation
  primitives (invalidate greenzone after the edit point via `invalidateAfter`).
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
