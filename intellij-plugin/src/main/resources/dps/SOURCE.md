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
./gradlew :cli:fatJar
./gradlew :cli:test --tests moe.afox.dpsandbox.cli.LiveDebugSessionTest --tests moe.afox.dpsandbox.cli.ServeCommandTest
./gradlew :core:test --tests moe.afox.dpsandbox.core.VersionProfileTest
```

The output is `cli/build/libs/datapack-sandbox-cli.jar`. From this plugin project,
`updateDatapackSandboxService -PdatapackSandboxCliJar=<output>` updates the resource.
Update `VERSION.txt` whenever changing the patch or binary. The fat JAR normalizes
entry order and timestamps. Build dependencies and JDK distribution also influence
artifact bytes; the recorded hash identifies the exact shipped JAR.
