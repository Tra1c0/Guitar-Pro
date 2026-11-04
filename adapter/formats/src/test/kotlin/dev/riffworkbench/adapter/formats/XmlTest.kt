package dev.riffworkbench.adapter.formats

import dev.riffworkbench.adapter.formats.xml.*
import org.junit.Assert.*
import org.junit.Test

class XmlTest {
    @Test fun parsesElementsAttributesTextCdataAndReferences() {
        val root = XmlReader.parse("""<?xml version="1.0"?><!-- c --><!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD" "http://x/partwise.dtd">
            <GPIF version="7"><Score><Title><![CDATA[Riff <A>]]></Title><Artist>A &amp; B &#x263A; &#65;</Artist></Score><Empty a='1' b="x&lt;y"/></GPIF>""")
        assertEquals("GPIF", root.name)
        assertEquals("Riff <A>", root.textOf("Score", "Title"))
        assertEquals("A & B ☺ A", root.textOf("Score", "Artist"))
        assertEquals("x<y", root.child("Empty")!!.attr("b"))
    }

    @Test fun rejectsEntityExpansionAndInternalSubsets() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">]><lolz>&lol2;</lolz>"""
        assertTrue(runCatching { XmlReader.parse(bomb) }.exceptionOrNull() is XmlException)
        assertTrue(runCatching { XmlReader.parse("<a>&xxe;</a>") }.exceptionOrNull() is XmlException)
        assertTrue(runCatching { XmlReader.parse("<a><b></a>") }.exceptionOrNull() is XmlException)
        assertTrue(runCatching { XmlReader.parse("<a/><b/>") }.exceptionOrNull() is XmlException)
    }

    @Test fun rejectsExcessiveDepth() {
        val deep = "<a>".repeat(XmlReader.MAX_DEPTH + 1) + "</a>".repeat(XmlReader.MAX_DEPTH + 1)
        assertTrue(runCatching { XmlReader.parse(deep) }.exceptionOrNull()!!.message!!.contains("tief"))
    }

    @Test fun writerEscapesAndRoundTrips() {
        val xml = XmlWriter().declaration().start("root", "title" to "a\"b<c>").leaf("Text", "x & y < z").start("Empty").end().end().build()
        val root = XmlReader.parse(xml)
        assertEquals("a\"b<c>", root.attr("title"))
        assertEquals("x & y < z", root.textOf("Text"))
        assertNotNull(root.child("Empty"))
    }
}
