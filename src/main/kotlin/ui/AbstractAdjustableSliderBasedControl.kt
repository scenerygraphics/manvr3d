package ui

import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.MouseMotionListener
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import kotlin.math.max
import kotlin.math.min

abstract class AbstractAdjustableSliderBasedControl(// ================================= initialization =================================
    val slider: JSlider,
    associatedValueSpinner: JSpinner,
    protected val lowBoundInfo: JLabel,
    protected val highBoundInfo: JLabel
) {
    /** provide here own mouse movement to boundary change "scaler",
     * this is intentionally available as of per-slider basis   */
    var boundarySetter = BOUNDARY_SETTER_CUBE_FUN

    // ================================= helper builders =================================
    fun interface BoundaryValuesProvider {
        fun boundaryDeltaOnThisMouseMove(mouseDeltaInPx: Int): Int
    }

    val value: Int
        /** only a shortcut to getSlider().getValue()  */
        get() = slider.value

    // ================================= execution: managing slider thumbs =================================
    //only for derived classes...
    protected var originalSliderValue = -1 //aka before-dragging-value
    protected open fun storeSliderThumbsPositions() {
        originalSliderValue = slider.value
    }

    protected open fun fixupSliderThumbsPositions() {
        //make sure that the slider is not changing while adjusting its boundary,
        //which may not be always possible (as the boundary is allowed to move
        //irrespective of what the slider value was)
        if (originalSliderValue < slider.minimum) slider.setValue(slider.minimum) else if (originalSliderValue > slider.maximum) slider.setValue(
            slider.maximum
        ) else slider.setValue(originalSliderValue)
    }

    protected open fun didSliderThumbsChangedPositions(): Boolean {
        return slider.value != originalSliderValue
    }

    // ================================= execution: internal state =================================
    private var isControlKeyPressed = false
    private var isMouseLBpressed = false

    //meant originally for derived classes...
    var isInControllingMode = false
        private set
    private var initialMousePosition = 0
    private var initialBoundaryValue = 0
    private var isMinBoundaryControlled = false

    // ================================= execution: events handling =================================
    protected inner class EventHandler : KeyListener, MouseListener, MouseMotionListener {
        override fun keyPressed(keyEvent: KeyEvent) {
            if (keyEvent.keyCode == CONTROL_KEY_keycode) {
                isControlKeyPressed = true
                if (isMouseLBpressed) isInControllingMode = true
            }
        }

        override fun keyReleased(keyEvent: KeyEvent) {
            if (keyEvent.keyCode == CONTROL_KEY_keycode) {
                isControlKeyPressed = false
                if (isInControllingMode) tellListenersThatWeEndedAdjustingMode()
                isInControllingMode = false
            }
        }

        override fun mousePressed(mouseEvent: MouseEvent) {
            if (mouseEvent.button == MOUSE_BUTTON_code) {
                isMouseLBpressed = true
                if (isControlKeyPressed) {
                    isInControllingMode = true

                    //store the initial state now--at the beginning of the dragging
                    initialMousePosition = mouseEvent.xOnScreen
                    isMinBoundaryControlled = mouseEvent.x.toFloat() / slider.width.toFloat() < 0.5f
                    initialBoundaryValue = if (isMinBoundaryControlled) slider.minimum else slider.maximum
                    storeSliderThumbsPositions()
                }
            }
        }

        override fun mouseReleased(mouseEvent: MouseEvent) {
            if (mouseEvent.button == MOUSE_BUTTON_code) {
                isMouseLBpressed = false
                if (isInControllingMode) tellListenersThatWeEndedAdjustingMode()
                isInControllingMode = false
            }
        }

        override fun mouseDragged(mouseEvent: MouseEvent) {
            if (isInControllingMode) {
                val deltaMove = mouseEvent.xOnScreen - initialMousePosition
                var newSliderValue = initialBoundaryValue + boundarySetter.boundaryDeltaOnThisMouseMove(deltaMove)
                if (isMinBoundaryControlled) {
                    //make sure the value is within the min model limits,
                    newSliderValue = max(
                        MIN_BOUND_LIMIT.toDouble(),
                        min(
                            newSliderValue.toDouble(),
                            MAX_BOUND_LIMIT.toDouble()
                        )
                    ).toInt()
                    //and set min only if it is not beyond (greater than) the max boundary
                    if (newSliderValue < slider.maximum) {
                        slider.setMinimum(newSliderValue)
                        lowBoundInfo.setText(newSliderValue.toString())
                    }
                } else {
                    //right part
                    //make sure the value is within the max model limits,
                    newSliderValue = max(
                        MIN_BOUND_LIMIT.toDouble(),
                        min(
                            newSliderValue.toDouble(),
                            MAX_BOUND_LIMIT.toDouble()
                        )
                    ).toInt()
                    //and set max only if it is not beyond (lesser than) the min boundary
                    if (newSliderValue > slider.minimum) {
                        slider.setMaximum(newSliderValue)
                        highBoundInfo.setText(newSliderValue.toString())
                    }
                }
                fixupSliderThumbsPositions()
            }
        }

        override fun mouseEntered(mouseEvent: MouseEvent) {
            //during the mouse dragging, we might have gotten out of the slider area;
            //when outside, the keyboard and mouse buttons change might have changed but
            //this object is now aware of it (as its listeners couldn't be triggered);
            //
            //now, when the mouse pointer is coming back, we have to reset the statuses
            isControlKeyPressed = mouseEvent.getModifiersEx() and MouseEvent.CTRL_DOWN_MASK > 0
            isMouseLBpressed = mouseEvent.getModifiersEx() and MouseEvent.BUTTON1_DOWN_MASK > 0
            val wasInControllingMode = isInControllingMode
            isInControllingMode = isControlKeyPressed && isMouseLBpressed
            if (wasInControllingMode && !isInControllingMode) tellListenersThatWeEndedAdjustingMode()
        }

        override fun keyTyped(keyEvent: KeyEvent) { /* intentionally empty */
        }

        override fun mouseClicked(mouseEvent: MouseEvent) { /* intentionally empty */
        }

        override fun mouseExited(mouseEvent: MouseEvent) { /* intentionally empty */
        }

        override fun mouseMoved(mouseEvent: MouseEvent) { /* intentionally empty */
        }
    }

    // ================================= execution: listeners =================================
    protected val listeners: MutableList<ChangeListener> = ArrayList(10)

    init {

        //add tooltip but only if there's none already
        if (slider.toolTipText == null) {
            slider.setToolTipText("Press and hold both CTRL and left-mouse-button while dragging the mouse horizontally to adjust sliding range.")
        }

        //listeners setup: setting the slider from the associated spinner
        val m = associatedValueSpinner.model
        require(m is SpinnerNumberModel) { "The provided spinner is expected to be of the type SpinnerNumberModel." }
        val nm = m //NB: safe to cast...
        nm.addChangeListener { l: ChangeEvent? ->
            var value = nm.value as Int
            value = max(slider.minimum.toDouble(), min(value.toDouble(), slider.maximum.toDouble()))
                .toInt()
            slider.setValue(value)
            nm.setValue(value) //basically, assures that the spinner is also not outside the current bounds
        }

        //listeners setup: forwarder to the associated spinner and also
        //to client listeners (for which it triggers only on truly relevant slider changes)
        slider.addChangeListener { event: ChangeEvent? ->
            nm.setValue(slider.value)
            if (!isInControllingMode) tellListenersThatSliderHasChanged(event)
        }

        //listeners setup: managing slider's limits
        val handler: EventHandler = EventHandler()
        slider.addKeyListener(handler)
        slider.addMouseListener(handler)
        slider.addMouseMotionListener(handler)
    }

    fun addChangeListener(listener: ChangeListener) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: ChangeListener) {
        listeners.remove(listener)
    }

    protected fun tellListenersThatSliderHasChanged(event: ChangeEvent?) {
        listeners.forEach { listener: ChangeListener -> listener.stateChanged(event) }
    }

    protected fun tellListenersThatWeEndedAdjustingMode() {
        //...but only when we really have changed the value before and after the adjustment
        if (didSliderThumbsChangedPositions()) {
            tellListenersThatSliderHasChanged(ChangeEvent(slider))
        }
    }

    companion object {
        /** represents the Control key, which is changeable and shared among all such controls
         * (to warrant all are controlled the same way)  */
        var CONTROL_KEY_keycode = 17

        /** represents the left mouse button, which is changeable and shared among all such controls
         * (to warrant all are controlled the same way)  */
        var MOUSE_BUTTON_code = 1
        val BOUNDARY_SETTER_IDENTITY_FUN: BoundaryValuesProvider =
            BoundaryValuesProvider { mouseDeltaInPx: Int -> mouseDeltaInPx }
        val BOUNDARY_SETTER_SQUARE_FUN: BoundaryValuesProvider = BoundaryValuesProvider { mouseDeltaInPx: Int ->
            var d = mouseDeltaInPx.toFloat() / 4f
            d *= d
            if (mouseDeltaInPx > 0) d.toInt() else -d.toInt()
        }
        val BOUNDARY_SETTER_CUBE_FUN: BoundaryValuesProvider = BoundaryValuesProvider { mouseDeltaInPx: Int ->
            var d = mouseDeltaInPx.toFloat() / 15f
            d *= d * d
            d.toInt()
        }

        @JvmOverloads
        fun createAppropriateSpinnerModel(
            withThisCurrentValue: Int,
            withThisStep: Int = 50
        ): SpinnerNumberModel {
            return SpinnerNumberModel(withThisCurrentValue, MIN_BOUND_LIMIT, MAX_BOUND_LIMIT, withThisStep)
        }

        //internal shortcuts
        const val MIN_BOUND_LIMIT = 0
        const val MAX_BOUND_LIMIT = 65535
    }
}