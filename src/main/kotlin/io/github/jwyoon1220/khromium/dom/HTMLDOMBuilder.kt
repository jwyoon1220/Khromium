package io.github.jwyoon1220.khromium.dom

import io.github.jwyoon1220.khromium.core.VMMAllocator
import io.github.jwyoon1220.khromium.core.VirtualMemoryManager

/**
 * Builds KDOM nodes in VMM from ANTLR HTML Parse Trees.
 */
class HTMLDOMBuilder(
    val allocator: VMMAllocator
) : HTMLParserBaseVisitor<Long>() {

    override fun visitDocument(ctx: HTMLParser.DocumentContext): Long {
        // Technically document is a collection of roots, let's create a root <html> element as a container
        val root = KDOM.createElement(allocator, "document")
        for (elementCtx in ctx.element()) {
            val child = visit(elementCtx)
            if (child != 0L) {
                KDOM.appendChild(allocator.vmm, root, child)
            }
        }
        return root
    }

    override fun visitElementWithContent(ctx: HTMLParser.ElementWithContentContext): Long {
        val tagName = ctx.TAG_IDENTIFIER(0).text
        val el = KDOM.createElement(allocator, tagName)

        // Attributes
        for (attrCtx in ctx.attribute()) {
            val attrName = attrCtx.TAG_IDENTIFIER().text
            var attrVal = ""
            if (attrCtx.ATTR_VALUE() != null) {
                attrVal = attrCtx.ATTR_VALUE().text
                if (attrVal.length >= 2) {
                    attrVal = attrVal.substring(1, attrVal.length - 1) // strip quotes
                }
            }
            KDOM.setAttribute(allocator, el, attrName, attrVal)
        }

        // Content
        for (contentCtx in ctx.content()) {
            val child = visit(contentCtx) ?: 0L
            if (child != 0L) {
                KDOM.appendChild(allocator.vmm, el, child)
            }
        }

        return el
    }

    override fun visitEmptyElement(ctx: HTMLParser.EmptyElementContext): Long {
        val tagName = ctx.TAG_IDENTIFIER().text
        val el = KDOM.createElement(allocator, tagName)

        // Attributes
        for (attrCtx in ctx.attribute()) {
            val attrName = attrCtx.TAG_IDENTIFIER().text
            var attrVal = ""
            if (attrCtx.ATTR_VALUE() != null) {
                attrVal = attrCtx.ATTR_VALUE().text
                if (attrVal.length >= 2) {
                    attrVal = attrVal.substring(1, attrVal.length - 1) // strip quotes
                }
            }
            KDOM.setAttribute(allocator, el, attrName, attrVal)
        }

        return el
    }

    override fun visitContent(ctx: HTMLParser.ContentContext): Long {
        if (ctx.element() != null) {
            return visit(ctx.element()) ?: 0L
        } else if (ctx.TEXT() != null) {
            val text = ctx.TEXT().text
            // Ignore pure whitespace text nodes for cleaner DOM
            if (text.trim().isEmpty()) return 0L
            return KDOM.createTextNode(allocator, text)
        }
        return 0L
    }
}
