package io.github.jwyoon1220.khromium.dom

import io.github.jwyoon1220.khromium.core.VMMAllocator
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Builds a KDOM tree in VMM from raw HTML using Jsoup as the parser.
 *
 * Jsoup handles the full HTML5 parsing surface (DOCTYPE, comments, malformed markup,
 * implied tags, etc.).  After parsing, this builder walks the Jsoup DOM and writes
 * KDOM element/text nodes into the tab's [VMMAllocator] so every byte of DOM data
 * lives off-heap in the virtual address space — not on the JVM heap.
 *
 * The resulting KDOM pointer is compatible with all existing consumers:
 * [KDOM], [DomBridge], [KhromiumRenderer], and [BrowserTab].
 */
object JsoupDOMBuilder {

    /**
     * Parses [html] and builds a KDOM tree in the VMM managed by [allocator].
     * Returns the root "document" element pointer (never 0L on success).
     */
    fun parse(html: String, allocator: VMMAllocator): Long {
        val doc: Document = Jsoup.parse(html)
        val root = KDOM.createElement(allocator, "document")
        for (child in doc.childNodes()) {
            val childPtr = buildNode(child, allocator)
            if (childPtr != 0L) {
                KDOM.appendChild(allocator.vmm, root, childPtr)
            }
        }
        return root
    }

    // ── Internal tree walk ────────────────────────────────────────────────────

    private fun buildNode(node: Node, allocator: VMMAllocator): Long = when (node) {
        is Element  -> buildElement(node, allocator)
        is DataNode -> {
            // Raw content of <script>, <style>, <template>, etc. — preserve exactly.
            val text = node.wholeData
            if (text.isBlank()) 0L else KDOM.createTextNode(allocator, text)
        }
        is TextNode -> {
            // Regular text content — preserve whitespace so <pre> and inline CSS work.
            val text = node.wholeText
            if (text.isBlank()) 0L else KDOM.createTextNode(allocator, text)
        }
        else -> 0L  // skip Comment, DocumentType, XmlDeclaration, etc.
    }

    private fun buildElement(element: Element, allocator: VMMAllocator): Long {
        val ptr = KDOM.createElement(allocator, element.tagName())

        for (attr in element.attributes()) {
            KDOM.setAttribute(allocator, ptr, attr.key, attr.value)
        }

        for (child in element.childNodes()) {
            val childPtr = buildNode(child, allocator)
            if (childPtr != 0L) {
                KDOM.appendChild(allocator.vmm, ptr, childPtr)
            }
        }

        return ptr
    }
}
