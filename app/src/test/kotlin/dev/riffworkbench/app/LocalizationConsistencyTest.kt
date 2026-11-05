package dev.riffworkbench.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA cycle 12 (localisation): plain JVM checks over the source tree, no Robolectric.
 *
 * German is the default locale (`values/`), English the second one (`values-en/`, `tr(de, en)`, `english()`).
 * The first two tests guard resource parity and pass today; the third one fails: user-visible messages built in
 * view models / composition roots are German literals and reach an English UI unchanged.
 */
class LocalizationConsistencyTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun resourceDirs(): List<Pair<File, File>> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != "third_party" && !it.name.startsWith(".") }
        .filter { it.isFile && it.name == "strings.xml" && it.parentFile.name == "values" }
        .map { it to File(it.parentFile.parentFile, "values-en/strings.xml") }.toList()

    private fun strings(file: File): Map<String, String> =
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(value: String): List<String> =
        Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]""").findAll(value).map { it.value }.filter { it != "%%" }.sorted().toList()

    @Test fun everyDefaultStringHasAnEnglishCounterpartAndViceVersa() {
        val dirs = resourceDirs()
        assertTrue("no values/strings.xml found under $root", dirs.isNotEmpty())
        dirs.forEach { (de, en) ->
            assertTrue("missing ${en.relativeTo(root)}", en.isFile)
            val d = strings(de).keys; val e = strings(en).keys
            assertEquals("only in ${de.relativeTo(root)}", emptySet<String>(), d - e)
            assertEquals("only in ${en.relativeTo(root)}", emptySet<String>(), e - d)
        }
    }

    @Test fun placeholdersAgreeBetweenLocales() {
        resourceDirs().forEach { (de, en) ->
            val d = strings(de); val e = strings(en)
            val mismatches = d.keys.intersect(e.keys).filter { placeholders(d.getValue(it)) != placeholders(e.getValue(it)) }
            assertEquals("placeholder mismatch in ${de.parentFile.parentFile.relativeTo(root)}", emptyList<String>(), mismatches)
        }
    }
}
