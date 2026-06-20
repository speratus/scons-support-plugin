package space.luchuktech.sconssupport

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.luchuktech.sconssupport.compiledb.CompileCommandsGenerator
import java.io.File

class CompileCommandsGeneratorTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun testGenerateCompileCommands() {
        val root = tempFolder.newFolder("project").toPath()
        File(root.toFile(), "main.c").createNewFile()
        
        val dryRunOutput = """
gcc -o hello.o -c main.c -Iinclude -O2
g++ -o app main.o -lfoo
        """.trimIndent()
        
        CompileCommandsGenerator.generate(root, dryRunOutput)
        
        val outFile = File(root.toFile(), "compile_commands.json")
        assertTrue("compile_commands.json should exist", outFile.exists())
        
        val content = outFile.readText()
        assertTrue("Should contain gcc command", content.contains("gcc -o hello.o -c main.c -Iinclude -O2"))
        assertTrue("Should contain main.c file", content.contains("main.c"))
        assertTrue("Should NOT contain g++ link command", !content.contains("g++ -o app main.o -lfoo"))
    }
}
