
import bdv.ui.rangeslider.RangeSlider
import ui.AbstractAdjustableSliderBasedControl
import ui.AdjustableBoundsRangeSlider
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner

object TestingAdjustableRange {
    const val MINBOUND = 0
    const val MAXBOUND = 500
    const val LOWVALUE = 30
    const val HIGHVALUE = 170
    fun ownLayoutOfControls() {
        val frame = JFrame("range slider test - own layout")
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
        val gridBagLayout = GridBagLayout()
        frame.layout = gridBagLayout
        val c = GridBagConstraints()
        c.anchor = GridBagConstraints.LINE_START
        c.fill = GridBagConstraints.HORIZONTAL

        //set to the current wanted range
        val slider = RangeSlider(MINBOUND, MAXBOUND)
        slider.setValue(LOWVALUE)
        slider.setUpperValue(HIGHVALUE)
        val lowValueSpinner = JSpinner(
            AbstractAdjustableSliderBasedControl.createAppropriateSpinnerModel(slider.value)
        )
        val highValueSpinner = JSpinner(
            AbstractAdjustableSliderBasedControl.createAppropriateSpinnerModel(slider.upperValue)
        )
        val minBoundInfo = JLabel(slider.minimum.toString())
        val maxBoundInfo = JLabel(slider.maximum.toString())
        c.insets = Insets(5, 10, 5, 10)
        c.gridy = 0
        c.weightx = 0.05
        c.gridx = 0
        frame.add(minBoundInfo, c)
        c.weightx = 0.1
        c.gridx = 1
        frame.add(lowValueSpinner, c)
        c.weightx = 0.7
        c.gridx = 2
        frame.add(JLabel("  <------------>  "), c)
        c.weightx = 0.1
        c.gridx = 3
        frame.add(highValueSpinner, c)
        c.weightx = 0.05
        c.gridx = 4
        frame.add(maxBoundInfo, c)
        c.gridy = 1
        c.weightx = 0.9
        c.gridx = 0
        c.gridwidth = 5
        frame.add(slider, c)
        val ctrl = AdjustableBoundsRangeSlider(
            slider,
            lowValueSpinner, highValueSpinner,
            minBoundInfo, maxBoundInfo
        )
        val msg = JLabel("Values are " + slider.value + " and " + slider.upperValue)
        ctrl.addChangeListener { l -> msg.setText("Values are " + slider.value + " and " + slider.upperValue) }
        //
        c.gridy = 2
        c.gridx = 0
        c.gridwidth = 3
        c.weightx = 0.2
        frame.add(msg, c)
        frame.pack()
        frame.isVisible = true
    }

    fun defaultLayoutOfControls() {
        val frame = JFrame("range slider test - default layout")
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
        val gridBagLayout = GridBagLayout()
        frame.layout = gridBagLayout
        val c = GridBagConstraints()
        c.anchor = GridBagConstraints.LINE_START
        c.fill = GridBagConstraints.HORIZONTAL
        c.weightx = 0.1
        c.insets = Insets(5, 10, 5, 10)
        c.gridy = 0
        c.gridx = 0
        val placeHolder = JPanel()
        frame.add(placeHolder, c)
        val ctrl: AdjustableBoundsRangeSlider = AdjustableBoundsRangeSlider
            .createAndPlaceHere(placeHolder, LOWVALUE, HIGHVALUE, MINBOUND, MAXBOUND)
        c.gridy = 1
        c.gridx = 0
        val msg = JLabel("Values are " + ctrl.value + " and " + ctrl.upperValue)
        ctrl.addChangeListener { msg.setText("Values are " + ctrl.value + " and " + ctrl.upperValue) }
        frame.add(msg, c)
        frame.pack()
        frame.isVisible = true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        ownLayoutOfControls()
        defaultLayoutOfControls()
    }
}