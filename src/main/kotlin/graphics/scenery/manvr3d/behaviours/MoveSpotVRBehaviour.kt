package graphics.scenery.manvr3d.behaviours

import graphics.scenery.controls.OpenXRHMD
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.behaviours.MultiButtonManager
import graphics.scenery.manvr3d.Manvr3dMain
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.toFloatArray
import graphics.scenery.utils.lazyLogger
import org.joml.Vector3f
import org.mastodon.collection.RefCollections
import org.scijava.ui.behaviour.DragBehaviour
import kotlin.time.TimeSource

class MoveSpotVRBehaviour(
    val manvr3d: Manvr3dMain,
    val buttonmanager: MultiButtonManager,
    val button: OpenXRHMD.OpenXRButton,
    val trackerRole: TrackerRole,
    val getTipPosition: () -> Vector3f
): DragBehaviour {

    val logger by lazyLogger()

    val adjacentEdges = mutableListOf<Int>()

    var currentControllerPos = Vector3f()

    private val graph = manvr3d.mastodon.model.graph

    /** Spots of the current timepoint that get moved in this drag. Snapshot of this is taken in init. */
    private val movingSpots = RefCollections.createRefList(graph.vertices())

    private var startTime = TimeSource.Monotonic.markNow()

    override fun init(x: Int, y: Int) {
        buttonmanager.pressButton(button, trackerRole)
        // Return when a different behavior was intended
        if (buttonmanager.isTwoHandedActive()) return
        startTime = TimeSource.Monotonic.markNow()

        movingSpots.clear()
        adjacentEdges.clear()
        manvr3d.bdvNotifier?.lockUpdates = true
        currentControllerPos = manvr3d.sciviewToMastodonCoords(getTipPosition())

        manvr3d.geometryHandler.collectSelectedSpots(manvr3d.currentTimepoint, movingSpots, adjacentEdges)
        if (movingSpots.isEmpty()) return
        logger.debug("Started dragging ${movingSpots.size} spot in VR")
    }

    override fun drag(x: Int, y: Int) {
        // Only perform the single hand behavior when no other grab button is currently active
        // to prevent simultaneous execution of behaviors
        if (buttonmanager.isTwoHandedActive()
            || manvr3d.bdvNotifier?.lockUpdates != true
            || movingSpots.isEmpty()
        ) {
            return
        }
        val newPos = manvr3d.sciviewToMastodonCoords(getTipPosition())
        manvr3d.geometryHandler.moveSpots(movingSpots, newPos - currentControllerPos, adjacentEdges)
        currentControllerPos = newPos
    }

    override fun end(x: Int, y: Int) {
        val wasLocked = manvr3d.bdvNotifier?.lockUpdates == true
        manvr3d.bdvNotifier?.lockUpdates = false
        buttonmanager.releaseButton(button, trackerRole)

        if (!buttonmanager.isTwoHandedActive() && wasLocked) {
            manvr3d.geometryHandler.showInstancedSpots(
                manvr3d.currentTimepoint,
                manvr3d.currentColorizer
            )
            adjacentEdges.clear()
            movingSpots.clear()
            manvr3d.fileLogger.incrementSpotMoved()
            manvr3d.recordVREditEvent("Spots moved", TimeSource.Monotonic.markNow() - startTime)
        }
    }

    companion object {

        /**
         * Convenience method for adding grab behaviour
         */
        fun createAndSet(
            manvr3d: Manvr3dMain,
            hmd: OpenXRHMD,
            buttons: List<OpenXRHMD.OpenXRButton>,
            controllerSide: List<TrackerRole>,
            buttonmanager: MultiButtonManager,
            getTipPosition: () -> Vector3f
        ) {
            hmd.onDeviceConnect { _, device, _ ->
                if (device.type == TrackedDeviceType.Controller) {
                    device.model?.let { controller ->
                        if (controllerSide.contains(device.role)) {
                            buttons.forEach { button ->
                                val name = "VRDrag:${hmd.trackingSystemName}:${device.role}:$button"
                                val grabBehaviour = MoveSpotVRBehaviour(
                                    manvr3d,
                                    buttonmanager,
                                    button,
                                    device.role,
                                    getTipPosition
                                )
                                buttonmanager.registerButtonConfig(button, device.role)
                                hmd.addBehaviour(name, grabBehaviour)
                                hmd.addKeyBinding(name, device.role, button)
                            }
                        }
                    }
                }
            }
        }
    }
}