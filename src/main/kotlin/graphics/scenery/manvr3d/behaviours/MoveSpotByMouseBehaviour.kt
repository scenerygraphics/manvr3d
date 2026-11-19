package graphics.scenery.manvr3d.behaviours

import graphics.scenery.Camera
import graphics.scenery.controls.behaviours.WithCameraDelegateBase
import graphics.scenery.manvr3d.Manvr3dMain
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import org.joml.Vector3f
import org.mastodon.collection.RefCollections
import org.mastodon.mamut.ProjectModel
import org.scijava.ui.behaviour.DragBehaviour

class MoveSpotByMouseBehaviour(
    camera: () -> Camera?,
    val mastodon: ProjectModel,
    val manvr3d: Manvr3dMain
): DragBehaviour, WithCameraDelegateBase(camera) {

    private val movingSpots = RefCollections.createRefList(mastodon.model.graph.vertices())
    private val edges = mutableListOf<Int>()
    private var distance = 0f
    private var lastHit = Vector3f()

    /** Point along the mouse ray at the grab depth, in Mastodon coords. */
    private fun hitAt(cam: Camera, x: Int, y: Int): Vector3f {
        val (rayStart, rayDir) = cam.screenPointToRay(x, y)
        return manvr3d.sciviewToMastodonCoords(rayStart + rayDir.normalize() * distance)
    }

    override fun init(x: Int, y: Int) {
        distance = 0f
        val cam = cam ?: return
        manvr3d.geometryHandler.collectSelectedSpots(manvr3d.currentTimepoint, movingSpots, edges)
        val anchor = movingSpots.firstOrNull()?.let { manvr3d.geometryHandler.findInstanceFromSpot(it) } ?: return
        distance = cam.spatial().position.distance(anchor.spatial().worldPosition())
        lastHit = hitAt(cam, x, y)
    }

    override fun drag(x: Int, y: Int) {
        if (distance <= 0)
            return

        val cam = cam ?: return
        val hit = hitAt(cam, x, y)
        manvr3d.geometryHandler.moveSpots(movingSpots, hit - lastHit, edges)
        lastHit = hit
    }

    override fun end(x: Int, y: Int) {
        if (distance <= 0f) return
        distance = 0f
        movingSpots.clear()
        edges.clear()
        manvr3d.geometryHandler.showInstancedSpots(manvr3d.currentTimepoint, manvr3d.currentColorizer)
    }
}