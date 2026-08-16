# Complete MCFPP project fixture

This project exercises cross-namespace type and function imports, import aliases,
member access, Java-backed MNI declarations, and embedded Minecraft commands.
The `datapack` directory is an IDEA-plugin smoke test for `.mcfunction`,
`pack.mcmeta`, and path-sensitive vanilla datapack JSON support.
Open this directory in VS Code so the MCFPP and Red Hat Java extensions index the
MCFPP source and `ProjectMni.java` together.

Run the end-to-end compiler check with Java 21:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
..\..\..\MCFPP\gradlew.bat -p . check
```
