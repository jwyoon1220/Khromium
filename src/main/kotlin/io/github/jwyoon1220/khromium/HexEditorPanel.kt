package io.github.jwyoon1220.khromium

import io.github.jwyoon1220.khromium.core.PhysicalMemoryManager
import javax.swing.*
import java.awt.*
import java.nio.ByteBuffer

class HexEditorPanel(private val memory: PhysicalMemoryManager) : JPanel(), Scrollable {
    private val font = Font("Monospaced", Font.PLAIN, 14)
    private val lineHeight = 20
    private val charWidth = 10
    private val rowCapacity = 16 // 한 줄에 16바이트

    init {
        background = Color.WHITE
        // 전체 메모리 크기에 따른 가상 높이 설정 (스크롤바 크기 결정)
        val totalRows = (memory.totalSizeBytes + rowCapacity - 1) / rowCapacity
        preferredSize = Dimension(700, totalRows.toInt() * lineHeight)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.font = font
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // 1. 현재 화면에 보이는 사각형 영역(ClipBounds) 획득
        val clip = g.getClipBounds() ?: return
        val buffer = memory.rawBuffer
        val totalSize = memory.totalSizeBytes

        // 2. 그려야 할 시작 줄과 끝 줄 계산 (O(1) 수준의 계산)
        val startRow = clip.y / lineHeight
        val endRow = (clip.y + clip.height) / lineHeight + 1

        for (i in startRow..endRow) {
            val offset = i * rowCapacity
            if (offset >= totalSize) break

            val y = (i + 1) * lineHeight

            // 주소(Offset) 그리기
            g2.color = Color.GRAY
            g2.drawString(String.format("%08X ", offset), 10, y)

            // 데이터 그리기
            val hexBuilder = StringBuilder()
            val asciiBuilder = StringBuilder()

            for (j in 0 until rowCapacity) {
                val addr = offset + j
                if (addr < totalSize) {
                    val b = buffer.get(addr).toInt() and 0xFF
                    hexBuilder.append(String.format("%02X ", b))
                    asciiBuilder.append(if (b in 32..126) b.toChar() else '.')
                } else {
                    hexBuilder.append("   ") // 빈 공간 채우기
                }
            }

            g2.color = Color.BLACK
            g2.drawString(hexBuilder.toString(), 100, y)
            g2.color = Color(0, 100, 0) // 아스키는 진한 초록색으로 구분
            g2.drawString("| $asciiBuilder", 500, y)
        }
    }

    // --- Scrollable 인터페이스 구현 (부드러운 스크롤을 위해) ---
    override fun getPreferredScrollableViewportSize(): Dimension = Dimension(700, 400)
    override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = lineHeight
    override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = lineHeight * 10
    override fun getScrollableTracksViewportWidth(): Boolean = false
    override fun getScrollableTracksViewportHeight(): Boolean = false
}