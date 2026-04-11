package io.github.jwyoon1220.khromium.ui

import io.github.jwyoon1220.khromium.core.KProcess
import io.github.jwyoon1220.khromium.core.SecurityBreachException
import io.github.jwyoon1220.khromium.dom.JsoupDOMBuilder
import io.github.jwyoon1220.khromium.dom.KDOM
import io.github.jwyoon1220.khromium.js.KhromiumJsRuntime
import io.github.jwyoon1220.khromium.js.SharedBytecodeCache
import io.github.jwyoon1220.khromium.net.KhromiumNetworkClient
import java.awt.*
import java.util.ArrayDeque
import javax.swing.*

/**
 * BrowserTab is a full browser tab component with:
 *   - Navigation bar (address field + Back / Forward / Reload buttons)
 *   - [KhromiumRenderer] for HTML/CSS rendering
 *   - History stack (back/forward navigation)
 *   - Integrated network fetch, DOM parsing, JS execution, and rendering pipeline
 *   - Security isolation: crashes / breaches affect only this tab
 */
class BrowserTab(
    private val process: KProcess,
    private val sharedCache: SharedBytecodeCache,
    private val networkClient: KhromiumNetworkClient = KhromiumNetworkClient()
) : JPanel(BorderLayout()) {

    // ── Navigation state ─────────────────────────────────────────────────────
    private val historyBack    = ArrayDeque<String>()
    private val historyForward = ArrayDeque<String>()
    private var currentUrl     = ""

    // ── UI Components ────────────────────────────────────────────────────────
    private val addressField   = JTextField("about:blank")
    private val backBtn        = JButton("◀")
    private val forwardBtn     = JButton("▶")
    private val reloadBtn      = JButton("↺")
    private val stopBtn        = JButton("✕")
    private val statusBar      = JLabel(" Ready")
    private val renderer       = KhromiumRenderer(process.vmm)
    private val scrollPane     = JScrollPane(renderer)

    // ── Worker thread ─────────────────────────────────────────────────────────
    @Volatile private var loadWorker: Thread? = null

    init {
        renderer.background = Color.WHITE
        renderer.preferredSize = Dimension(800, 2000)
        buildUI()
        updateNavButtons()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Navigates to [url], pushing the current page onto the back stack. */
    fun navigate(url: String) {
        val normalized = normalizeUrl(url)
        if (normalized == currentUrl) { reload(); return }
        if (currentUrl.isNotBlank()) historyBack.push(currentUrl)
        historyForward.clear()
        doLoad(normalized)
    }

    /** Reloads the current page. */
    fun reload() {
        if (currentUrl.isNotBlank()) doLoad(currentUrl)
    }

    /** Navigates back one page. */
    fun back() {
        if (historyBack.isEmpty()) return
        historyForward.push(currentUrl)
        doLoad(historyBack.pop())
    }

    /** Navigates forward one page. */
    fun forward() {
        if (historyForward.isEmpty()) return
        historyBack.push(currentUrl)
        doLoad(historyForward.pop())
    }

    // ── Private: loading pipeline ─────────────────────────────────────────────

    private fun doLoad(url: String) {
        stopLoad()
        currentUrl = url
        addressField.text = url
        setStatus("Loading $url …")
        updateNavButtons()

        loadWorker = Thread({
            try {
                when {
                    url.startsWith("about:") -> loadAboutPage(url)
                    url.startsWith("view-source:") -> loadViewSource(url.removePrefix("view-source:"))
                    else -> loadUrl(url)
                }
            } catch (e: SecurityBreachException) {
                SwingUtilities.invokeLater {
                    showErrorPage("⚠ SECURITY BREACH\n\n${e.message}")
                    setStatus("Security breach — tab terminated")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    showErrorPage("Failed to load $url\n\n${e.message}")
                    setStatus("Error: ${e.message?.take(80)}")
                }
            }
        }, "khromium-loader-${process.pid}").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun loadUrl(url: String) {
        val response = networkClient.get(url)
        val html = when {
            response.mimeType.contains("html") -> response.bodyText
            response.mimeType.contains("text") -> "<pre>${response.bodyText}</pre>"
            else -> "<html><body><p>Cannot render ${response.mimeType}</p></body></html>"
        }
        parseAndRender(html)
        SwingUtilities.invokeLater { setStatus("${response.statusCode} — $url") }
    }

    private fun loadAboutPage(url: String) {
        val html = when (url) {
            "about:blank"   -> "<html><body></body></html>"
            "about:khromium" -> """
                <html><body>
                  <h1>Khromium v2.3</h1>
                  <p>A hybrid kernel browser engine built on the JVM.</p>
                  <h2>Architecture</h2>
                  <ul>
                    <li>Off-heap PMM/VMM memory subsystem</li>
                    <li>Per-tab VMM isolation with canary protection</li>
                    <li>5-retry fault tolerance with attack detection</li>
                    <li>Shared bytecode cache with LRU eviction</li>
                    <li>Cross-platform JS: QuickJS (native) or Nashorn (JVM)</li>
                    <li>Cookie store with domain isolation</li>
                    <li>Opaque handle manager for native resources</li>
                  </ul>
                </body></html>""".trimIndent()
            else -> "<html><body><p>Unknown page: $url</p></body></html>"
        }
        parseAndRender(html)
        SwingUtilities.invokeLater { setStatus("OK — $url") }
    }

    private fun loadViewSource(url: String) {
        val response = networkClient.get(url)
        val escaped  = response.bodyText
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val html = "<html><body><pre>$escaped</pre></body></html>"
        parseAndRender(html)
        SwingUtilities.invokeLater { setStatus("Source: $url") }
    }

    private fun parseAndRender(html: String) {
        // 1. Parse HTML → KDOM in VMM (Jsoup handles DOCTYPE, comments, implied tags, etc.)
        val domRoot = JsoupDOMBuilder.parse(html, process.allocator)

        // 2. Extract <style> CSS from DOM
        val css = extractStyleTag(domRoot)

        // 3. Execute inline <script> tags
        val scripts = extractScripts(domRoot)
        if (scripts.isNotEmpty()) {
            KhromiumJsRuntime(process.pid.toString(), process, sharedCache, domRoot).use { runtime ->
                for (script in scripts) {
                    runCatching { runtime.execute(script) }
                }
            }
        }

        // 4. Render
        SwingUtilities.invokeLater {
            renderer.setDom(domRoot, css)
            renderer.isDirty = true
            renderer.repaint()
        }
    }

    private fun showErrorPage(message: String) {
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        try {
            parseAndRender("<html><body><h2>Error</h2><pre>$escaped</pre></body></html>")
        } catch (e: Exception) {
            // Allocator may be in a bad state (e.g. after a prior crash) — fall back to a
            // plain-text label so the AWT thread never dies with an unhandled exception.
            SwingUtilities.invokeLater {
                renderer.setDom(0L)   // clear stale DOM
                renderer.repaint()
            }
        }
    }

    private fun stopLoad() {
        loadWorker?.interrupt()
        loadWorker = null
    }

    // ── DOM helpers ───────────────────────────────────────────────────────────

    private fun extractStyleTag(nodePtr: Long): String {
        if (nodePtr == 0L) return ""
        val sb = StringBuilder()
        if (process.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = process.vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L && process.vmm.readString(namePtr).lowercase() == "style") {
                sb.append(collectText(nodePtr))
            }
            var child = process.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                sb.append(extractStyleTag(child))
                child = process.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return sb.toString()
    }

    private fun extractScripts(nodePtr: Long): List<String> {
        if (nodePtr == 0L) return emptyList()
        val result = mutableListOf<String>()
        if (process.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_ELEMENT) {
            val namePtr = process.vmm.readLong(nodePtr + KDOM.OFFSET_NAME)
            if (namePtr != 0L && process.vmm.readString(namePtr).lowercase() == "script") {
                val text = collectText(nodePtr)
                if (text.isNotBlank()) result.add(text)
            }
            var child = process.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
            while (child != 0L) {
                result.addAll(extractScripts(child))
                child = process.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
            }
        }
        return result
    }

    private fun collectText(nodePtr: Long): String {
        if (nodePtr == 0L) return ""
        val sb = StringBuilder()
        if (process.vmm.readInt(nodePtr + KDOM.OFFSET_TYPE) == KDOM.TYPE_TEXT) {
            val p = process.vmm.readLong(nodePtr + KDOM.OFFSET_TEXT_DATA)
            if (p != 0L) sb.append(process.vmm.readString(p))
        }
        var child = process.vmm.readLong(nodePtr + KDOM.OFFSET_FIRST_CHILD)
        while (child != 0L) {
            sb.append(collectText(child))
            child = process.vmm.readLong(child + KDOM.OFFSET_NEXT_SIB)
        }
        return sb.toString()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun buildUI() {
        val navBar = JPanel(BorderLayout(4, 0)).also { it.border = BorderFactory.createEmptyBorder(4, 4, 4, 4) }
        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        btnPanel.add(backBtn)
        btnPanel.add(forwardBtn)
        btnPanel.add(reloadBtn)
        btnPanel.add(stopBtn)

        addressField.addActionListener { navigate(addressField.text.trim()) }
        val goBtn = JButton("Go")
        goBtn.addActionListener { navigate(addressField.text.trim()) }

        navBar.add(btnPanel, BorderLayout.WEST)
        navBar.add(addressField, BorderLayout.CENTER)
        navBar.add(goBtn, BorderLayout.EAST)

        backBtn.addActionListener    { back() }
        forwardBtn.addActionListener { forward() }
        reloadBtn.addActionListener  { reload() }
        stopBtn.addActionListener    { stopLoad(); setStatus("Stopped") }

        add(navBar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun updateNavButtons() {
        backBtn.isEnabled    = historyBack.isNotEmpty()
        forwardBtn.isEnabled = historyForward.isNotEmpty()
    }

    private fun setStatus(msg: String) {
        SwingUtilities.invokeLater { statusBar.text = " $msg" }
    }

    private fun normalizeUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("about:") || url.startsWith("view-source:")) return url
        if (url.contains(".") && !url.contains(" ")) return "https://$url"
        // Search fallback (DuckDuckGo)
        val query = java.net.URLEncoder.encode(url, "UTF-8")
        return "https://duckduckgo.com/?q=$query"
    }
}
