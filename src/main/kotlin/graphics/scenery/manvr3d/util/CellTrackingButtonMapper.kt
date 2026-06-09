package graphics.scenery.manvr3d.util

import graphics.scenery.controls.ButtonMapping
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.OpenVRHMD.OpenVRButton
import graphics.scenery.controls.OpenVRHMD.Manufacturer
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.VRInputMapper
import graphics.scenery.utils.lazyLogger
import org.joml.Quaternionf
import org.joml.Vector3f
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

    val selectColor = Vector3f(1f, 0.25f, 0.25f)
    val trackingColor = Vector3f(0.65f, 1f, 0.22f)
    val defaultColor = Vector3f(0.6f, 0.82f, 0.88f)

    init {
        // Oculus/Quest profile
        mapper.registerProfile(Manufacturer.Oculus, mapOf(
            EYE_TRACKING to ButtonMapping(
                TrackerRole.LeftHand, OpenVRButton.Trigger, "ET", color = trackingColor,
                offset = Vector3f(0.011f, -0.044f, 0.0501f),
                rotation = Quaternionf(-0.218f, 0.514f, -0.417f, 0.717f)
            ),
            CONTROLLER_TRACKING to ButtonMapping(
                TrackerRole.RightHand, OpenVRButton.Trigger, "CT", color = trackingColor,
                offset = Vector3f(-0.016f, -0.052f, 0.041f),
                rotation = Quaternionf(-0.281f, -0.490f, 0.293f, 0.771f)
            ),
            GRAB_WORLD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Side),
            GRAB_SPOT to ButtonMapping(TrackerRole.RightHand, OpenVRButton.Side),
            PLAYBACK to ButtonMapping(
                TrackerRole.LeftHand, OpenVRButton.Menu, "Play",
                offset = Vector3f(0.023f, 0.003f, 0.056f),
                rotation = Quaternionf(-0.866f, -0.034f, -0.063f, 0.494f)
            ),
            CYCLE_MENU to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.A, "Menu",
                offset = Vector3f(0.016f, 0.011f, 0.066f),
                rotation = Quaternionf(-0.866f, -0.034f, -0.063f, 0.494f)
                ),
            STEP_FWD to ButtonMapping(
                TrackerRole.RightHand, OpenVRButton.Left, "FW",
                offset = Vector3f(0.006f, 0.005f,0.047f),
                rotation = Quaternionf(-0.86f, 0.122f,0.075f, 0.491f)
            ),
            STEP_BWD to ButtonMapping(
                TrackerRole.RightHand, OpenVRButton.Right, "BW",
                offset = Vector3f(-0.016f, 0.01f,0.053f),
                rotation = Quaternionf(-0.86f, 0.122f,0.075f, 0.491f)
            ),
            RADIUS_INCREASE to ButtonMapping(
                TrackerRole.RightHand, OpenVRButton.Up, "R+",
                offset = Vector3f(-0.007f, 0f,0.041f),
                rotation = Quaternionf(-0.86f, 0.122f,0.075f, 0.491f)
            ),
            RADIUS_DECREASE to ButtonMapping(
                TrackerRole.RightHand, OpenVRButton.Down, "R-",
                offset = Vector3f(0.001f, 0.013f,0.055f),
                rotation = Quaternionf(-0.86f, 0.122f,0.075f, 0.491f)
            ),
            ADD_DELETE_RESET to ButtonMapping(
                TrackerRole.RightHand, OpenVRButton.Menu, "Add",
                offset = Vector3f(-0.041f, 0.003f,0.059f),
                rotation = Quaternionf(-0.893f, 0.072f,0.087f, 0.437f)
            ),
            SELECT to ButtonMapping(
                TrackerRole.RightHand, OpenVRButton.A, "Sel", color = selectColor,
                offset = Vector3f(-0.03f, 0.013f,0.071f),
                rotation = Quaternionf(-0.894f, 0.085f,0.095f, 0.43f)
            ),
            MOVE_FORWARD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Up, "Move",
                offset = Vector3f(-0.011f, 0.005f, 0.044f),
                rotation = Quaternionf(-0.866f, -0.034f, -0.063f, 0.494f)
            ),
            MOVE_BACKWARD to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Down),
            MOVE_LEFT to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Left),
            MOVE_RIGHT to ButtonMapping(TrackerRole.LeftHand, OpenVRButton.Right),

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
