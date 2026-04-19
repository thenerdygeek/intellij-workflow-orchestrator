package com.workflow.orchestrator.core.insights

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class ReportRangeDialog : DialogWrapper(true) {

    private val rangeCombo = JComboBox(arrayOf(
        "Last 7 days",
        "Last 30 days",
        "This week",
        "This month",
        "All time",
    )).apply {
        selectedIndex = 0
    }

    private val aiCheckBox = JCheckBox("Include AI recommendations", true)

    init {
        title = "Generate Insights Report"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("Date range:"), gbc)

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        panel.add(rangeCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(aiCheckBox, gbc)

        return panel
    }

    val windowStartMs: Long
        get() {
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            return when (rangeCombo.selectedItem as String) {
                "Last 7 days"  -> now - 7L * 24 * 60 * 60 * 1000
                "Last 30 days" -> now - 30L * 24 * 60 * 60 * 1000
                "This week"    -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                "This month"   -> today.withDayOfMonth(1)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                "All time"     -> 0L
                else           -> now - 7L * 24 * 60 * 60 * 1000
            }
        }

    val windowEndMs: Long
        get() = System.currentTimeMillis()

    val includeAI: Boolean
        get() = aiCheckBox.isSelected
}
