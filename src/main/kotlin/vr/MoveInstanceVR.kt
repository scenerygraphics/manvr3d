package vr

import Manvr3dMain
import graphics.scenery.Scene
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.behaviours.MultiButtonManager
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.lazyLogger
import org.joml.Vector3f
import org.scijava.ui.behaviour.DragBehaviour

class MoveInstanceVR(
    val manvr3d: Manvr3dMain,
    val buttonmanager: MultiButtonManager,
    val button: OpenVRHMD.OpenVRButton,
    val trackerRole: TrackerRole,
    val getTipPosition: () -> Vector3f
): DragBehaviour {

    val logger by lazyLogger()

    val adjacentEdges = mutableListOf<Int>()

    var currentControllerPos = Vector3f()

    override fun init(x: Int, y: Int) {
        buttonmanager.pressButton(button, trackerRole)
        // Return when a different behavior was intended
        if (buttonmanager.isTwoHandedActive()) return

        val pos = getTipPosition()
        // Guard against empty selection
        if (manvr3d.mastodon.selectionModel.selectedVertices == null
            || manvr3d.selectedSpotInstances.isEmpty()) {
            manvr3d.selectedSpotInstances.clear()
            return
        }

        // Guard against spots not being in the current timepoint
        val invalidSpot = manvr3d.selectedSpotInstances.any { inst ->
            val spot = manvr3d.geometryHandler.findSpotFromInstance(inst)
            spot?.timepoint != manvr3d.volumeNode.currentTimepoint
        }
        if (invalidSpot) {
            manvr3d.selectedSpotInstances.clear()
            logger.warn("Tried to move a spot outside the current timepoint. Aborting.")
            return
        }

        manvr3d.bdvNotifier?.lockUpdates = true
        currentControllerPos = manvr3d.sciviewToMastodonCoords(pos)

        manvr3d.selectedSpotInstances.forEach { inst ->
            logger.debug("selected spot instance is $inst")
            val spot = manvr3d.geometryHandler.findSpotFromInstance(inst)
            spot?.let { s ->
                adjacentEdges.addAll(s.edges().map { it.internalPoolIndex })
                logger.debug("Moving edges $manvr3d.adjacentEdges for spot ${spot.internalPoolIndex}.")
            }
        }
    }

    override fun drag(x: Int, y: Int) {
        // Only perform the single hand behavior when no other grab button is currently active
        // to prevent simultaneous execution of behaviors
        if (buttonmanager.isTwoHandedActive()
            || manvr3d.bdvNotifier?.lockUpdates != true
            || manvr3d.selectedSpotInstances.isEmpty()
        ) {
            return
        }
        val pos = getTipPosition()
        val newPos = manvr3d.sciviewToMastodonCoords(pos)
        val movement = newPos - currentControllerPos
        manvr3d.selectedSpotInstances.forEach {
            it.spatial { position += movement }
            manvr3d.geometryHandler.moveSpotInBDV(it, movement)
        }
        manvr3d.geometryHandler.mainSpotInstance?.updateInstanceBuffers()
        manvr3d.geometryHandler.updateLinkTransforms(adjacentEdges)
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
        }
    }

    companion object {

        /**
         * Convenience method for adding grab behaviour
         */
        fun createAndSet(
            manvr3d: Manvr3dMain,
            hmd: OpenVRHMD,
            buttons: List<OpenVRHMD.OpenVRButton>,
            controllerSide: List<TrackerRole>,
            buttonmanager: MultiButtonManager,
            getTipPosition: () -> Vector3f
        ) {
            hmd.events.onDeviceConnect.add { _, device, _ ->
                if (device.type == TrackedDeviceType.Controller) {
                    device.model?.let { controller ->
                        if (controllerSide.contains(device.role)) {
                            buttons.forEach { button ->
                                val name = "VRDrag:${hmd.trackingSystemName}:${device.role}:$button"
                                val grabBehaviour = MoveInstanceVR(
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