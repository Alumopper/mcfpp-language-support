# idea-cli-mni

This is the source/configuration template created by `mcfpp init idea-cli-mni --mni`.

Open this directory in IDEA with MCFPP Language 0.5 installed and JDK 25 configured. Wait for synchronization. Java sources, resources and JUnit dependencies are imported from the CLI's final Gradle model.

Run **MCFPP Test** to execute the Java sample test; run **MCFPP Build** to compile the provider and its MNI function call. `main.mcfpp` explicitly declares `func answer(): int = example.mni.Example.answer`; the Java method uses `@MNIFunction` and a result wrapper. Use Ctrl+B on the declaration's Java target to navigate to the provider. **Open Build Output** opens `build/idea-cli-mni`.

The corresponding CLI commands are `mcfpp ide-model --json`, `mcfpp test` and `mcfpp build`. Generated Gradle files are in `.mcfpp/`; they do not need a separate manual Gradle import.
