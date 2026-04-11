package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.KProcess
import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.dom.JsoupDOMBuilder
import io.github.jwyoon1220.khromium.js.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsEngineAndDomBridgeTest {

    private fun makePmm() = PhysicalMemoryManager(16)

    // ── NashornEngine ─────────────────────────────────────────────────────────

    @Test
    fun `NashornEngine initialises and evaluates arithmetic`() {
        val engine = NashornEngine()
        assertTrue(engine.initRuntime(), "Nashorn should init successfully")
        val result = engine.eval("2 + 3")
        assertEquals("5", result)
        engine.destroyRuntime()
    }

    @Test
    fun `NashornEngine returns undefined for void expression`() {
        val engine = NashornEngine()
        engine.initRuntime()
        val result = engine.eval("var x = 10;")
        // Nashorn returns "undefined" for a var declaration with no return value
        assertTrue(result == "undefined" || result.isBlank() || result == "null")
        engine.destroyRuntime()
    }

    @Test
    fun `NashornEngine handles syntax errors gracefully`() {
        val engine = NashornEngine()
        engine.initRuntime()
        val result = engine.eval("!!!! invalid syntax $$$")
        assertTrue(result.contains("ScriptException") || result.contains("Error") || result.contains("error"))
        engine.destroyRuntime()
    }

    @Test
    fun `NashornEngine bindGlobal exposes host object`() {
        val engine = NashornEngine()
        engine.initRuntime()
        engine.bindGlobal("greeting", "Hello from Kotlin!")
        val result = engine.eval("greeting")
        assertEquals("Hello from Kotlin!", result)
        engine.destroyRuntime()
    }

    // ── DomBridge ─────────────────────────────────────────────────────────────

    private fun parseDom(html: String, process: KProcess): Long =
        JsoupDOMBuilder.parse(html, process.allocator)

    @Test
    fun `DomBridge getElementById finds element by id attribute`() {
        val pmm     = makePmm()
        val process = KProcess(pmm)
        val dom = parseDom("""<html><body><h1 id="title">Hello</h1></body></html>""", process)

        val bridge  = DomBridge(process.allocator, dom)
        val element = bridge.getElementById("title")
        assertNotNull(element)
        assertEquals("h1", element.tagName)
    }

    @Test
    fun `DomBridge getElementById returns null for missing id`() {
        val pmm     = makePmm()
        val process = KProcess(pmm)
        val dom = parseDom("""<html><body><p>No id here</p></body></html>""", process)
        val bridge  = DomBridge(process.allocator, dom)
        val element = bridge.getElementById("nonexistent")
        assertEquals(null, element)
    }

    @Test
    fun `DomBridge innerHTML setter updates text content`() {
        val pmm     = makePmm()
        val process = KProcess(pmm)
        val dom = parseDom("""<html><body><h1 id="t">Original</h1></body></html>""", process)
        val bridge  = DomBridge(process.allocator, dom)
        val el = bridge.getElementById("t")
        assertNotNull(el)

        el.innerHTML = "Updated"
        assertEquals("Updated", el.textContent)
    }

    @Test
    fun `DomBridge getAttribute returns attribute value`() {
        val pmm     = makePmm()
        val process = KProcess(pmm)
        val dom = parseDom("""<html><body><a href="https://example.com">Link</a></body></html>""", process)
        val bridge  = DomBridge(process.allocator, dom)
        val links   = bridge.getElementsByTagName("a")
        assertTrue(links.isNotEmpty())
        assertEquals("https://example.com", links[0].getAttribute("href"))
    }

    // ── KhromiumJsRuntime with Nashorn + DOM bridge ───────────────────────────

    @Test
    fun `KhromiumJsRuntime with DOM bridge allows getElementById`() {
        val pmm     = makePmm()
        val process = KProcess(pmm)
        val dom = parseDom("""<html><body><h1 id="t">Old</h1></body></html>""", process)

        val cache = SharedBytecodeCache(pmm)
        val runtime = KhromiumJsRuntime(process.pid.toString(), process, cache, dom)
        val result = runtime.execute("""
            var el = document.getElementById("t");
            if (el) { el.innerHTML = "New Value"; "ok"; } else { "not found"; }
        """.trimIndent())
        assertEquals("ok", result)
        runtime.close()
    }
}
