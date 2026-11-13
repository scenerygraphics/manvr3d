package graphics.scenery.manvr3d.behaviours

import graphics.scenery.manvr3d.vr.CellTrackingBase
import graphics.scenery.utils.lazyLogger
import org.scijava.ui.behaviour.DragBehaviour

/** Several behaviors mapped per default to the right menu button. If controller tracking is active,
 * end the tracking. If not, clicking will either create or delete a spot, depending on whether the user
 * previously selected a spot. Holding the button for more than 0.5s deletes the whole connected branch. */
class AddDeleteResetBehavior(val context: CellTrackingBase) : DragBehaviour {

    val logger by lazyLogger()

    var start = System.currentTimeMillis()
    var wasExecuted = false
    // Check whether we are interacting with a menu -> if true, we don't interact
    private var wantInteract = false

    override fun init(x: Int, y: Int) {
        wantInteract = !context.cursor.showPointer
        if (!wantInteract) return
        start = System.currentTimeMillis()
        wasExecuted = false
    }
    override fun drag(x: Int, y: Int) {
        if (!wantInteract) return
        // If the button was pressed for more than 0.5s, delete the branch
        if (System.currentTimeMillis() - start > 500 && !wasExecuted) {
            val p = context.cursor.getPosition()
            context.manvr3d.geometryHandler.addOrRemoveSpotsAndEdges(
                context.volume.currentTimepoint,
                p,
                context.cursor.radius,
                true,
                true)
            wasExecuted = true
        }
    }
    override fun end(x: Int, y: Int) {
        if (!wantInteract) return
        if (context.controllerTrackingActive) {
            context.endControllerTracking()
        } else {
            val p = context.cursor.getPosition()
            logger.debug("Got cursor position: $p")
            if (!wasExecuted) {
                context.geometryHandler.addOrRemoveSpotsAndEdges(
                    context.volume.currentTimepoint,
                    p,
                    context.cursor.radius,
                    false,
                    true)
            }
        }
    }
}