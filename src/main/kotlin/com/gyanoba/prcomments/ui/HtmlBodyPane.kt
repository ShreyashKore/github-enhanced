package com.gyanoba.prcomments.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.basic.BasicTextUI
import javax.swing.text.View

/**
 * Read-only HTML pane for a Markdown comment body.
 *
 * Swing's `JEditorPane` reports a preferred size that ignores the width it will actually get, which
 * makes it useless inside a vertically stacked panel. [getPreferredSize] asks the view hierarchy for
 * the height at the current width instead, so wrapped text lays out correctly.
 */
class HtmlBodyPane : JEditorPane() {

    init {
        editorKit = buildKit()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        font = StartupUiUtil.labelFont
        foreground = UIUtil.getLabelForeground()
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                event.url?.let { BrowserUtil.browse(it) } ?: event.description?.let { BrowserUtil.browse(it) }
            }
        }
        addComponentListener(object : ComponentAdapter() {
            private var lastWidth = -1
            override fun componentResized(e: ComponentEvent) {
                if (width != lastWidth) {
                    lastWidth = width
                    revalidate()
                }
            }
        })
    }

    fun setMarkdown(markdown: String) {
        text = "<html><body>" + MarkdownRenderer.toHtml(markdown) + "</body></html>"
        caretPosition = 0
        revalidate()
    }

    override fun getPreferredSize(): Dimension {
        val width = width
        if (width <= 0) return super.getPreferredSize()
        val textUi = ui as? BasicTextUI ?: return super.getPreferredSize()
        val insets = insets
        val contentWidth = (width - insets.left - insets.right).coerceAtLeast(1)
        val root = textUi.getRootView(this)
        root.setSize(contentWidth.toFloat(), Float.MAX_VALUE)
        val height = root.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
        return Dimension(width, height)
    }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_WIDTH), preferredSize.height)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun buildKit() = HTMLEditorKitBuilder().withWordWrapViewFactory().build().apply {
        val code = hex(UIUtil.getTextFieldBackground())
        val border = hex(JBColor.border())
        val muted = hex(UIUtil.getContextHelpForeground())
        styleSheet.addRule("body { margin: 0; padding: 0; }")
        styleSheet.addRule("p { margin: 0 0 6px 0; }")
        styleSheet.addRule("ul, ol { margin: 0 0 6px 18px; padding: 0; }")
        styleSheet.addRule("li { margin: 0 0 2px 0; }")
        styleSheet.addRule("h3, h4, h5, h6 { margin: 6px 0 4px 0; }")
        styleSheet.addRule("code { background-color: $code; }")
        styleSheet.addRule("pre { background-color: $code; margin: 4px 0; padding: 4px; }")
        styleSheet.addRule("blockquote { margin: 4px 0 4px 8px; padding-left: 8px; border-left: 2px solid $border; color: $muted; }")
        styleSheet.addRule("hr { border-top: 1px solid $border; }")
    }

    companion object {
        private const val MIN_WIDTH = 120

        private fun hex(color: Color): String = "#%06x".format(color.rgb and 0xFFFFFF)
    }
}
