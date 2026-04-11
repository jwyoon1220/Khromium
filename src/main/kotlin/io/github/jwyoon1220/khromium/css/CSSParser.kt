package io.github.jwyoon1220.khromium.css

import java.awt.Color

/**
 * Simplified CSS parser that understands a subset of CSS syntax:
 *   - type selectors:      `h1 { ... }`
 *   - class selectors:     `.cls { ... }`
 *   - id selectors:        `#id { ... }`
 *   - inline style attr:   parsed from a single declaration string
 *
 * Supported properties:
 *   color, background-color, font-family, font-size, font-weight, font-style,
 *   margin, margin-top/right/bottom/left, padding, padding-top/right/bottom/left,
 *   display, text-align, border
 */
class CSSParser {

    /** Parsed stylesheet: selector string → CSSStyle */
    data class StyleSheet(val rules: Map<String, CSSStyle>)

    /**
     * Parses a full stylesheet string into a [StyleSheet].
     */
    fun parse(css: String): StyleSheet {
        val rules = mutableMapOf<String, CSSStyle>()
        // Strip comments
        val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        // Match rule blocks: selector { declarations }
        val rulePattern = Regex("""([^{]+)\{([^}]*)}""")
        for (match in rulePattern.findAll(clean)) {
            val selectors = match.groupValues[1].split(",").map { it.trim() }
            val style     = parseDeclarationBlock(match.groupValues[2])
            for (sel in selectors) {
                if (sel.isNotBlank()) rules[sel.trim()] = style
            }
        }
        return StyleSheet(rules)
    }

    /**
     * Parses a single inline style declaration string (e.g. from an HTML `style="..."` attribute).
     */
    fun parseInline(declarations: String): CSSStyle = parseDeclarationBlock(declarations)

    // ── internals ────────────────────────────────────────────────────────────

    private fun parseDeclarationBlock(block: String): CSSStyle {
        var style = CSSStyle()
        for (decl in block.split(";")) {
            val parts = decl.split(":").map { it.trim() }
            if (parts.size < 2) continue
            val prop  = parts[0].lowercase()
            val value = parts[1].lowercase()
            style = applyProperty(style, prop, value)
        }
        return style
    }

    private fun applyProperty(base: CSSStyle, prop: String, value: String): CSSStyle {
        return when (prop) {
            "color"            -> base.copy(color = parseColor(value) ?: base.color)
            "background-color",
            "background"       -> base.copy(backgroundColor = parseColor(value))
            "font-family"      -> base.copy(fontFamily = value.trim().removeSurrounding("\"").removeSurrounding("'"))
            "font-size"        -> base.copy(fontSize = parsePx(value) ?: base.fontSize)
            "font-weight"      -> base.copy(fontBold = value == "bold" || value == "700" || value == "800" || value == "900")
            "font-style"       -> base.copy(fontItalic = value == "italic" || value == "oblique")
            "margin"           -> { val px = parsePx(value) ?: 0; base.copy(marginTop=px, marginRight=px, marginBottom=px, marginLeft=px) }
            "margin-top"       -> base.copy(marginTop = parsePx(value) ?: base.marginTop)
            "margin-right"     -> base.copy(marginRight = parsePx(value) ?: base.marginRight)
            "margin-bottom"    -> base.copy(marginBottom = parsePx(value) ?: base.marginBottom)
            "margin-left"      -> base.copy(marginLeft = parsePx(value) ?: base.marginLeft)
            "padding"          -> { val px = parsePx(value) ?: 0; base.copy(paddingTop=px, paddingRight=px, paddingBottom=px, paddingLeft=px) }
            "padding-top"      -> base.copy(paddingTop = parsePx(value) ?: base.paddingTop)
            "padding-right"    -> base.copy(paddingRight = parsePx(value) ?: base.paddingRight)
            "padding-bottom"   -> base.copy(paddingBottom = parsePx(value) ?: base.paddingBottom)
            "padding-left"     -> base.copy(paddingLeft = parsePx(value) ?: base.paddingLeft)
            "display"          -> base.copy(display = parseDisplay(value))
            "text-align"       -> base.copy(textAlign = parseTextAlign(value))
            "border"           -> parseBorderProp(base, value)
            else               -> base
        }
    }

    private fun parseColor(value: String): Color? {
        if (value.startsWith("#")) {
            val hex = value.removePrefix("#")
            return when (hex.length) {
                3 -> Color(
                    Integer.parseInt("${hex[0]}${hex[0]}", 16),
                    Integer.parseInt("${hex[1]}${hex[1]}", 16),
                    Integer.parseInt("${hex[2]}${hex[2]}", 16)
                )
                6 -> Color(
                    Integer.parseInt(hex.substring(0, 2), 16),
                    Integer.parseInt(hex.substring(2, 4), 16),
                    Integer.parseInt(hex.substring(4, 6), 16)
                )
                8 -> Color(
                    Integer.parseInt(hex.substring(0, 2), 16),
                    Integer.parseInt(hex.substring(2, 4), 16),
                    Integer.parseInt(hex.substring(4, 6), 16),
                    Integer.parseInt(hex.substring(6, 8), 16)
                )
                else -> null
            }
        }
        return when (value.trim()) {
            "black"   -> Color.BLACK
            "white"   -> Color.WHITE
            "red"     -> Color.RED
            "green"   -> Color(0, 128, 0)
            "blue"    -> Color.BLUE
            "gray","grey"    -> Color.GRAY
            "silver"  -> Color(192,192,192)
            "yellow"  -> Color.YELLOW
            "orange"  -> Color(255,165,0)
            "purple"  -> Color(128,0,128)
            "pink"    -> Color(255,192,203)
            "navy"    -> Color(0,0,128)
            "teal"    -> Color(0,128,128)
            "transparent" -> Color(0,0,0,0)
            else      -> null
        }
    }

    private fun parsePx(value: String): Int? =
        value.removeSuffix("px").removeSuffix("pt").trim().toIntOrNull()

    private fun parseDisplay(value: String) = when (value.trim()) {
        "block"        -> CSSStyle.Display.BLOCK
        "inline"       -> CSSStyle.Display.INLINE
        "inline-block" -> CSSStyle.Display.INLINE_BLOCK
        "none"         -> CSSStyle.Display.NONE
        "flex"         -> CSSStyle.Display.FLEX
        else           -> CSSStyle.Display.BLOCK
    }

    private fun parseTextAlign(value: String) = when (value.trim()) {
        "center"  -> CSSStyle.TextAlign.CENTER
        "right"   -> CSSStyle.TextAlign.RIGHT
        "justify" -> CSSStyle.TextAlign.JUSTIFY
        else      -> CSSStyle.TextAlign.LEFT
    }

    private fun parseBorderProp(base: CSSStyle, value: String): CSSStyle {
        // e.g. "1px solid black"
        val parts = value.split(" ").map { it.trim() }.filter { it.isNotEmpty() }
        val width = parts.getOrNull(0)?.let { parsePx(it) } ?: 1
        val color = parts.getOrNull(2)?.let { parseColor(it) } ?: Color.BLACK
        return base.copy(border = CSSStyle.Border(width, color))
    }
}
