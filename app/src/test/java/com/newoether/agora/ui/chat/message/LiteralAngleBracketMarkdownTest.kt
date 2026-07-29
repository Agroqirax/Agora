package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteralAngleBracketMarkdownTest {
    @Test
    fun ordinaryHtmlLookingTagsAreProtectedFromMarkdownHtmlParsing() {
        val protected = "<widget id=\"x\">value</widget> <T>".protectLiteralAngleBracketTags()

        assertEquals(
            "<\u200Bwidget id=\"x\">value</\u200Bwidget> <\u200BT>",
            protected,
        )
    }

    @Test
    fun inlineAndFencedCodeRemainByteForByteUnchanged() {
        val source = "`<widget>`\n```\n<tag>inside</tag>\n```"

        assertEquals(source, source.protectLiteralAngleBracketTags())
    }

    @Test
    fun commonMarkAutolinksAreNotBroken() {
        val source = "<https://example.com> <person@example.com>"
        val protected = source.protectLiteralAngleBracketTags()

        assertEquals(source, protected)
        assertFalse(protected.contains('\u200B'))
    }

    @Test
    fun multilineTagsAreProtectedAndIncompleteTagsStayLiteral() {
        val source = "<widget\nattr=x> and <unfinished"
        val protected = source.protectLiteralAngleBracketTags()

        assertEquals("<\u200Bwidget\nattr=x> and <unfinished", protected)
        assertTrue(source.escapeForMarkdown().contains("<unfinished"))
    }
}
