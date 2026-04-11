package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.KProcess
import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.dom.JsoupDOMBuilder
import io.github.jwyoon1220.khromium.dom.KDOM
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [JsoupDOMBuilder] – HTML → KDOM conversion.
 */
class JsoupDOMBuilderTest {

    private fun makeProcess() = KProcess(PhysicalMemoryManager(16))

    private fun parse(html: String): Pair<KProcess, Long> {
        val proc = makeProcess()
        return proc to JsoupDOMBuilder.parse(html, proc.allocator)
    }

    // ── basic structure ───────────────────────────────────────────────────────

    @Test
    fun `parse returns non-zero root pointer`() {
        val (_, root) = parse("<html><body></body></html>")
        assertNotEquals(0L, root, "Root pointer must not be 0")
    }

    @Test
    fun `root element is named document`() {
        val (proc, root) = parse("<html></html>")
        val namePtr = proc.vmm.readLong(root + KDOM.OFFSET_NAME)
        val name    = proc.vmm.readString(namePtr)
        assertEquals("document", name)
    }

    @Test
    fun `parse builds element nodes for standard tags`() {
        val (proc, root) = parse("<html><body><h1>Hello</h1></body></html>")
        // Walk document → html → body → h1
        assertTrue(findTagInTree(proc, root, "h1"), "h1 element must be present in the tree")
    }

    @Test
    fun `parse creates text node with correct content`() {
        val (proc, root) = parse("<html><body><p>Khromium</p></body></html>")
        val text = collectAllText(proc, root)
        assertTrue(text.contains("Khromium"), "Text node must contain 'Khromium'")
    }

    @Test
    fun `parse handles nested tags correctly`() {
        val (proc, root) = parse("<html><body><ul><li>A</li><li>B</li></ul></body></html>")
        assertTrue(findTagInTree(proc, root, "ul"), "ul must be present")
        assertTrue(findTagInTree(proc, root, "li"), "li must be present")
    }

    @Test
    fun `parse handles attributes`() {
        val (proc, root) = parse("""<html><body><a href="https://example.com">Link</a></body></html>""")
        val href = findAttrInTree(proc, root, "a", "href")
        assertEquals("https://example.com", href)
    }

    @Test
    fun `parse handles id attribute`() {
        val (proc, root) = parse("""<html><body><div id="main">Content</div></body></html>""")
        val id = findAttrInTree(proc, root, "div", "id")
        assertEquals("main", id)
    }

    // ── special content ───────────────────────────────────────────────────────

    @Test
    fun `parse preserves script tag content as text node`() {
        val (proc, root) = parse("""<html><head><script>var x = 1;</script></head><body></body></html>""")
        val text = collectAllText(proc, root)
        assertTrue(text.contains("var x = 1;"), "Script content must be preserved as text")
    }

    @Test
    fun `parse preserves style tag content as text node`() {
        val (proc, root) = parse("""<html><head><style>h1 { color: red; }</style></head><body></body></html>""")
        val text = collectAllText(proc, root)
        assertTrue(text.contains("color: red"), "Style content must be preserved")
    }

    @Test
    fun `parse handles empty body without crashing`() {
        val (_, root) = parse("<html><body></body></html>")
        assertNotEquals(0L, root)
    }

    @Test
    fun `parse handles minimal html`() {
        val (_, root) = parse("<p>minimal</p>")
        assertNotEquals(0L, root)
    }

    @Test
    fun `parse handles deeply nested elements`() {
        val html = "<html><body>" + "<div>".repeat(10) + "deep" + "</div>".repeat(10) + "</body></html>"
        val (proc, root) = parse(html)
        val text = collectAllText(proc, root)
        assertTrue(text.contains("deep"))
    }

    @Test
    fun `parse handles multiple sibling elements`() {
        val (proc, root) = parse("<html><body><h1>A</h1><h2>B</h2><h3>C</h3></body></html>")
        assertTrue(findTagInTree(proc, root, "h1"))
        assertTrue(findTagInTree(proc, root, "h2"))
        assertTrue(findTagInTree(proc, root, "h3"))
    }

    @Test
    fun `parse handles special characters in text`() {
        val (proc, root) = parse("<html><body><p>안녕하세요 &amp; 🎉</p></body></html>")
        val text = collectAllText(proc, root)
        assertTrue(text.contains("안녕하세요"), "Korean text must round-trip correctly")
    }

    // ── helper functions ──────────────────────────────────────────────────────

    private fun findTagInTree(proc: KProcess, nodePtr: Long, tag: String): Boolean {
        if (nodePtr == 0L) return false
        if (proc.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = proc.vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L && proc.vmm.readString(namePtr).lowercase() == tag) return true
            var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                if (findTagInTree(proc, child, tag)) return true
                child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return false
    }

    private fun findAttrInTree(proc: KProcess, nodePtr: Long, tag: String, attr: String): String? {
        if (nodePtr == 0L) return null
        if (proc.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = proc.vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L && proc.vmm.readString(namePtr).lowercase() == tag) {
                var a = proc.vmm.readLong(nodePtr + KDOM.OFFSET_ELEMENT_ATTRS)
                while (a != 0L) {
                    val kPtr = proc.vmm.readLong(a + KDOM.OFFSET_ATTR_NAME)
                    if (kPtr != 0L && proc.vmm.readString(kPtr) == attr) {
                        val vPtr = proc.vmm.readLong(a + KDOM.OFFSET_ATTR_VALUE)
                        return if (vPtr != 0L) proc.vmm.readString(vPtr) else ""
                    }
                    a = proc.vmm.readLong(a + KDOM.OFFSET_ATTR_NEXT)
                }
            }
            var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                val result = findAttrInTree(proc, child, tag, attr)
                if (result != null) return result
                child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return null
    }

    private fun collectAllText(proc: KProcess, nodePtr: Long): String {
        if (nodePtr == 0L) return ""
        val sb = StringBuilder()
        val type = proc.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE)
        if (type == KDOM.TYPE_TEXT) {
            val p = proc.vmm.readLong(nodePtr + KDOM.OFFSET_TEXT_DATA)
            if (p != 0L) sb.append(proc.vmm.readString(p))
        }
        var child = proc.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
        while (child != 0L) {
            sb.append(collectAllText(proc, child))
            child = proc.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
        }
        return sb.toString()
    }
}
