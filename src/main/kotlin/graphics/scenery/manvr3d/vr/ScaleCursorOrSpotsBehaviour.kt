package graphics.scenery.manvr3d.vr

import graphics.scenery.InstancedNode
import graphics.scenery.manvr3d.Manvr3dMain
import org.mastodon.collection.RefCollections
import org.scijava.ui.behaviour.DragBehaviour

/** Behaviour that either scales the VR cursor or the selected spots. */
class ScaleCursorOrSpotsBehaviour(val manvr3d: Manvr3dMain, val factor: Float): DragBehaviour {
    private val spots = RefCollections.createRefList(manvr3d.mastodon.model.graph.vertices())

    override fun init(p0: Int, p1: Int) {
        manvr3d.geometryHandler.collectSelectedSpots(manvr3d.currentTimepoint, spots)
    }

    override fun drag(p0: Int, p1: Int) {
        if (spots.isNotEmpty()) {
            manvr3d.geometryHandler.scaleSpots(spots, factor)
        } else {
            // Make cursor movement a little faster than  changing the spot radii
            manvr3d.vrTracking.cursor.scaleByFactor(factor * factor)
        }
    }

    override fun end(p0: Int, p1: Int) {
        if (spots.isNotEmpty()) manvr3d.geometryHandler.commitSpotChanges()
        spots.clear()
    }
}