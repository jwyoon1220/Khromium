package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.*
import io.github.jwyoon1220.khromium.dom.*
import io.github.jwyoon1220.khromium.js.*
import io.github.jwyoon1220.khromium.net.KhromiumNetworkClient
import io.github.jwyoon1220.khromium.ui.BrowserTab
import com.formdev.flatlaf.FlatDarkLaf
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.*

// ── Memory visualizer ──────────────────────────────────────────────────────────

class MemoryVisualizer(val memory: PhysicalMemoryManager) : JPanel() {
    init {
        Timer(100) { repaint() }.start()
        preferredSize = Dimension(800, 30)
        toolTipText   = "Physical Memory: ${memory.totalSizeMega} MB — orange = allocated, grey = free"
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val blocks    = memory.debugBlocks
        val totalWidth = width.toDouble()
        val memSize   = memory.totalSizeBytes.toDouble()
        if (memSize <= 0) return

        var currentX = 0
        blocks.forEach { block ->
            val blockWidth = ((block.size / memSize) * totalWidth).toInt().coerceAtLeast(1)
            g.color = if (block.isFree) Color(220, 220, 220) else Color(255, 165, 0)
            g.fillRect(currentX, 0, blockWidth, height)
            currentX += blockWidth
        }
        // border
        g.color = Color.DARK_GRAY
        g.drawRect(0, 0, width - 1, height - 1)

        // stats overlay
        val used  = memory.totalPages - memory.freePageCount
        val pct   = if (memory.totalPages > 0) used * 100 / memory.totalPages else 0
        g.color = Color.DARK_GRAY
        g.drawString("PMM: ${memory.totalSizeMega} MB  |  used=$pct%  |  free pages=${memory.freePageCount}", 4, 13)
    }
}

// ── Dev Tools panel (HTML source + JS console) ────────────────────────────────

fun createDevToolsTab(process: KProcess, sharedCache: SharedBytecodeCache): JPanel {
    val panel    = JPanel(BorderLayout())
    val htmlArea = JTextArea("""<html>
  <head>
    <style>
      body { font-family: SansSerif; margin: 16px; }
      h1   { color: #2c3e50; }
      p    { color: #555; }
      .box { background-color: #ecf0f1; padding: 8px; border: 1px solid #bdc3c7; }
    </style>
  </head>
  <body>
    <h1 id="title">Khromium Off-Heap Demo</h1>
    <p class="box">This paragraph is stored entirely in off-heap VMM memory.</p>
  </body>
</html>""")
    val jsArea   = JTextArea("""var title = document.getElementById("title");
if (title) {
    title.innerHTML = "Hello from Khromium JS Engine!";
}
"done";
""")

    val runBtn    = JButton("▶  Parse & Execute")
    val outputArea = JTextArea()
    outputArea.isEditable = false

    runBtn.addActionListener {
        try {
            // 1. Parse HTML → KDOM in VMM (Jsoup handles full HTML5 syntax)
            val domRoot = JsoupDOMBuilder.parse(htmlArea.text, process.allocator)

            // 2. Execute JavaScript via KhromiumJsRuntime (auto-selects QuickJS or Nashorn)
            KhromiumJsRuntime(process.pid.toString(), process, sharedCache, domRoot).use { runtime ->
                val result = runtime.execute(jsArea.text)
                val baos   = java.io.ByteArrayOutputStream()
                val oldOut = System.out
                System.setOut(java.io.PrintStream(baos))
                KDOM.printTree(process.vmm, domRoot)
                println()
                println("--- JS Execution Result ---")
                println(result)
                System.setOut(oldOut)
                outputArea.text = baos.toString()
            }

        } catch (e: SecurityBreachException) {
            outputArea.text = buildString {
                appendLine("⚠  SECURITY BREACH — TAB TERMINATED")
                appendLine("═".repeat(60))
                appendLine()
                appendLine(e.message)
                appendLine()
                appendLine("The tab's VMM has been torn down.")
                appendLine("All other tabs and the Khromium kernel continue running.")
            }
        } catch (e: Exception) {
            outputArea.text = "Error:\n${e.stackTraceToString()}"
        }
    }

    val inputSplit  = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(htmlArea), JScrollPane(jsArea))
    inputSplit.resizeWeight = 0.6
    val centerSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputSplit, JScrollPane(outputArea))
    centerSplit.resizeWeight = 0.5

    panel.add(centerSplit, BorderLayout.CENTER)
    panel.add(runBtn,      BorderLayout.SOUTH)
    return panel
}

// ── Component playground ──────────────────────────────────────────────────────

