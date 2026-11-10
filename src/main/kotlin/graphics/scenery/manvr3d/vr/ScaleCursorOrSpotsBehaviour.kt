package graphics.scenery.manvr3d.vr

import graphics.scenery.InstancedNode
import graphics.scenery.manvr3d.Manvr3dMain
import org.scijava.ui.behaviour.DragBehaviour

/** Behaviour that either scales the VR cursor or the selected spots. */
class ScaleCursorOrSpotsBehaviour(val manvr3d: Manvr3dMain, val factor: Float): DragBehaviour {
    var selection = listOf<InstancedNode.Instance>()
    override fun init(p0: Int, p1: Int) {
        selection = manvr3d.selectedSpotInstances.toList()
    }

    override fun drag(p0: Int, p1: Int) {
        if (selection.isNotEmpty()) {
            manvr3d.geometryHandler.changeSpotRadius(selection, factor, false)
        } else {
            // Make cursor movement a little faster than  changing the spot radii
            manvr3d.vrTracking.cursor.scaleByFactor(factor * factor)
        }
    }

    override fun end(p0: Int, p1: Int) {
        manvr3d.geometryHandler.changeSpotRadius(selection, factor, true)
    }
}