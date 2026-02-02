package util

import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackerRole
import graphics.scenery.utils.lazyLogger
import org.scijava.ui.behaviour.Behaviour

/** This input mapping manager provides several preconfigured profiles for different VR controller layouts.
 * The active profile is stored in [currentProfile].
 * To change profile, call [loadProfile] with the new [graphics.scenery.controls.OpenVRHMD.Manufacturer] type.
 * Note that for Quest-like layouts, the lower button always equals [graphics.scenery.controls.OpenVRHMD.OpenVRButton.A]
 * and the upper button is always [graphics.scenery.controls.OpenVRHMD.OpenVRButton.Menu]. */
object CellTrackingButtonMapper {

    var eyeTracking: ButtonConfig? = null
    var controllerTracking: ButtonConfig? = null
    var grabObserver: ButtonConfig? = null
    var grabSpot: ButtonConfig? = null
    var playback: ButtonConfig? = null
    var cycleMenu: ButtonConfig? = null
    var faster: ButtonConfig? = null
    var slower: ButtonConfig? = null
    var stepFwd: ButtonConfig? = null
    var stepBwd: ButtonConfig? = null
    var addDeleteReset: ButtonConfig? = null
    var select: ButtonConfig? = null
    var move_forward_fast: ButtonConfig? = null
    var move_back_fast: ButtonConfig? = null
    var move_left_fast: ButtonConfig? = null
    var move_right_fast: ButtonConfig? = null
    var radiusIncrease: ButtonConfig? = null
    var radiusDecrease: ButtonConfig? = null

    private var currentProfile: OpenVRHMD.Manufacturer = OpenVRHMD.Manufacturer.Oculus

    val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    private val profiles = mapOf(
        OpenVRHMD.Manufacturer.HTC to mapOf(
            "eyeTracking" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Trigger),
            "controllerTracking" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Trigger),
            "grabObserver" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Side),
            "grabSpot" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Side),
            "playback" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Menu),
            "cycleMenu" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Menu),
            "faster" to null,
            "slower" to null,
            "radiusIncrease" to null,
            "radiusDecrease" to null,
            "stepFwd" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Left),
            "stepBwd" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Right),
            "addDeleteReset" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Up),
            "select" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Down),
            "move_forward_fast" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Up),
            "move_back_fast" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Down),
            "move_left_fast" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Left),
            "move_right_fast" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Right),
        ),

        OpenVRHMD.Manufacturer.Oculus to mapOf(
            "eyeTracking" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Trigger),
            "controllerTracking" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Trigger),
            "grabObserver" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Side),
            "grabSpot" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Side),
            "playback" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.A),
            "cycleMenu" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Menu),
//            "faster" to ButtonConfig(TrackerRole.RightHand, OpenVRButton.Up),
//            "slower" to ButtonConfig(TrackerRole.RightHand, OpenVRButton.Down),
            "stepFwd" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Left),
            "stepBwd" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Right),
            "addDeleteReset" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Menu),
            "select" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.A),
            "move_forward_fast" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Up),
            "move_back_fast" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Down),
            "move_left_fast" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Left),
            "move_right_fast" to ButtonConfig(TrackerRole.LeftHand, OpenVRHMD.OpenVRButton.Right),
            "radiusIncrease" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Up),
            "radiusDecrease" to ButtonConfig(TrackerRole.RightHand, OpenVRHMD.OpenVRButton.Down),
        )
    )

    init {
        loadProfile(OpenVRHMD.Manufacturer.Oculus)
    }

    /** Load the current profile's button mapping */
    fun loadProfile(p: OpenVRHMD.Manufacturer): Boolean {
        currentProfile = p
        val profile = profiles[currentProfile] ?: return false
        eyeTracking = profile["eyeTracking"]
        controllerTracking = profile["controllerTracking"]
        grabObserver = profile["grabObserver"]
        grabSpot = profile["grabSpot"]
        playback = profile["playback"]
        cycleMenu = profile["cycleMenu"]
        faster = profile["faster"]
        slower = profile["slower"]
        stepFwd = profile["stepFwd"]
        stepBwd = profile["stepBwd"]
        addDeleteReset = profile["addDeleteReset"]
        select = profile["select"]
        move_forward_fast = profile["move_forward_fast"]
        move_back_fast = profile["move_back_fast"]
        move_left_fast = profile["move_left_fast"]
        move_right_fast = profile["move_right_fast"]
        radiusIncrease = profile["radiusIncrease"]
        radiusDecrease = profile["radiusDecrease"]
        return true
    }

    fun getCurrentMapping(): Map<String, ButtonConfig?>?{
        return profiles[currentProfile]
    }

    fun getMapFromName(name: String): ButtonConfig? {
        return when (name) {
            "eyeTracking" -> eyeTracking
            "controllerTracking" -> controllerTracking
            "grabObserver" -> grabObserver
            "grabSpot" -> grabSpot
            "playback" -> playback
            "cycleMenu" -> cycleMenu
            "faster" -> faster
            "slower" -> slower
            "stepFwd" -> stepFwd
            "stepBwd" -> stepBwd
            "addDeleteReset" -> addDeleteReset
            "select" -> select
            "move_forward_fast" -> move_forward_fast
            "move_back_fast" -> move_back_fast
            "move_left_fast" -> move_left_fast
            "move_right_fast" -> move_right_fast
            "radiusIncrease" -> radiusIncrease
            "radiusDecrease" -> radiusDecrease
            else -> null
        }
    }

    /** Sets a keybinding and behavior for an [hmd], using the [name] string, a [behavior]
     * and the keybinding if found in the current profile. */
    fun setKeyBindAndBehavior(hmd: OpenVRHMD, name: String, behavior: Behaviour) {
        val config = getMapFromName(name)
        if (config != null) {
            hmd.addKeyBinding(name, config.r, config.b)
            hmd.addBehaviour(name, behavior)
            logger.debug("Added behavior $behavior to ${config.r}, ${config.b}.")
        } else {
            logger.warn("No valid button mapping found for key '$name' in current profile!")
        }
    }
}


/** Combines the [TrackerRole] ([r]) and the [OpenVRHMD.OpenVRButton] ([b]) into a single configuration. */
data class ButtonConfig (
    /** The [TrackerRole] of this button configuration. */
    var r: TrackerRole,
    /** The [OpenVRHMD.OpenVRButton] of this button configuration. */
    var b: OpenVRHMD.OpenVRButton
)