fun createComponentTab(process: KProcess): JPanel {
    val panel = JPanel(BorderLayout())
    val kComp = KComponent(process)
    kComp.kName   = "VMM Managed Component"
    kComp.kX      = 50
    kComp.kY      = 50
    kComp.kWidth  = 300
    kComp.kHeight = 200

    panel.add(kComp, BorderLayout.CENTER)

    val btn = JButton("Move Component via VMM Pointer")
    btn.addActionListener { kComp.kX += 10; kComp.kY += 10 }
    panel.add(btn, BorderLayout.SOUTH)
    return panel
}

// ── Memory hex inspector ──────────────────────────────────────────────────────

fun createHexInspectorTab(pmm: PhysicalMemoryManager): JPanel {
    val panel = JPanel(BorderLayout())
    panel.add(JScrollPane(HexEditorPanel(pmm)), BorderLayout.CENTER)
    return panel
}

// ── Security demo ─────────────────────────────────────────────────────────────

fun createSecurityDemoTab(pmm: PhysicalMemoryManager): JPanel {
    val panel      = JPanel(BorderLayout())
    val outputArea = JTextArea()
    outputArea.isEditable = false
    outputArea.font = Font("Monospaced", Font.PLAIN, 13)

    val runBtn = JButton("Run Security Demonstrations")
    runBtn.addActionListener {
        val sb = StringBuilder()

        // 1. Canary detection
        sb.appendLine("=== 1. Heap Canary Detection ===")
        runCatching {
            val vmm1   = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)
            val alloc1 = VMMAllocator(vmm1, 0x800000L, 512 * 1024)
            val ptr    = alloc1.malloc(16)
            vmm1.write(ptr, ByteArray(28) { 0xDE.toByte() })  // overflow into canary
            alloc1.free(ptr)
        }.onSuccess {
            sb.appendLine("  [FAIL] Canary not triggered!")
        }.onFailure { e ->
            if (e is SecurityBreachException) sb.appendLine("  [PASS] Canary caught overflow: ${e.message?.take(80)}")
            else sb.appendLine("  [ERROR] ${e.message}")
        }

        // 2. Tab isolation
        sb.appendLine("\n=== 2. Tab Isolation ===")
        runCatching {
            val vmm2   = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)
            val vmm3   = VirtualMemoryManager(pmm, VirtualMemoryManager.VMMType.PRIVATE)
            val alloc2 = VMMAllocator(vmm2, 0xA00000L, 512 * 1024)
            val alloc3 = VMMAllocator(vmm3, 0xC00000L, 512 * 1024)
            val p2     = alloc2.malloc(16)
            val p3     = alloc3.malloc(16)
            vmm2.write(p2, ByteArray(28) { 0xBE.toByte() }) // attack tab 2
            var tab2Caught = false
            runCatching { alloc2.free(p2) }.onFailure { e ->
                if (e is SecurityBreachException) {
                    tab2Caught = true
                    sb.appendLine("  Tab 2 breach detected: ${e.message?.take(60)}")
                    vmm2.destroy()
                }
            }
            // Tab 3 must still work
            alloc3.free(p3)
            alloc3.malloc(8)
            sb.appendLine("  [PASS] Tab 3 unaffected after Tab 2 breach=$tab2Caught")
        }.onFailure { e -> sb.appendLine("  [ERROR] ${e.message}") }

        // 3. Cookie isolation
        sb.appendLine("\n=== 3. Cookie Domain Isolation ===")
        runCatching {
            val cs = io.github.jwyoon1220.khromium.core.CookieStore(pmm)
            cs.set("example.com", "session", "abc123")
            val ok = cs.get("example.com")
            sb.appendLine("  Correct domain get: ${ok.size} cookie(s) returned")
            cs.get("evil.com").also {
                sb.appendLine("  Wrong domain returned ${it.size} cookies (should be 0)")
            }
        }.onFailure { e -> sb.appendLine("  [ERROR] ${e.message}") }

        // 4. Opaque handles
        sb.appendLine("\n=== 4. Opaque Handle Cross-Tab Check ===")
        runCatching {
            val mgr    = io.github.jwyoon1220.khromium.core.OpaqueHandleManager<String>()
            val handle = mgr.register("secret-resource", ownerTabId = "tab-1")
            sb.appendLine("  Handle issued: $handle")
            runCatching { mgr.resolve(handle, "tab-2") }.onFailure { e ->
                if (e is SecurityBreachException) sb.appendLine("  [PASS] Cross-tab resolve blocked: ${e.message?.take(60)}")
            }
            val res = mgr.resolve(handle, "tab-1")
            sb.appendLine("  [PASS] Owner resolved: $res")
        }.onFailure { e -> sb.appendLine("  [ERROR] ${e.message}") }

        // 5. FaultRetryPolicy
        sb.appendLine("\n=== 5. Fault Retry Policy ===")
        runCatching {
            val policy = io.github.jwyoon1220.khromium.core.FaultRetryPolicy("demo-tab", maxRetries = 3, baseDelayMs = 1L)
            var calls  = 0
            runCatching {
                policy.withRetry(0xDEADBEEFL) {
                    calls++
                    throw io.github.jwyoon1220.khromium.core.SegmentationFaultException("simulated fault at 0xDEADBEEF")
                }
            }.onFailure { e ->
                if (e is SecurityBreachException) sb.appendLine("  [PASS] Retry exhausted after $calls calls → breach: ${e.message?.take(60)}")
            }
        }.onFailure { e -> sb.appendLine("  [ERROR] ${e.message}") }

        outputArea.text = sb.toString()
    }

    panel.add(JScrollPane(outputArea), BorderLayout.CENTER)
    panel.add(runBtn, BorderLayout.SOUTH)
    return panel
}

