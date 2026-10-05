package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps the English and Polish string resources in lockstep: same keys, and the
 * same format arguments per key. A key missing in one language falls back
 * silently, and a mismatched %1$d / %1$s crashes getString() at runtime - only
 * in that language, so a normal smoke test in the other one never sees it.
 *
 * Gradle runs Android unit tests with the module directory (app/) as the
 * working directory, hence the relative paths.
 */
class StringsParityTest {

    private val formatArg = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")

    private fun load(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("Missing $path (working dir: ${File(".").absolutePath})", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i)
            el.attributes.getNamedItem("name").nodeValue to el.textContent
        }
    }

    private fun args(text: String): List<String> =
        formatArg.findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    @Test
    fun polishHasExactlyTheEnglishKeys() {
        val en = load("src/main/res/values/strings.xml")
        val pl = load("src/main/res/values-pl/strings.xml")
        assertEquals("Keys missing in values-pl", emptySet<String>(), en.keys - pl.keys)
        assertEquals("Keys only in values-pl", emptySet<String>(), pl.keys - en.keys)
    }

    @Test
    fun formatArgumentsMatchPerKey() {
        val en = load("src/main/res/values/strings.xml")
        val pl = load("src/main/res/values-pl/strings.xml")
        val mismatched = en.keys.intersect(pl.keys).filter { args(en.getValue(it)) != args(pl.getValue(it)) }
        assertEquals("Format arguments differ", emptyList<String>(), mismatched)
    }
}
