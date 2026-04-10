package io.github.jwyoon1220.khromium.css

import java.awt.Color
import java.awt.Font

/**
 * Simplified CSS style properties applied to a DOM element.
 */
data class CSSStyle(
    val color: Color = Color.BLACK,
    val backgroundColor: Color? = null,
    val fontFamily: String = "SansSerif",
    val fontSize: Int = 14,
    val fontBold: Boolean = false,
    val fontItalic: Boolean = false,
    val marginTop: Int = 0,
    val marginBottom: Int = 0,
    val marginLeft: Int = 0,
    val marginRight: Int = 0,
    val paddingTop: Int = 0,
    val paddingBottom: Int = 0,
    val paddingLeft: Int = 0,
    val paddingRight: Int = 0,
    val display: Display = Display.BLOCK,
    val textAlign: TextAlign = TextAlign.LEFT,
    val border: Border? = null
) {
    enum class Display { BLOCK, INLINE, INLINE_BLOCK, NONE, FLEX }
    enum class TextAlign { LEFT, CENTER, RIGHT, JUSTIFY }
    data class Border(val width: Int, val color: Color, val style: String = "solid")

    fun toAwtFont(): Font {
        var style = Font.PLAIN
        if (fontBold)   style = style or Font.BOLD
        if (fontItalic) style = style or Font.ITALIC
        return Font(fontFamily, style, fontSize)
    }

    /** Merges [override] on top of this style (override wins on non-default values). */
    fun merge(override: CSSStyle): CSSStyle = CSSStyle(
        color           = override.color,
        backgroundColor = override.backgroundColor ?: backgroundColor,
        fontFamily      = override.fontFamily,
        fontSize        = override.fontSize,
        fontBold        = override.fontBold,
        fontItalic      = override.fontItalic,
        marginTop       = override.marginTop,
        marginBottom    = override.marginBottom,
        marginLeft      = override.marginLeft,
        marginRight     = override.marginRight,
        paddingTop      = override.paddingTop,
        paddingBottom   = override.paddingBottom,
        paddingLeft     = override.paddingLeft,
        paddingRight    = override.paddingRight,
        display         = override.display,
        textAlign       = override.textAlign,
        border          = override.border ?: border
    )

    companion object {
        /** Default styles for HTML block elements. */
        val HEADING_H1 = CSSStyle(fontSize = 28, fontBold = true, marginTop = 16, marginBottom = 8)
        val HEADING_H2 = CSSStyle(fontSize = 22, fontBold = true, marginTop = 14, marginBottom = 6)
        val HEADING_H3 = CSSStyle(fontSize = 18, fontBold = true, marginTop = 12, marginBottom = 4)
        val PARAGRAPH  = CSSStyle(fontSize = 14, marginTop = 8,  marginBottom = 8)
        val BODY       = CSSStyle(fontSize = 14, marginLeft = 8, marginRight = 8, marginTop = 8)
        val LINK       = CSSStyle(fontSize = 14, color = Color(0, 0, 200))
        val CODE       = CSSStyle(fontSize = 13, fontFamily = "Monospaced",
                                  backgroundColor = Color(240, 240, 240))
        val PRE        = CSSStyle(fontSize = 13, fontFamily = "Monospaced",
                                  backgroundColor = Color(240, 240, 240),
                                  marginTop = 8, marginBottom = 8,
                                  paddingLeft = 8, paddingRight = 8,
                                  paddingTop = 4, paddingBottom = 4)
    }
}
