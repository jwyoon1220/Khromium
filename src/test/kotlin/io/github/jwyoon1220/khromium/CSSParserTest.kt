package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.css.CSSParser
import io.github.jwyoon1220.khromium.css.CSSStyle
import org.junit.jupiter.api.Test
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CSSParserTest {

    private val parser = CSSParser()

    @Test
    fun `parse basic type selector`() {
        val sheet = parser.parse("h1 { color: #ff0000; font-size: 24px; font-weight: bold; }")
        val style = sheet.rules["h1"]
        assertNotNull(style)
        assertEquals(Color.RED, style.color)
        assertEquals(24, style.fontSize)
        assertTrue(style.fontBold)
    }

    @Test
    fun `parse id selector`() {
        val sheet = parser.parse("#myId { color: #0000ff; }")
        val style = sheet.rules["#myId"]
        assertNotNull(style)
        assertEquals(Color.BLUE, style.color)
    }

    @Test
    fun `parse class selector`() {
        val sheet = parser.parse(".box { background-color: #eeeeee; padding: 8px; }")
        val style = sheet.rules[".box"]
        assertNotNull(style)
        assertNotNull(style.backgroundColor)
        assertEquals(8, style.paddingTop)
        assertEquals(8, style.paddingLeft)
    }

    @Test
    fun `parse display none`() {
        val sheet = parser.parse("span { display: none; }")
        assertEquals(CSSStyle.Display.NONE, sheet.rules["span"]?.display)
    }

    @Test
    fun `parse inline style`() {
        val style = parser.parseInline("color: #00ff00; font-size: 16px;")
        assertEquals(16, style.fontSize)
        assertEquals(Color(0, 255, 0), style.color)
    }

    @Test
    fun `parse named colors`() {
        val sheet = parser.parse("p { color: red; background-color: white; }")
        val style = sheet.rules["p"]
        assertEquals(Color.RED, style?.color)
        assertEquals(Color.WHITE, style?.backgroundColor)
    }

    @Test
    fun `parse 3-digit hex color`() {
        val sheet = parser.parse("a { color: #f00; }")
        val style = sheet.rules["a"]
        assertEquals(Color(255, 0, 0), style?.color)
    }

    @Test
    fun `parse margin shorthand`() {
        val style = parser.parseInline("margin: 16px;")
        assertEquals(16, style.marginTop)
        assertEquals(16, style.marginBottom)
        assertEquals(16, style.marginLeft)
        assertEquals(16, style.marginRight)
    }

    @Test
    fun `strip CSS comments`() {
        val sheet = parser.parse("/* this is a comment */ h2 { color: blue; }")
        assertNotNull(sheet.rules["h2"])
    }

    @Test
    fun `multiple selectors with comma`() {
        val sheet = parser.parse("h1, h2, h3 { font-weight: bold; }")
        assertTrue(sheet.rules["h1"]?.fontBold == true)
        assertTrue(sheet.rules["h2"]?.fontBold == true)
        assertTrue(sheet.rules["h3"]?.fontBold == true)
    }
}