// ── Main ───────────────────────────────────────────────────────────────────────

fun main() {
    // ── Kernel initialisation ─────────────────────────────────────────────────
    // Shared Physical RAM (128 MB — supports multiple tabs + shared cache)
    val pmm         = PhysicalMemoryManager(128)
    val sharedCache = SharedBytecodeCache(pmm)

    // Start background eviction daemon
    val evictionDaemon = EvictionDaemon(sharedCache, intervalSec = 60L)
    evictionDaemon.start()

    // Attempt to initialise the native TLSF heap (optional — Nashorn fallback is used if unavailable)
    NativeMemoryManager.initHeap(32L * 1024L * 1024L)

    // Shared network client (all tabs share one connection pool)
    val networkClient = KhromiumNetworkClient()

    // ── Swing UI ──────────────────────────────────────────────────────────────
    // Modern dark look-and-feel (FlatLaf — same family as IntelliJ/Minecraft tooling)
    FlatDarkLaf.setup()
    // Attempt to enable OpenGL acceleration; ignored if unavailable on this platform
    runCatching { System.setProperty("sun.java2d.opengl", "true") }
    SwingUtilities.invokeLater {
        val frame = JFrame("Khromium Browser Engine v2.3")
        frame.layout = BorderLayout()
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

        // Top: memory visualizer
        val visualizer = MemoryVisualizer(pmm)
        frame.add(visualizer, BorderLayout.NORTH)

        // Center: tabbed pane
        val tabbedPane = JTabbedPane()

        // Tab 1 & 2: real browser tabs (with address bar + renderer)
        val proc1    = KProcess(pmm)
        val browser1 = BrowserTab(proc1, sharedCache, networkClient)
        tabbedPane.addTab("Browser Tab 1", browser1)
        browser1.navigate("about:khromium")

        val proc2    = KProcess(pmm)
        val browser2 = BrowserTab(proc2, sharedCache, networkClient)
        tabbedPane.addTab("Browser Tab 2", browser2)
        browser2.navigate("about:blank")

        // Tab 3: DevTools (HTML/JS playground)
        val proc3 = KProcess(pmm)
        tabbedPane.addTab("Dev Tools", createDevToolsTab(proc3, sharedCache))

        // Tab 4: VMM Component demo
        val proc4 = KProcess(pmm)
        tabbedPane.addTab("VMM UI Demo", createComponentTab(proc4))

        // Tab 5: Physical memory hex inspector
        tabbedPane.addTab("Hex Inspector", createHexInspectorTab(pmm))

        // Tab 6: Security demonstrations
        tabbedPane.addTab("Security Demo", createSecurityDemoTab(pmm))

        // Toolbar: New Tab button
        val newTabBtn = JButton("+ New Tab")
        var tabCounter = 3
        newTabBtn.addActionListener {
            tabCounter++
            val proc = KProcess(pmm)
            val tab  = BrowserTab(proc, sharedCache, networkClient)
            tabbedPane.addTab("Tab $tabCounter", tab)
            tabbedPane.selectedIndex = tabbedPane.tabCount - 1
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.add(newTabBtn)
        toolbar.add(JLabel("  about:khromium — Khromium Engine v2.3"))

        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(toolbar,     BorderLayout.NORTH)
        centerPanel.add(tabbedPane,  BorderLayout.CENTER)
        frame.add(centerPanel, BorderLayout.CENTER)

        frame.setSize(1200, 800)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        // Ensure eviction daemon is stopped on JVM shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            evictionDaemon.stop()
        })
    }
}
