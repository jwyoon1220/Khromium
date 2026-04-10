package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.SecurityBreachException
import io.github.jwyoon1220.khromium.core.KProcess
import io.github.jwyoon1220.khromium.core.NativeMemoryManager
import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import io.github.jwyoon1220.khromium.dom.*
import io.github.jwyoon1220.khromium.js.*
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import java.awt.*
import javax.swing.*

class MemoryVisualizer(val memory: PhysicalMemoryManager) : JPanel() {
    init {
        Timer(100) { repaint() }.start()
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val blocks = memory.debugBlocks
        val totalWidth = width.toDouble()
        val memSize = memory.totalSizeBytes.toDouble()

        if (memSize <= 0) return

        var currentX = 0
        blocks.forEach { block ->
            val blockWidth = ((block.size / memSize) * totalWidth).toInt().coerceAtLeast(1)
            g.color = if (block.isFree) Color(220, 220, 220) else Color(255, 165, 0)
            g.fillRect(currentX, 0, blockWidth, height)
            g.color = Color.DARK_GRAY
            g.drawRect(currentX, 0, blockWidth, height)
            currentX += blockWidth
        }
    }
}

fun createRendererTab(process: KProcess, sharedCache: SharedBytecodeCache): JPanel {
    val panel = JPanel(BorderLayout())
    val htmlArea = JTextArea(
        """<html>
          <body id="main">
            <h1 id="title">Loading...</h1>
          </body>
        </html>"""
    )
    val jsArea = JTextArea(
        """var header = document.getElementById("title");
        header.innerHTML = "Hello From Khromium Off-Heap JS!";
        """
    )
    
    val runBtn = JButton("Parse & Execute")
    val outputArea = JTextArea()
    outputArea.isEditable = false

    runBtn.addActionListener {
        try {
            // 1. HTML Rendering Phase (DOM Allocation)
            val htmlLexer = HTMLLexer(CharStreams.fromString(htmlArea.text))
            val htmlTokens = CommonTokenStream(htmlLexer)
            val htmlParser = HTMLParser(htmlTokens)
            val domRoot = HTMLDOMBuilder(process.allocator).visit(htmlParser.document())

            // 2. JavaScript Execution Phase (KhromiumJsRuntime with AOT cache)
            KhromiumJsRuntime(process.pid.toString(), process, sharedCache).use { runtime ->
                val result = runtime.execute(jsArea.text)

                // 3. Debug Output
                val oldOut = System.out
                val baos = java.io.ByteArrayOutputStream()
                System.setOut(java.io.PrintStream(baos))
                KDOM.printTree(process.vmm, domRoot)
                println()
                println("--- QuickJS Execution Result ---")
                println(result)
                System.setOut(oldOut)
                outputArea.text = baos.toString()
            }
            
        } catch (e: SecurityBreachException) {
            // Canary corruption / overflow attack detected in this tab's VMM.
            // The runtime has already destroyed the tab's process; display the
            // security alert here — other tabs are completely unaffected.
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
            outputArea.text = "Kernel Panic: \n${e.stackTraceToString()}"
        }
    }

    val inputSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(htmlArea), JScrollPane(jsArea))
    inputSplit.resizeWeight = 0.5
    
    val centerSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputSplit, JScrollPane(outputArea))
    centerSplit.resizeWeight = 0.5

    panel.add(centerSplit, BorderLayout.CENTER)
    panel.add(runBtn, BorderLayout.SOUTH)
    return panel
}

fun createComponentTab(process: KProcess): JPanel {
    val panel = JPanel(BorderLayout())
    // Instantiate KComponent allocating state into isolated VMM
    val kComp = KComponent(process)
    kComp.kName = "VMM Managed Component"
    kComp.kX = 50
    kComp.kY = 50
    kComp.kWidth = 300
    kComp.kHeight = 200
    
    panel.add(kComp, BorderLayout.CENTER)
    
    val btn = JButton("Move Component via VMM Pointer")
    btn.addActionListener {
        kComp.kX += 10
        kComp.kY += 10
    }
    panel.add(btn, BorderLayout.SOUTH)
    return panel
}

fun main() {
    // Shared Physical RAM (64MB – sized to accommodate two 10 MB private heaps + 4 MB shared cache)
    val pmm = PhysicalMemoryManager(64)

    NativeMemoryManager.initHeap(16L * 1024L * 1024L)

    // Shared bytecode/result cache backed by Shared VMM (AOT cache for JS scripts)
    val sharedCache = SharedBytecodeCache(pmm)

    val frame = JFrame("Khromium Engine v2.3 - Multiprocess Architecture")
    frame.layout = BorderLayout()

    val visualizer = MemoryVisualizer(pmm)
    visualizer.preferredSize = Dimension(800, 40)
    frame.add(visualizer, BorderLayout.NORTH)

    val tabbedPane = JTabbedPane()

    // Process 1: The UI Component Demo
    val proc1 = KProcess(pmm)
    tabbedPane.addTab("Process 1: UI VMM Test", createComponentTab(proc1))

    // Process 2: The HTML/JS Engine Demo
    val proc2 = KProcess(pmm)
    tabbedPane.addTab("Process 2: Browser Engine", createRendererTab(proc2, sharedCache))

    frame.add(tabbedPane, BorderLayout.CENTER)

    frame.setSize(1000, 700)
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
    frame.setLocationRelativeTo(null)
    frame.isVisible = true
}
