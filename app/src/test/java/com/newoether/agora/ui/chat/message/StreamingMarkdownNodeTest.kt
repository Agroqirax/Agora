package com.newoether.agora.ui.chat.message

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownNodeTest {
    private val flavour = GFMFlavourDescriptor()

    @Test
    fun growingTailKeepsTheSameBlockIdentity() {
        val original = firstRenderableNode("- Aion S\n- Aion Y")
        val updated = firstRenderableNode("- Aion S\n- Aion Y\n- Aion V")

        assertTrue(original.hasSameBlockIdentity(updated))
    }

    @Test
    fun promotedTailAndFollowingParagraphHaveDifferentBlockIdentities() {
        val list = firstRenderableNode("- Aion S\n- Aion Y")
        val paragraph = renderableNodes("- Aion S\n- Aion Y\n\nNext paragraph").last()

        assertFalse(list.hasSameBlockIdentity(paragraph))
    }

    private fun firstRenderableNode(markdown: String): StreamingMarkdownNode =
        renderableNodes(markdown).first()

    private fun renderableNodes(markdown: String): List<StreamingMarkdownNode> =
        MarkdownParser(flavour)
            .buildMarkdownTreeFromString(markdown)
            .children
            .filter { node -> markdown.substring(node.startOffset, node.endOffset).isNotBlank() }
            .map { node -> node.toStreamingNode(markdown) }

    private fun ASTNode.toStreamingNode(markdown: String): StreamingMarkdownNode =
        StreamingMarkdownNode(
            startOffset = startOffset,
            endOffset = endOffset,
            contentHash = markdown.substring(startOffset, endOffset).hashCode(),
            node = this,
            sourceContent = markdown,
        )
}
