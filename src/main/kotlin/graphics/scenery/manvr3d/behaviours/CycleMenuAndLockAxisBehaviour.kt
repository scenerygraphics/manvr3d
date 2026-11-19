package graphics.scenery.manvr3d.behaviours

import graphics.scenery.controls.OpenXRHMD
import graphics.scenery.controls.TrackerRole
import graphics.scenery.manvr3d.vr.CellTrackingBase
import graphics.scenery.utils.lazyLogger
import org.scijava.ui.behaviour.DragBehaviour

/** Behavior that handles double assignment of the left A key which is used to cycle menus as well as
 * reset the rotation when pressed while the [graphics.scenery.controls.behaviours.VRTwoHandNodeTransform] is active. */
class CycleMenuAndLockAxisBehaviour(
    val context: CellTrackingBase,
    val button: OpenXRHMD.OpenXRButton,
    val role: TrackerRole
) : DragBehaviour {

    val logger by lazyLogger()

    fun registerConfig() {
        logger.debug("Setting up keybinds for CycleMenuAndLockAxisBehavior")
        context.resetRotationBtnManager.registerButtonConfig(button, role)
    }
    override fun init(x: Int, y: Int) {
        context.resetRotationBtnManager.pressButton(button, role)
        if (!context.resetRotationBtnManager.isTwoHandedActive()) {
            context.leftWristMenu.cycleNext()
        }
    }
    override fun drag(x: Int, y: Int) {}
    override fun end(x: Int, y: Int) {
        context.resetRotationBtnManager.releaseButton(button, role)
    }
}