package graphics.scenery.manvr3d.util

import graphics.scenery.controls.ButtonMapping
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.OpenVRHMD.OpenVRButton
import graphics.scenery.controls.OpenVRHMD.Manufacturer
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.VRInputMapper
import graphics.scenery.utils.lazyLogger
import org.scijava.ui.behaviour.Behaviour

/** This input mapper provides several preconfigured profiles of manvr3d's controls for different VR controller layouts.
 * The actual behavior binding is done in [mapper].
 * Note that for Quest-like layouts, the lower button always equals [OpenVRButton.A]
 * and the upper button is always [OpenVRButton.Menu]. */
object CellTrackingButtonMapper {
    val logger by lazyLogger()

    val mapper = VRInputMapper()

    // Behavior name constants
    const val EYE_TRACKING = "eyeTracking"
    const val CONTROLLER_TRACKING = "controllerTracking"
    const val GRAB_WORLD = "grabWorld"
    const val GRAB_SPOT = "grabSpot"
    const val PLAYBACK = "playback"
    const val CYCLE_MENU = "cycleMenu"
    const val STEP_FWD = "stepFwd"
    const val STEP_BWD = "stepBwd"
    const val ADD_DELETE_RESET = "addDeleteReset"
    const val SELECT = "select"
    const val RADIUS_INCREASE = "radiusIncrease"
    const val RADIUS_DECREASE = "radiusDecrease"
    const val MOVE_FORWARD = "moveForward"
    const val MOVE_BACKWARD = "moveBackward"
    const val MOVE_LEFT = "moveLeft"
    const val MOVE_RIGHT = "moveRight"

    init {
        // Oculus/Quest profile
        mapper.registerProfile(Manufacturer.Oculus, mapOf(
            EYE_TRACKING to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Trigger),
            CONTROLLER_TRACKING to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Trigger),
            GRAB_WORLD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Side),
            GRAB_SPOT to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Side),
            PLAYBACK to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.A),
            CYCLE_MENU to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Menu),
            STEP_FWD to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Left),
            STEP_BWD to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Right),
            ADD_DELETE_RESET to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Menu),
            SELECT to ButtonMapping(TrackerRole.RightHand, OpenVRButton.A),
            MOVE_FORWARD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Up),
            MOVE_BACKWARD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Down),
            MOVE_LEFT to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Left),
            MOVE_RIGHT to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Right),
            RADIUS_INCREASE to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Up),
            RADIUS_DECREASE to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Down)
        ))

        // HTC Vive profile
        mapper.registerProfile(Manufacturer.HTC, mapOf(
            EYE_TRACKING to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Trigger),
            CONTROLLER_TRACKING to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Trigger),
            GRAB_WORLD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Side),
            GRAB_SPOT to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Side),
            PLAYBACK to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Menu),
            CYCLE_MENU to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Menu),
            STEP_FWD to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Left),
            STEP_BWD to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Right),
            ADD_DELETE_RESET to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Up),
            SELECT to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Down),
            MOVE_FORWARD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Up),
            MOVE_BACKWARD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Down),
            MOVE_LEFT to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Left),
            MOVE_RIGHT to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Right)
        ))
    }

    fun loadProfileForHMD(hmd: OpenVRHMD): Boolean {
        return mapper.loadProfileForHMD(hmd)
    }
}
