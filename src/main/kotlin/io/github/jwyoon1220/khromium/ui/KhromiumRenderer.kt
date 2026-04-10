package io.github.jwyoon1220.khromium.ui

import io.github.jwyoon1220.khromium.core.VirtualMemoryManager
import io.github.jwyoon1220.khromium.css.CSSParser
import io.github.jwyoon1220.khromium.css.CSSStyle
import io.github.jwyoon1220.khromium.dom.KDOM
import java.awt.*
import javax.swing.JPanel
import javax.swing.Timer

/**
 * KhromiumRenderer is a Swing JPanel that paints a KDOM tree (stored in VMM)
 * using the Dirty-Rect + isDirty paradigm described in the Khromium spec (§7.2).
 *
 * Design:
 *   - The panel holds a reference to the VMM root pointer and a parsed StyleSheet.
 *   - [isDirty] is set whenever the DOM or styles change; the Swing repaint() call
 *     is issued only if [isDirty] is true.
 *   - Rendering is a single recursive DFS over the VMM DOM tree.
 *   - Default UA styles are applied per tag name; inline style= attributes override them.
 */
class KhromiumRenderer(
    val vmm: VirtualMemoryManager
) : JPanel() {

    /** Whether a repaint is needed. Set to true on DOM mutations. */
    @Volatile var isDirty: Boolean = true

    private var domRoot: Long = 0L
    private var styleSheet: CSSParser.StyleSheet = CSSParser.StyleSheet(emptyMap())
    private val cssParser = CSSParser()

    // Dirty-rect repaint timer (approx 30 fps)
    private val renderTimer = Timer(33) {
        if (isDirty) {
            repaint()
            isDirty = false
        }
    }.also { it.start() }

    /** Update the DOM root and mark dirty. */
    fun setDom(root: Long, css: String = "") {
        domRoot     = root
        styleSheet  = if (css.isNotBlank()) cssParser.parse(css) else CSSParser.StyleSheet(emptyMap())
        isDirty     = true
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (domRoot == 0L) return
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val ctx = RenderContext(g2, 8, 8, width - 16)
        renderNode(domRoot, ctx, defaultStyle("body"))
    }

    // ── rendering helpers ─────────────────────────────────────────────────────

    private data class RenderContext(
        val g: Graphics2D,
        var x: Int,
        var y: Int,
        val maxWidth: Int
    )

    private fun renderNode(nodePtr: Long, ctx: RenderContext, parentStyle: CSSStyle) {
        if (nodePtr == 0L) return
        val type = vmm.readInt(nodePtr + KDOM.OFFSET_TYPE)

        when (type) {
            KDOM.TYPE_ELEMENT -> renderElement(nodePtr, ctx, parentStyle)
            KDOM.TYPE_TEXT    -> renderText(nodePtr, ctx, parentStyle)
        }
    }

    private fun renderElement(nodePtr: Long, ctx: RenderContext, parentStyle: CSSStyle) {
        val namePtr = vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
        val tagName = if (namePtr != 0L) vmm.readString(namePtr).lowercase() else return

        // Skip non-visual elements
        if (tagName in setOf("html", "head", "title", "meta", "link", "script", "style", "document")) {
            // Still descend into <style> to capture inline CSS
            if (tagName == "style") {
                val textContent = collectText(nodePtr)
                styleSheet = cssParser.parse(textContent)
            }
            // Descend into children for structural elements
            var child = vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                renderNode(child, ctx, parentStyle)
                child = vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
            return
        }

        val uaStyle = defaultStyle(tagName)
        var style   = uaStyle

        // Apply stylesheet rules
        style = styleSheet.rules[tagName]?.let { style.merge(it) } ?: style
        val idAttr = getAttr(nodePtr, "id")
        if (idAttr != null) style = styleSheet.rules["#$idAttr"]?.let { style.merge(it) } ?: style
        val classAttr = getAttr(nodePtr, "class")
        if (classAttr != null) {
            for (cls in classAttr.split(" ")) {
                style = styleSheet.rules[".$cls"]?.let { style.merge(it) } ?: style
            }
        }
        val inlineStyle = getAttr(nodePtr, "style")
        if (inlineStyle != null) style = style.merge(cssParser.parseInline(inlineStyle))

        if (style.display == CSSStyle.Display.NONE) return

        // Block-level: add top margin and reset x
        if (style.display != CSSStyle.Display.INLINE) {
            ctx.y += style.marginTop + style.paddingTop
            ctx.x = style.marginLeft + style.paddingLeft
        }

        // Draw background
        if (style.backgroundColor != null) {
            ctx.g.color = style.backgroundColor
            ctx.g.fillRect(ctx.x, ctx.y, ctx.maxWidth, estimateBlockHeight(nodePtr, style))
        }

        // Draw border
        if (style.border != null) {
            ctx.g.color = style.border.color
            ctx.g.stroke = BasicStroke(style.border.width.toFloat())
            ctx.g.drawRect(ctx.x, ctx.y, ctx.maxWidth, estimateBlockHeight(nodePtr, style))
        }

        // Horizontal rule
        if (tagName == "hr") {
            ctx.g.color = Color.GRAY
            ctx.g.drawLine(ctx.x, ctx.y, ctx.x + ctx.maxWidth, ctx.y)
            ctx.y += 6
            return
        }

        // Render children
        val childCtx = ctx.copy()
        var child = vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
        while (child != 0L) {
            renderNode(child, childCtx, style)
            child = vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
        }
        ctx.y = childCtx.y

        // Block-level: add bottom margin/padding
        if (style.display != CSSStyle.Display.INLINE) {
            ctx.y += style.marginBottom + style.paddingBottom
            ctx.x = style.marginLeft
        }
    }

    private fun renderText(nodePtr: Long, ctx: RenderContext, style: CSSStyle) {
        val textPtr = vmm.readLong(nodePtr + KDOM.OFFSET_TEXT_DATA)
        val text    = if (textPtr != 0L) vmm.readString(textPtr).trim() else return
        if (text.isEmpty()) return

        ctx.g.font  = style.toAwtFont()
        ctx.g.color = style.color
        val fm      = ctx.g.fontMetrics

        // Word-wrap
        val words = text.split(Regex("\\s+"))
        var lineX = ctx.x
        val words2 = mutableListOf<String>()
        for (word in words) {
            val w = fm.stringWidth("$word ")
            if (lineX + w > ctx.x + ctx.maxWidth && words2.isNotEmpty()) {
                // draw current line
                val line = words2.joinToString(" ")
                ctx.g.drawString(line, ctx.x + style.paddingLeft, ctx.y + fm.ascent)
                ctx.y += fm.height
                words2.clear()
                lineX = ctx.x
            }
            words2.add(word)
            lineX += w
        }
        if (words2.isNotEmpty()) {
            val line = words2.joinToString(" ")
            ctx.g.drawString(line, ctx.x + style.paddingLeft, ctx.y + fm.ascent)
            ctx.y += fm.height
        }
    }

    private fun estimateBlockHeight(nodePtr: Long, style: CSSStyle): Int {
        // Simple heuristic: font height × number of text lines
        val text = collectText(nodePtr)
        val lineCount = (text.length / 60).coerceAtLeast(1)
        return style.fontSize * lineCount + style.paddingTop + style.paddingBottom + 8
    }

    private fun collectText(nodePtr: Long): String {
        if (nodePtr == 0L) return ""
        val sb = StringBuilder()
        if (vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_TEXT) {
            val p = vmm.readLong(nodePtr + KDOM.OFFSET_TEXT_DATA)
            if (p != 0L) sb.append(vmm.readString(p))
        }
        var child = vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
        while (child != 0L) {
            sb.append(collectText(child))
            child = vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
        }
        return sb.toString()
    }

    private fun getAttr(nodePtr: Long, attrName: String): String? {
        if (vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) != KDOM.TYPE_ELEMENT) return null
        var attr = vmm.readLong(nodePtr + KDOM.OFFSET_ELEMENT_ATTRS)
        while (attr != 0L) {
            val np = vmm.readLong(attr + KDOM.OFFSET_ATTR_NAME)
            if (np != 0L && vmm.readString(np) == attrName) {
                val vp = vmm.readLong(attr + KDOM.OFFSET_ATTR_VALUE)
                return if (vp != 0L) vmm.readString(vp) else ""
            }
            attr = vmm.readLong(attr + KDOM.OFFSET_ATTR_NEXT)
        }
        return null
    }

    private fun defaultStyle(tagName: String): CSSStyle = when (tagName) {
        "h1"         -> CSSStyle.HEADING_H1
        "h2"         -> CSSStyle.HEADING_H2
        "h3", "h4",
        "h5", "h6"   -> CSSStyle.HEADING_H3
        "p"          -> CSSStyle.PARAGRAPH
        "a"          -> CSSStyle.LINK
        "code"       -> CSSStyle.CODE
        "pre"        -> CSSStyle.PRE
        "body"       -> CSSStyle.BODY
        "ul", "ol"   -> CSSStyle(marginTop = 8, marginBottom = 8, marginLeft = 24)
        "li"         -> CSSStyle(marginTop = 2, marginBottom = 2)
        "strong","b" -> CSSStyle(fontBold = true)
        "em","i"     -> CSSStyle(fontItalic = true)
        "div","section",
        "article",
        "main","nav",
        "header","footer" -> CSSStyle.BODY
        else         -> CSSStyle()
    }
}
