package org.mastodon.mamut

import ui.AdjustableBoundsSlider
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.ChangeEvent

object TestingAdjustableSlider {
    @JvmStatic
    fun main(args: Array<String>) {
        val frame = JFrame("slider test")
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
        val gridBagLayout = GridBagLayout()
        frame.layout = gridBagLayout
        val c = GridBagConstraints()
        c.weighty = 0.3
        c.fill = GridBagConstraints.BOTH

        //row
        c.gridy = 0
        c.gridx = 0
        c.weightx = 0.1
        frame.add(JButton("no op"), c)
        c.gridx = 1
        c.weightx = 0.8
        frame.add(JButton("no op"), c)
        c.gridx = 2
        c.weightx = 0.1
        frame.add(JButton("no op"), c)

        //row
        c.weightx = 0.0
        c.gridy = 1
        c.gridx = 0
        frame.add(JButton("no op"), c)
        //x=1 is missing for now
        c.gridx = 2
        frame.add(JButton("no op"), c)

        //row
        c.gridy = 2
        c.gridx = 0
        frame.add(JButton("no op"), c)
        //x=1 is missing for now
        c.gridx = 2
        frame.add(JButton("no op"), c)
        val placeHolder = JPanel()
        c.gridy = 1
        c.gridx = 1
        frame.add(placeHolder, c)
        //
        val slider = AdjustableBoundsSlider.createAndPlaceHere(placeHolder, 33, 0, 100)
        val msg = JLabel("Current slider value: " + slider.value)
        c.gridy = 2
        c.gridx = 1
        frame.add(msg, c)
        //
        slider.addChangeListener { l: ChangeEvent? ->
            msg.setText("Current slider value: " + slider.value)
            print('.')
        }
        frame.pack()
        frame.isVisible = true
    }
}