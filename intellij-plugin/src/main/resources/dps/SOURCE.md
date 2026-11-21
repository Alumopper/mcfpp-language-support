# Rebuild the bundled debug runtime

`VERSION.txt` records the upstream baseline and SHA-256 hashes of the source patch
and bundled JAR. The patch includes the recovered 26.3 source changes, live JSONL
debugging, protocol documentation and focused tests.

With Git and JDK 25 installed:

```sh
git clone https://github.com/Alumopper/DatapackSandbox
cd DatapackSandbox
git checkout cc9ae9634813b0087be0d9d41384f679ec240ca5
git apply /path/to/mcfpp-live-debug.patch
git apply /path/to/mcfpp-render-nbt.patch
git apply /path/to/mcfpp-render-ui.patch
git apply /path/to/mcfpp-render-performance.patch
git apply /path/to/mcfpp-quoted-nbt.patch
git apply /path/to/mcfpp-examples-runtime.patch
./gradlew :cli:fatJar
./gradlew :cli:test --tests moe.afox.dpsandbox.cli.LiveDebugSessionTest --tests moe.afox.dpsandbox.cli.ServeCommandTest
./gradlew :core:test --tests moe.afox.dpsandbox.core.VersionProfileTest
./gradlew :core:test --tests moe.afox.dpsandbox.core.JsonValuesTest
./gradlew :core:test --tests moe.afox.dpsandbox.core.QuotedNbtPathTest
./gradlew :cli:test --tests moe.afox.dpsandbox.cli.MinecraftClientResourcesTest
./gradlew :cli:test --tests moe.afox.dpsandbox.cli.JvmViewportControllerTest
./gradlew :core:test --tests moe.afox.dpsandbox.core.ExecuteCompositionTest --tests moe.afox.dpsandbox.core.CommandTraceOutputTest
./gradlew :cli:test --tests moe.afox.dpsandbox.cli.CheckCommandTest --tests moe.afox.dpsandbox.cli.RenderCommandTest
```

The output is `cli/build/libs/datapack-sandbox-cli.jar`. From this plugin project,
`updateDatapackSandboxService -PdatapackSandboxCliJar=<output>` updates the resource.
Update `VERSION.txt` whenever changing the patch or binary. The fat JAR normalizes
entry order and timestamps. Build dependencies and JDK distribution also influence
artifact bytes; the recorded hash identifies the exact shipped JAR.

The render patch adds named compound NBT path filters used by scheduler dispatch,
scientific SNBT float literals emitted by display setters, and Minecraft string /
collection `data get` results used to validate runtime UUID references. It also
implements scoreboard score swaps (`><`) used by compiled arithmetic in display
animations.

The UI patch uses measured fallback glyph advances, an oversampled font atlas and
smooth filtering. HUD scaling also respects fractional sizes so toolbar labels fit
the available width while remaining readable without client assets.

The performance patch forwards and drains pending command outputs before scene
compilation, keeping per-command output out of the renderer's world snapshots.
Viewport lifecycle and action messages are also sent to stdout for the IDE's DSB console.

The quoted NBT patch keeps a quoted first field and its suffix in one command
argument, preserves raw path spelling at command boundaries, and decodes quoted
NBT keys. This supports optimized compiler frames such as `"qwq:bounce_float".t`.

The examples runtime patch implements execute summon context, terminal conditions
and chained store callbacks, and exposes entity UUID NBT as four signed integers.
Trace diffs exclude historical outputs and nested commands' output payloads. CLI
checks and screenshots collect full traces only when requested or required by
manifest trace assertions/artifact steps, so ordinary compiled entity and scheduler
examples do not exceed snapshot limits through duplicated diagnostic history.
