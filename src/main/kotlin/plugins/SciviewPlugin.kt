package plugins

import org.mastodon.mamut.SciviewBridge
import org.scijava.command.Command
import org.scijava.plugin.Menu
import org.scijava.plugin.Plugin
import sc.iview.commands.MenuWeights.HELP
import sc.iview.commands.MenuWeights.HELP_HELP
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel

@Plugin(
    type = Command::class,
    menuRoot = "SciView",
    menu = [Menu(label = "Help", weight = HELP), Menu(label = "Mastodon Bridge", weight = HELP_HELP)]
)
class SciviewPlugin : Command {
    override fun run() {
        val panel = JFrame("Mastodon-sciview Bridge Keys Overview")
        panel.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
        val gridBagLayout = GridBagLayout()
        panel.layout = gridBagLayout
        val c = GridBagConstraints()
        c.fill = GridBagConstraints.BOTH
        c.insets = Insets(5, 8, 0, 8)
        c.weighty = 0.1
        c.gridy = 0
        val spacer = JLabel("   ")
        collectRowsOfText().forEach{
            c.anchor = GridBagConstraints.EAST
            c.weightx = 0.3
            c.gridx = 0
            panel.add(JLabel(it[0]), c)
            c.weightx = 0.1
            c.gridx = 1
            panel.add(spacer, c)
            c.anchor = GridBagConstraints.LINE_START
            c.weightx = 0.3
            c.gridx = 2
            panel.add(JLabel(it[1]), c)
            c.gridy++
        }
        val closeBtn = JButton("Close")
        closeBtn.addActionListener { panel.dispose() }
        c.gridx = 0
        c.gridwidth = 3
        c.weightx = 0.0
        c.weighty = 0.0
        c.insets = Insets(15, 30, 5, 30)
        panel.add(closeBtn, c)
        panel.pack()
        panel.isVisible = true
    }

    private fun collectRowsOfText(): Collection<Array<String?>> {
        val rows: MutableCollection<Array<String?>> = ArrayList(15)
        rows.add(arrayOf(SciviewBridge.key_DEC_SPH, SciviewBridge.desc_DEC_SPH))
        rows.add(arrayOf(SciviewBridge.key_INC_SPH, SciviewBridge.desc_INC_SPH))
        rows.add(arrayOf(SciviewBridge.key_PREV_TP, SciviewBridge.desc_PREV_TP))
        rows.add(arrayOf(SciviewBridge.key_NEXT_TP, SciviewBridge.desc_NEXT_TP))
        rows.add(arrayOf(SciviewBridge.key_CTRL_WIN, SciviewBridge.desc_CTRL_WIN))
        rows.add(arrayOf(SciviewBridge.key_CTRL_INFO, SciviewBridge.desc_CTRL_INFO))
        return rows
    }
}
