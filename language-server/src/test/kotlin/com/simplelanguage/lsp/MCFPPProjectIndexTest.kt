package com.simplelanguage.lsp

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MCFPPProjectIndexTest {
    @TempDir
    lateinit var projectRoot: Path

    @Test
    fun `source root files and Java macros share the project namespace`() {
        projectRoot.resolve("mcfpp.toml").writeText("schema=1\nnamespace=\"wizard-mni\"\nsourcePath=\"src/main/mcfpp\"\n")
        val source = projectRoot.resolve("src/main/mcfpp/main.mcfpp")
        source.parent.createDirectories()
        source.writeText("func main() { answer(); }")
        val index = MCFPPProjectIndex()
        index.setWorkspaceRoots(listOf(projectRoot.toUri().toString()))
        val model = index.refresh(emptyMap())
        assertEquals("wizard-mni", model.fileAnalyses.getValue(source.toUri().toString()).namespaceName)
        assertEquals("wizard-mni", index.namespaceForUri(source.toUri().toString()))
        val project = index.discoverProjects(projectRoot).single()
        assertEquals("wizard-mni.x_m_l_models", index.projectNamespace(project, source.parent.resolve("XMLModels/value.mcfpp")))
    }

    @Test
    fun `reads official singular jar key and legacy aliases`() {
        projectRoot.resolve("mcfpp.json").writeText(
            """
                {
                    "namespace": "test",
                    "sourcePath": "src/main/mcfpp/**",
                    "jar": ["libs/official.jar"],
                    "jars": ["libs/alias.jar"],
                    "include": ["includes/one"],
                    "includes": ["includes/two"]
                }
            """.trimIndent()
        )

        val project = MCFPPProjectIndex().discoverProjects(projectRoot).single()

        assertEquals(
            setOf(projectRoot.resolve("libs/official.jar"), projectRoot.resolve("libs/alias.jar")),
            project.jars.toSet()
        )
        assertEquals(
            setOf(projectRoot.resolve("includes/one"), projectRoot.resolve("includes/two")),
            project.includes.toSet()
        )
        assertEquals(
            "test.some_dir",
            MCFPPProjectIndex().projectNamespace(
                project,
                projectRoot.resolve("src/main/mcfpp/SomeDir/example.mcfpp")
            )
        )
    }

    @Test
    fun `recognizes a project configuration containing only include paths`() {
        projectRoot.resolve("mcfpp.json").writeText(
            """
                {
                    "include": "libraries/common"
                }
            """.trimIndent()
        )

        val project = MCFPPProjectIndex().discoverProjects(projectRoot).single()

        assertEquals(projectRoot.resolve("libraries/common"), project.includes.single())
        assertEquals(projectRoot, project.configFile?.parent)
    }

    @Test
    fun `resolved CLI libraries enter and leave the editor index after synchronization`() {
        projectRoot.resolve("mcfpp.toml").writeText(
            "schema=1\nnamespace=\"demo\"\nsourcePath=\"src/main/mcfpp\"\ncompileArgs=[\"-ignoreStdLib\"]\n"
        )
        projectRoot.resolve("src/main/mcfpp").createDirectories()
        val generated = projectRoot.resolve(".mcfpp").createDirectories()
        val archive = generated.resolve("dependency.mclib")
        val resource = javaClass.classLoader.getResourceAsStream("datapack/targets/1.21.8/bin.mclib")
            ?: javaClass.classLoader.getResourceAsStream("datapack/bin.mclib")
        java.nio.file.Files.write(archive, requireNotNull(resource).use { it.readBytes() })
        val modelFile = generated.resolve("ide-model.json")
        modelFile.writeText(com.google.gson.Gson().toJson(mapOf(
            "schema" to 1, "libraries" to listOf(mapOf("path" to archive.toString()))
        )))
        val index = MCFPPProjectIndex()
        index.setWorkspaceRoots(listOf(projectRoot.toUri().toString()))

        assertTrue(index.isRelevantWorkspaceChange(modelFile.toUri().toString()))
        val imported = index.refresh(emptyMap()).librarySymbols.filter { it.name == "print" }
        assertTrue(imported.isNotEmpty())
        assertTrue(imported.all { !it.isStdlib && it.uri == archive.toUri().toString() })

        modelFile.writeText("{\"schema\":1,\"libraries\":[]}")
        assertTrue(index.refresh(emptyMap()).librarySymbols.none { it.name == "print" })
    }

    @Test
    fun `ignores nested datapack json changes but watches project inputs`() {
        val index = MCFPPProjectIndex()
        index.setWorkspaceRoots(listOf(projectRoot.toUri().toString()))

        assertTrue(index.isRelevantWorkspaceChange(projectRoot.resolve("mcfpp.json").toUri().toString()))
        assertTrue(index.isRelevantWorkspaceChange(projectRoot.resolve("src/main.mcfpp").toUri().toString()))
        assertFalse(index.isRelevantWorkspaceChange(projectRoot.resolve("data/test/predicate/example.json").toUri().toString()))
    }

    @Test
    fun `infers namespace from source relative directory`() {
        projectRoot.resolve("mcfpp.json").writeText(
            """
                {
                    "namespace": "root",
                    "sourcePath": "src/**",
                    "compileArgs": ["-ignoreStdLib"]
                }
            """.trimIndent()
        )
        val sourceFile = projectRoot.resolve("src/SomeDir/example.mcfpp")
        sourceFile.parent.createDirectories()
        sourceFile.writeText("func example(){}")
        val index = MCFPPProjectIndex()
        index.setWorkspaceRoots(listOf(projectRoot.toUri().toString()))

        val analysis = index.refresh(emptyMap()).fileAnalyses.getValue(sourceFile.toUri().toString())

        assertEquals("root.some_dir", analysis.namespaceName)
        assertTrue(analysis.topLevelSymbols.all { it.namespaceName == "root.some_dir" })
    }

    @Test
    fun `preserves standard library overloads and their parameter metadata`() {
        projectRoot.resolve("mcfpp.json").writeText(
            """
                {
                    "namespace": "root",
                    "sourcePath": "src/**"
                }
            """.trimIndent()
        )
        projectRoot.resolve("src").createDirectories()
        val index = MCFPPProjectIndex()
        index.setWorkspaceRoots(listOf(projectRoot.toUri().toString()))

        val printOverloads = index.refresh(emptyMap()).librarySymbols.filter { it.name == "print" }

        assertTrue(printOverloads.size > 1, "Standard-library print symbols: $printOverloads")
        assertEquals(
            printOverloads.size,
            printOverloads.map { symbol -> symbol.parameters.map { it.isReadOnly to it.typeName } }.distinct().size
        )
        assertTrue(printOverloads.all { it.parameters.isNotEmpty() })
        assertTrue(printOverloads.all { it.typeName == it.detail && !it.typeName.isNullOrBlank() })

        val typedExternalMembers = index.refresh(emptyMap()).librarySymbols.filter {
            it.containerName != null && it.detail != null &&
                it.kind in setOf(org.eclipse.lsp4j.SymbolKind.Method, org.eclipse.lsp4j.SymbolKind.Field)
        }
        assertTrue(typedExternalMembers.isNotEmpty())
        assertTrue(typedExternalMembers.all { it.typeName == it.detail })
    }

    @Test
    fun `indexes standard library template constructors`() {
        projectRoot.resolve("mcfpp.json").writeText(
            """
                {
                    "namespace": "root",
                    "sourcePath": "src/**"
                }
            """.trimIndent()
        )
        projectRoot.resolve("src").createDirectories()
        val index = MCFPPProjectIndex()
        index.setWorkspaceRoots(listOf(projectRoot.toUri().toString()))

        val symbols = index.refresh(emptyMap()).librarySymbols
        val constructors = symbols.filter { it.kind == org.eclipse.lsp4j.SymbolKind.Constructor }

        assertTrue(constructors.isNotEmpty(), "No constructors were indexed from the standard library")
        assertTrue(constructors.all { it.isExternal && it.containerName == it.name })
    }
}
