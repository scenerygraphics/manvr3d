package ui

import java.awt.Container
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.UIManager

class AdjustableBoundsSlider(
    manageThisSlider: JSlider?,
    associatedValueSpinner: JSpinner?,
    associatedLowBound: JLabel?,
    associatedHighBound: JLabel?
) : AbstractAdjustableSliderBasedControl(
    manageThisSlider!!, associatedValueSpinner!!, associatedLowBound!!, associatedHighBound!!
) {
    companion object {
        fun createAndPlaceHere(
            intoThisComponent: Container,
            initialValue: Int,
            initialMin: Int,
            initialMax: Int
        ): AdjustableBoundsSlider {
            require(!(initialValue < initialMin || initialValue > initialMax)) { "Refuse to create slider showing value that's outside the slider's min and max range." }
            require(!(initialMin < MIN_BOUND_LIMIT || initialMin > MAX_BOUND_LIMIT)) { "Required MIN bound is outside the interval assumed by this governing class." }
            require(!(initialMax < MIN_BOUND_LIMIT || initialMax > MAX_BOUND_LIMIT)) { "Required MAX bound is outside the interval assumed by this governing class." }
            val gridBagLayout = GridBagLayout()
            intoThisComponent.setLayout(gridBagLayout)
            val c = GridBagConstraints()
            c.anchor = GridBagConstraints.LINE_START
            c.fill = GridBagConstraints.HORIZONTAL
            val defaultInset = c.insets

            //set to the current wanted range
            val slider = JSlider(JSlider.HORIZONTAL, initialMin, initialMax, initialValue)
            val spinner = JSpinner(
                createAppropriateSpinnerModel(initialValue)
            )
            val lowBoundInformer = JLabel(initialMin.toString())
            val highBoundInformer = JLabel(initialMax.toString())

            //from bigdataviewer-core/src/main/java/bdv/ui/convertersetupeditor/BoundedRangePanel.java,
            //method updateBoundLabelFonts(), L283
            val labelFont = UIManager.getFont("Label.font")
            val font = Font(labelFont.name, labelFont.style, 10)
            lowBoundInformer.setFont(font)
            highBoundInformer.setFont(font)
            c.gridheight = 2
            c.gridy = 0
            c.weightx = 0.05
            c.gridx = 0
            intoThisComponent.add(spinner, c)
            c.weightx = 0.9
            c.gridx = 1
            c.insets = Insets(defaultInset.top, 5, defaultInset.bottom, 5)
            intoThisComponent.add(slider, c)
            c.insets = defaultInset
            c.gridheight = 1
            c.weightx = 0.05
            c.gridx = 2
            c.insets = Insets(defaultInset.top, 5, defaultInset.bottom, defaultInset.right)
            intoThisComponent.add(highBoundInformer, c)
            c.gridy = 1
            intoThisComponent.add(lowBoundInformer, c)
            return AdjustableBoundsSlider(slider, spinner, lowBoundInformer, highBoundInformer)
        }
    }
}