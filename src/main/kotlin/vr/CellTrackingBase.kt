package vr

import Manvr3dMain
import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.controls.*
import graphics.scenery.controls.behaviours.AnalogInputWrapper
import graphics.scenery.controls.behaviours.ConfirmableClickBehaviour
import graphics.scenery.controls.behaviours.VRTouch
import graphics.scenery.primitives.Cylinder
import graphics.scenery.primitives.TextBoard
import graphics.scenery.ui.*
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.*
import graphics.scenery.utils.lazyLogger
import graphics.scenery.volumes.RAIVolume
import graphics.scenery.volumes.Volume
import org.joml.*
import org.mastodon.mamut.model.Spot
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.ui.behaviour.DragBehaviour
import sc.iview.SciView
import analysis.HedgehogAnalysis.SpineGraphVertex
import graphics.scenery.controls.behaviours.MultiButtonManager
import graphics.scenery.controls.behaviours.VR2HandNodeTransform
import graphics.scenery.controls.behaviours.VRGrabTheWorld
import graphics.scenery.utils.TimepointObservable
import org.checkerframework.checker.units.qual.m
import util.CellTrackingButtonMapper
import util.GeometryHandler
import util.SpineMetadata
import java.io.BufferedWriter
import java.io.FileWriter
import java.nio.file.Path
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Base class for different VR cell tracking purposes. It includes functionality to add spines and edgehogs,
 * as used by [EyeTracking], and registers controller bindings via [inputSetup].
 * @param sciview The [SciView] instance to use
 * @param manvr3d An instance of [Manvr3dMain], used for interactions with the Mastodon data structure
 * @param geometryHandler The [GeometryHandler] that handles spheres (cells) and cylinders (track segments)
 * @param resolutionScale Allows rendering the VR window at higher or lower resolution than natively supported
 */
open class CellTrackingBase(
    open var sciview: SciView,
    open var manvr3d: Manvr3dMain,
    open var geometryHandler: GeometryHandler,
    val resolutionScale: Float = 1f
): TimepointObservable() {
    val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    lateinit var sessionId: String
    lateinit var sessionDirectory: Path

    lateinit var hmd: OpenVRHMD

    val hedgehogs = Mesh()
    val hedgehogIds = AtomicInteger(0)
    lateinit var volume: Volume

    val referenceTarget = Icosphere(0.004f, 2)

    @Volatile var eyeTrackingActive = false
    var playing = false
    var direction = PlaybackDirection.Backward
    var volumesPerSecond = 6f
    var skipToNext = false
    var skipToPrevious = false

    var volumeScaleFactor = 1.0f

    private lateinit var lightTetrahedron: List<PointLight>

    val volumeTimepointWidget = TextBoard()

    /** determines whether the volume and hedgehogs should keep listening for updates or not */
    var cellTrackingActive: Boolean = false

    enum class HedgehogVisibility { Hidden, PerTimePoint, Visible }

    enum class PlaybackDirection { Forward, Backward }

    enum class ElephantMode { StageSpots, TrainAll, PredictTP, PredictAll, NNLinking }

    var hedgehogVisibility = HedgehogVisibility.Hidden
    var trackVisibility = true
    var spotVisibility = true

    var leftVRController: TrackedDevice? = null
    var rightVRController: TrackedDevice? = null

    val cursor = CursorTool()
    val cursorSelectColor = Vector3f(1f, 0.25f, 0.25f)
    val cursorTrackingColor = Vector3f(0.65f, 1f, 0.22f)

    lateinit var leftWristMenu: MultiWristMenu

    var enableTrackingPreview = true

    val grabButtonManager = MultiButtonManager()
    val resetRotationBtnManager = MultiButtonManager()

    val buttonMapper = CellTrackingButtonMapper

    open fun run() {
        sciview.toggleVRRendering(resolutionScale = resolutionScale)
        hmd = sciview.hub.getWorkingHMD() as? OpenVRHMD ?: throw IllegalStateException("Could not find headset")

        // Try to load the correct button mapping corresponding to the controller layout
        val isProfileLoaded = buttonMapper.loadProfile(hmd.manufacturer)
        if (!isProfileLoaded) {
            throw IllegalStateException("Could not load profile, headset type unknown!")
        }
        val shell = Box(Vector3f(20.0f, 20.0f, 20.0f), insideNormals = true)
        shell.ifMaterial {
            cullingMode = Material.CullingMode.Front
            diffuse = Vector3f(0.4f, 0.4f, 0.4f)
        }

        shell.spatial().position = Vector3f(0.0f, 0.0f, 0.0f)
        shell.name = "Shell"
        sciview.addNode(shell)

        lightTetrahedron = Light.createLightTetrahedron<PointLight>(
            Vector3f(0.0f, 0.0f, 0.0f),
            spread = 5.0f,
            radius = 15.0f,
            intensity = 5.0f
        )
        lightTetrahedron.forEach { sciview.addNode(it) }

        val volumeNodes = sciview.findNodes { node -> Volume::class.java.isAssignableFrom(node.javaClass) }

        val v = (volumeNodes.firstOrNull() as? Volume)
        if(v == null) {
            logger.warn("No volume found, bailing")
            return
        } else {
            logger.info("found ${volumeNodes.size} volume nodes. Using the first one: ${volumeNodes.first()}")
            volume = v
        }

        logger.info("Adding onDeviceConnect handlers")
        hmd.events.onDeviceConnect.add { hmd, device, timestamp ->
            logger.info("onDeviceConnect called, cam=${sciview.camera}")
            if (device.type == TrackedDeviceType.Controller) {
                logger.info("Got device ${device.name} at $timestamp")
                device.model?.let { hmd.attachToNode(device, it, sciview.camera) }
                when (device.role) {
                    TrackerRole.Invalid -> {}
                    TrackerRole.LeftHand -> leftVRController = device
                    TrackerRole.RightHand -> rightVRController = device
                }
                if (device.role == TrackerRole.RightHand) {
                    attachCursorAndTimepointWidget()
                    device.model?.name = "rightHand"
                } else if (device.role == TrackerRole.LeftHand) {
                    device.model?.let {
                        it.name = "leftHand"
                        leftWristMenu = MultiWristMenu(it,
                            columnBasePosition = Vector3f(0.03f, 0f, 0.1f),
                            columnRotation = Quaternionf().rotationXYZ(-1.2f, 1.7f, 0f)
                        )
                        setupElephantMenu()
                        setupGeneralMenu()
                        leftWristMenu.hideAll()
                    }

                    logger.info("Set up navigation and editing controls.")
                }
            }
        }
        inputSetup()

        cellTrackingActive = true
        manvr3d.rebuildGeometry()
        launchUpdaterThread()
    }

    var controllerTrackingActive = false

    /** Intermediate storage for a single track created with the controllers.
     * Once tracking is finished, this track is sent to Mastodon. */
    var controllerTrackList = mutableListOf<Vector3f>()
    var startWithExistingSpot: Spot? = null

    /** This lambda is called every time the user performs a click with controller-based tracking. */
    val trackCellsWithController = ClickBehaviour { _, _ ->
        if (!controllerTrackingActive) {
            controllerTrackingActive = true
            cursor.setColor(cursorTrackingColor)
            // we dont want animation, because we track step by step
            playing = false
            // Assume the user didn't click on an existing spot to start the track.
            startWithExistingSpot = null
        }
        // play the volume backwards, step by step, so cell split events can simply be turned into a merge event
        if (volume.currentTimepoint > 0) {
            val p = cursor.getPosition()
            // did the user click on an existing cell and wants to merge the track into it?
            val (selected, isValidSelection) =
                geometryHandler.selectClosestSpotsVR(p, volume.currentTimepoint, cursor.radius, false) ?: (null to false)
            // If this is the first spot we track, and its a valid existing spot, mark it as such
            if (isValidSelection && controllerTrackList.size == 0) {
                startWithExistingSpot = selected
                logger.debug("Set startWithExistingPost to $startWithExistingSpot")
            } else {
                controllerTrackList.add(p)
            }
            logger.debug("Tracked a new spot at position $p")
            logger.debug("Do we want to merge? $isValidSelection. Selected spot is $selected")
            // Create a placeholder link during tracking for immediate feedback
            geometryHandler.addTrackedPoint(p, volume.currentTimepoint, cursor.radius, enableTrackingPreview)

            volume.goToTimepoint(volume.currentTimepoint - 1)
            // If the user clicked a cell and its *not* the first in the track, we assume it is a merge event and end the tracking
            if (isValidSelection && controllerTrackList.size > 1) {
                endControllerTracking(selected)
            }
            // This will also redraw all geometry using Mastodon as source
            notifyObservers(volume.currentTimepoint)
        } else {
            sciview.camera?.showMessage("Reached the first time point!", centered = true, distance = 2f, size = 0.2f)
            // Let's head back to the last timepoint for starting a new track fast-like
            volume.goToLastTimepoint()
            endControllerTracking()
        }
    }

    /** Stops the current controller tracking process and sends the created track to Mastodon. */
    private fun endControllerTracking(mergeSpot: Spot? = null) {
        if (controllerTrackingActive) {
            logger.info("Ending controller tracking now and sending ${controllerTrackList.size} spots to Mastodon to chew on.")
            controllerTrackingActive = false
            // Radius can be 0 because the actual radii were already captured during tracking
            geometryHandler.addTrackToMastodon(null, 0f, true, startWithExistingSpot, mergeSpot)
            controllerTrackList.clear()
            cursor.resetColor()
        }
    }

    fun setupElephantMenu() {
        val unpressedColor = Vector3f(0.81f, 0.81f, 1f)
        val touchingColor = Vector3f(0.7f, 0.65f, 1f)
        val pressedColor = Vector3f(0.54f, 0.44f, 0.96f)
        val colName = "Elephant Menu"
        val delay = 500

        leftWristMenu.addColumn(colName)
        leftWristMenu.addButton(colName, "Stage all",
            command = { updateElephantActions(ElephantMode.StageSpots) }, depressDelay = delay,
            color = unpressedColor, touchingColor = touchingColor, pressedColor = pressedColor)
        leftWristMenu.addButton(colName, "Train All TPs",
            command = { updateElephantActions(ElephantMode.TrainAll) }, depressDelay = delay,
            color = unpressedColor, touchingColor = touchingColor, pressedColor = pressedColor)
        leftWristMenu.addButton(colName, "Predict All",
            command = { updateElephantActions(ElephantMode.PredictAll) }, depressDelay = delay,
            color = unpressedColor, touchingColor = touchingColor, pressedColor = pressedColor)
        leftWristMenu.addButton(colName, "Predict TP",
            command = { updateElephantActions(ElephantMode.PredictTP) }, depressDelay = delay,
            color = unpressedColor, touchingColor = touchingColor, pressedColor = pressedColor)
        leftWristMenu.addButton(colName, "NN linking",
            command = { updateElephantActions(ElephantMode.NNLinking) }, depressDelay = delay,
            color = unpressedColor, touchingColor = touchingColor, pressedColor = pressedColor)
    }

    var lastButtonTime = System.currentTimeMillis()

    /** Ensure that only a single Elephant action is triggered at a time */
    private fun updateElephantActions(mode: ElephantMode) {
        val buttonTime = System.currentTimeMillis()
        if ((buttonTime - lastButtonTime) > 1000) {
            thread {
                when (mode) {
                    ElephantMode.StageSpots -> manvr3d.stageSpots()
                    ElephantMode.TrainAll -> manvr3d.trainSpots()
                    ElephantMode.PredictTP -> manvr3d.preditSpots(false)
                    ElephantMode.PredictAll -> manvr3d.preditSpots(true)
                    ElephantMode.NNLinking -> manvr3d.linkNearestNeighbors()
                }
                logger.info("We locked the buttons for ${(buttonTime-lastButtonTime)} ms ")
                lastButtonTime = buttonTime
            }
        } else {
            sciview.camera?.showMessage("Have some patience!", duration = 1500, distance = 2f, size = 0.2f, centered = true)
        }
    }

    fun setupGeneralMenu() {
        val cam = sciview.camera ?: throw IllegalStateException("Could not find camera")

        val color = Vector3f(0.8f)
        val pressedColor = Vector3f(0.95f, 0.35f, 0.25f)
        val touchingColor = Vector3f(0.7f, 0.55f, 0.55f)

        val undoButton = Button(
            "Undo",
            command = { manvr3d.undoRedo() }, byTouch = true, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor
        )
        val redoButton = Button(
            "Redo",
            command = { manvr3d.undoRedo(redo = true) }, byTouch = true, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor
        )
        val resetViewButton = Button(
            "Recenter", command = {
                manvr3d.resetView()
            }, byTouch = true, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor
        )

        val togglePlaybackDirBtn = ToggleButton(
            textFalse = "BW", textTrue = "FW", command = {
                direction = if (direction == PlaybackDirection.Forward) {
                    PlaybackDirection.Backward
                } else {
                    PlaybackDirection.Forward
                }
            }, byTouch = true,
            defaultColor = Vector3f(0.52f, 0.87f, 0.86f),
            touchingColor = color,
            pressedColor = Vector3f(0.84f, 0.87f, 0.52f)
        )
        val playSlowerBtn = Button(
            "<", command = {
                volumesPerSecond = maxOf(volumesPerSecond - 1f, 1f)
                cam.showMessage(
                    "Speed: ${"%.0f".format(volumesPerSecond)} vol/s",
                    distance = 1.2f, size = 0.2f, centered = true
                )
            }, byTouch = true, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor
        )
        val playFasterBtn = Button(
            ">", command = {
                volumesPerSecond = minOf(volumesPerSecond + 1f, 20f)
                cam.showMessage(
                    "Speed: ${"%.0f".format(volumesPerSecond)} vol/s",
                    distance = 1.2f, size = 0.2f, centered = true
                )
            }, byTouch = true, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor
        )
        val goToLastBtn = Button(
            ">|", command = {
                playing = false
                volume.goToLastTimepoint()
                notifyObservers(volume.currentTimepoint)
                cam.showMessage("Jumped to timepoint ${volume.currentTimepoint}.",
                    distance = 1.2f, size = 0.2f, centered = true)
            }, byTouch = true, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor
        )
        val goToFirstBtn = Button(
            "|<", command = {
                playing = false
                volume.goToFirstTimepoint()
                notifyObservers(volume.currentTimepoint)
                cam.showMessage("Jumped to timepoint ${volume.currentTimepoint}.",
                    distance = 1.2f, size = 0.2f, centered = true)
            }, byTouch = true, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor
        )


        leftWristMenu.addColumn("General Menu")
        leftWristMenu.addRow("General Menu",
            goToFirstBtn, playSlowerBtn, togglePlaybackDirBtn, playFasterBtn, goToLastBtn)
        leftWristMenu.addRow(
            "General Menu", undoButton, redoButton, resetViewButton)


        leftWristMenu.addColumn("Toggle Menu")
        leftWristMenu.addToggleButton("Toggle Menu", "Volume off", "Volume on", command = {
            val state = volume.visible
            manvr3d.setVolumeOnlyVisibility(!state)
        }, color = color, pressedColor = pressedColor, touchingColor = touchingColor, defaultState = true)
        leftWristMenu.addToggleButton("Toggle Menu", "Tracks off", "Tracks on",
            command = {
                trackVisibility = !trackVisibility
                geometryHandler.setTrackVisibility(trackVisibility)
            }, color = color, pressedColor = pressedColor, touchingColor = touchingColor, defaultState = true )
        leftWristMenu.addToggleButton("Toggle Menu", "Spots off", "Spots on",
            command = {
                spotVisibility = !spotVisibility
                geometryHandler.setSpotVisibility(spotVisibility)
            }, color = color, pressedColor = pressedColor, touchingColor = touchingColor, defaultState = true )
        leftWristMenu.addToggleButton("Toggle Menu", "Preview Off", "Preview On", command = {
            enableTrackingPreview = !enableTrackingPreview
            geometryHandler.toggleLinkPreviews(enableTrackingPreview)
        }, color = color, pressedColor = pressedColor, touchingColor = touchingColor, defaultState = true)


        leftWristMenu.addColumn("Cleanup Menu")
        leftWristMenu.addButton("Cleanup Menu", "Merge overlaps", command = {
            manvr3d.mergeOverlapsAndUpdate(volume.currentTimepoint)
        }, color = color, pressedColor = pressedColor, touchingColor = touchingColor)
        leftWristMenu.addButton("Cleanup Menu", "Merge selected", command = {
            manvr3d.mergeSelectionAndUpdate()
        }, color = color, pressedColor = pressedColor, touchingColor = touchingColor)
        leftWristMenu.addButton("Cleanup Menu", "Delete Graph", command = {
            manvr3d.deleteGraphAndUpdate()
        }, byTouch = true, color = color, pressedColor = pressedColor, touchingColor = touchingColor)
        leftWristMenu.addButton("Cleanup Menu", "Delete TP", command = {
            manvr3d.deleteTimepointAndUpdate(volume.currentTimepoint)
        }, byTouch = true, color = color, pressedColor = pressedColor, touchingColor = touchingColor)
    }

    fun addHedgehog() {
        logger.info("added hedgehog")
        val hedgehog = Cylinder(0.005f, 1.0f, 16)
        hedgehog.visible = false
        hedgehog.setMaterial(ShaderMaterial.fromFiles("DeferredInstancedColor.frag", "DeferredInstancedColor.vert"))
        val hedgehogInstanced = InstancedNode(hedgehog)
        hedgehogInstanced.visible = false
        hedgehogInstanced.instancedProperties["ModelMatrix"] = { hedgehog.spatial().world}
        hedgehogInstanced.instancedProperties["Metadata"] = { Vector4f(0.0f, 0.0f, 0.0f, 0.0f) }
        hedgehogs.addChild(hedgehogInstanced)
    }

    /** Attach a spherical cursor to the right controller. */
    private fun attachCursorAndTimepointWidget(debug: Boolean = false) {
        // Only attach if not already attached
        if (sciview.findNodes { it.name == "VR Cursor" }.isNotEmpty()) {
            return
        }

        volumeTimepointWidget.text = volume.currentTimepoint.toString()
        volumeTimepointWidget.name = "Volume Timepoint Widget"
        volumeTimepointWidget.fontColor = Vector4f(0.4f, 0.45f, 1f, 1f)
        volumeTimepointWidget.spatial {
            scale = Vector3f(0.07f)
            position = Vector3f(-0.05f, -0.05f, 0.12f)
            rotation = Quaternionf().rotationXYZ(-1.57f, -1.57f, 0f)
        }

        rightVRController?.model?.let {
            cursor.attachCursor(it)
            sciview.addNode(volumeTimepointWidget, activePublish = false, parent = it)
        }
    }

    open fun inputSetup()
    {
        val cam = sciview.camera ?: throw IllegalStateException("Could not find camera")

        sciview.sceneryInputHandler?.let { handler ->
            listOf(
                "move_forward_fast",
                "move_back_fast",
                "move_left_fast",
                "move_right_fast").forEach { name ->
                handler.getBehaviour(name)?.let { behaviour ->
                    buttonMapper.setKeyBindAndBehavior(hmd, name, behaviour)
                }
            }
        }

        val toggleHedgehog = ClickBehaviour { _, _ ->
            val current = HedgehogVisibility.entries.indexOf(hedgehogVisibility)
            hedgehogVisibility = HedgehogVisibility.entries.get((current + 1) % 3)

            when (hedgehogVisibility) {
                HedgehogVisibility.Hidden -> {
                    hedgehogs.visible = false
                    hedgehogs.runRecursive { it.visible = false }
                    cam.showMessage("Hedgehogs hidden", distance = 2f, size = 0.2f, centered = true)
                }

                HedgehogVisibility.PerTimePoint -> {
                    hedgehogs.visible = true
                    cam.showMessage("Hedgehogs shown per timepoint", distance = 2f, size = 0.2f, centered = true)
                }

                HedgehogVisibility.Visible -> {
                    hedgehogs.visible = true
                    cam.showMessage("Hedgehogs visible", distance = 2f, size = 0.2f, centered = true)
                }
            }
        }

        val nextTimepoint = ClickBehaviour { _, _ ->
            skipToNext = true
        }

        val prevTimepoint = ClickBehaviour { _, _ ->
            skipToPrevious = true
        }

        class ScaleCursorOrSpotsBehavior(val factor: Float): DragBehaviour {
            var selection = listOf<InstancedNode.Instance>()
            override fun init(p0: Int, p1: Int) {
                selection = manvr3d.selectedSpotInstances.toList()
            }

            override fun drag(p0: Int, p1: Int) {
                if (selection.isNotEmpty()) {
                    geometryHandler.changeSpotRadius(selection,factor, false)
                } else {
                    // Make cursor movement a little faster than  changing the spot radii
                    cursor.scaleByFactor(factor * factor)
                }
            }

            override fun end(p0: Int, p1: Int) {
                geometryHandler.changeSpotRadius(selection, factor, true)
            }
        }

        val scaleCursorOrSpotsUp = AnalogInputWrapper(ScaleCursorOrSpotsBehavior(1.02f), sciview.currentScene)

        val scaleCursorOrSpotsDown = AnalogInputWrapper(ScaleCursorOrSpotsBehavior(0.98f), sciview.currentScene)

        val faster = ClickBehaviour { _, _ ->
            volumesPerSecond = maxOf(minOf(volumesPerSecond+0.2f, 20f), 1f)
            cam.showMessage("Speed: ${"%.1f".format(volumesPerSecond)} vol/s",distance = 1.2f, size = 0.2f, centered = true)
        }

        val slower = ClickBehaviour { _, _ ->
            volumesPerSecond = maxOf(minOf(volumesPerSecond-0.2f, 20f), 1f)
            cam.showMessage("Speed: ${"%.1f".format(volumesPerSecond)} vol/s",distance = 2f, size = 0.2f, centered = true)
        }

        val playPause = ClickBehaviour { _, _ ->
            playing = !playing
            if (playing) {
                cam.showMessage("Playing", distance = 2f, size = 0.2f, centered = true)
            } else {
                cam.showMessage("Paused", distance = 2f, size = 0.2f, centered = true)
            }
        }

        val deleteLastHedgehog = ConfirmableClickBehaviour(
            armedAction = { timeout ->
                cam.showMessage("Deleting last track, press again to confirm.",distance = 2f, size = 0.2f,
                    messageColor = Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                    backgroundColor = Vector4f(1.0f, 0.2f, 0.2f, 1.0f),
                    duration = timeout.toInt(),
                    centered = true)

            },
            confirmAction = {
                hedgehogs.children.removeLast()
                volume.children.last { it.name.startsWith("Track-") }?.let { lastTrack ->
                    volume.removeChild(lastTrack)
                }
                val hedgehogId = hedgehogIds.get()
                val hedgehogFile = sessionDirectory.resolve("Hedgehog_${hedgehogId}_${SystemHelpers.formatDateTime()}.csv").toFile()
                val hedgehogFileWriter = BufferedWriter(FileWriter(hedgehogFile, true))
                hedgehogFileWriter.newLine()
                hedgehogFileWriter.newLine()
                hedgehogFileWriter.write("# WARNING: TRACK $hedgehogId IS INVALID\n")
                hedgehogFileWriter.close()

                cam.showMessage("Last track deleted.",distance = 2f, size = 0.2f,
                    messageColor = Vector4f(1.0f, 0.2f, 0.2f, 1.0f),
                    backgroundColor = Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                    duration = 1000,
                    centered = true
                )
            })

        buttonMapper.setKeyBindAndBehavior(hmd, "stepFwd", nextTimepoint)
        buttonMapper.setKeyBindAndBehavior(hmd, "stepBwd", prevTimepoint)

        buttonMapper.setKeyBindAndBehavior(hmd, "playback", playPause)
        buttonMapper.setKeyBindAndBehavior(hmd, "radiusIncrease", scaleCursorOrSpotsUp)
        buttonMapper.setKeyBindAndBehavior(hmd, "radiusDecrease", scaleCursorOrSpotsDown)

        /** Local class that handles double assignment of the left A key which is used to cycle menus as well as
         * reset the rotation when pressed while the [VR2HandNodeTransform] is active. */
        class CycleMenuAndLockAxisBehavior(val button: OpenVRHMD.OpenVRButton, val role: TrackerRole)
            : DragBehaviour {
            fun registerConfig() {
                logger.debug("Setting up keybinds for CycleMenuAndLockAxisBehavior")
                resetRotationBtnManager.registerButtonConfig(button, role)
            }
            override fun init(x: Int, y: Int) {
                resetRotationBtnManager.pressButton(button, role)
                if (!resetRotationBtnManager.isTwoHandedActive()) {
                    leftWristMenu.cycleNext()
                }
            }
            override fun drag(x: Int, y: Int) {}
            override fun end(x: Int, y: Int) {
                resetRotationBtnManager.releaseButton(button, role)
            }
        }

        val leftAButtonBehavior = CycleMenuAndLockAxisBehavior(OpenVRHMD.OpenVRButton.A, TrackerRole.LeftHand)
        leftAButtonBehavior.let {
            it.registerConfig()
            buttonMapper.setKeyBindAndBehavior(hmd, "cycleMenu", it)
        }

        buttonMapper.setKeyBindAndBehavior(hmd, "controllerTracking", trackCellsWithController)

        /** Several behaviors mapped per default to the right menu button. If controller tracking is active,
         * end the tracking. If not, clicking will either create or delete a spot, depending on whether the user
         * previously selected a spot. Holding the button for more than 0.5s deletes the whole connected branch. */
        class AddDeleteResetBehavior : DragBehaviour {
            var start = System.currentTimeMillis()
            var wasExecuted = false
            override fun init(x: Int, y: Int) {
                start = System.currentTimeMillis()
                wasExecuted = false
            }
            override fun drag(x: Int, y: Int) {
                if (System.currentTimeMillis() - start > 500 && !wasExecuted) {
                    val p = cursor.getPosition()
                    geometryHandler.addOrRemoveSpots(
                        volume.currentTimepoint,
                        p,
                        cursor.radius,
                        true,
                        true)
                    wasExecuted = true
                }
            }
            override fun end(x: Int, y: Int) {
                if (controllerTrackingActive) {
                    endControllerTracking()
                } else {
                    val p = cursor.getPosition()
                    logger.debug("Got cursor position: $p")
                    if (!wasExecuted) {
                        geometryHandler.addOrRemoveSpots(
                            volume.currentTimepoint,
                            p,
                            cursor.radius,
                            false,
                            true)
                    }
                }
            }
        }

        buttonMapper.setKeyBindAndBehavior(hmd, "addDeleteReset", AddDeleteResetBehavior())

        class DragSelectBehavior: DragBehaviour {
            var time = System.currentTimeMillis()
            override fun init(x: Int, y: Int) {
                time = System.currentTimeMillis()
                val p = cursor.getPosition()
                cursor.setColor(cursorSelectColor)
                geometryHandler.selectClosestSpotsVR(p, volume.currentTimepoint, cursor.radius, false)
            }
            override fun drag(x: Int, y: Int) {
                // Only perform the selection method ten times a second
                if (System.currentTimeMillis() - time > 100) {
                    val p = cursor.getPosition()
                    geometryHandler.selectClosestSpotsVR(p, volume.currentTimepoint, cursor.radius, true)
                    time = System.currentTimeMillis()
                }
            }
            override fun end(x: Int, y: Int) {
                cursor.resetColor()
            }
        }

        buttonMapper.setKeyBindAndBehavior(hmd, "select", DragSelectBehavior())

        // this behavior is needed for touching the menu buttons
        VRTouch.createAndSet(sciview.currentScene, hmd, listOf(TrackerRole.RightHand), false, customTip = cursor.cursor)

        VRGrabTheWorld.createAndSet(
            sciview.currentScene,
            hmd,
            listOf(OpenVRHMD.OpenVRButton.Side),
            listOf(TrackerRole.LeftHand),
            grabButtonManager,
            1.5f
        )

        VR2HandNodeTransform.createAndSet(
            hmd,
            OpenVRHMD.OpenVRButton.Side,
            sciview.currentScene,
            lockYaxis = false,
            target = volume,
            onStartCallback = {
                geometryHandler.setSpotVisibility(false)
                geometryHandler.setTrackVisibility(false)
            },
            onEndCallback = {
                manvr3d.rebuildGeometry()
                // Only re-enable the spots or tracks if they were enabled in the first place
                geometryHandler.setSpotVisibility(spotVisibility)
                geometryHandler.setTrackVisibility(trackVisibility)
            },
            resetRotationBtnManager = resetRotationBtnManager,
            resetRotationButton = MultiButtonManager.ButtonConfig(leftAButtonBehavior.button, leftAButtonBehavior.role)
        )

        // drag behavior can stay enabled regardless of current tool mode
        MoveInstanceVR.createAndSet(manvr3d, hmd,
            listOf(OpenVRHMD.OpenVRButton.Side), listOf(TrackerRole.RightHand),
            grabButtonManager,
            { cursor.getPosition() }
        )

        hmd.allowRepeats += OpenVRHMD.OpenVRButton.Trigger to TrackerRole.LeftHand
        logger.info("Registered VR controller bindings.")

    }

    /**
     * Launches a thread that updates the volume time points, the hedgehog visibility and reference target color.
     */
    fun launchUpdaterThread() {
        thread {
            while (!sciview.isInitialized) {
                Thread.sleep(200)
            }

            while (sciview.running && cellTrackingActive) {
                if (playing || skipToNext || skipToPrevious) {
                    val oldTimepoint = volume.viewerState.currentTimepoint
                    if (skipToNext || playing) {
                        skipToNext = false
                        if (direction == PlaybackDirection.Forward) {
                            notifyObservers(oldTimepoint + 1)
                        } else {
                            notifyObservers(oldTimepoint - 1)
                        }
                    } else {
                        skipToPrevious = false
                        if (direction == PlaybackDirection.Forward) {
                            notifyObservers(oldTimepoint - 1)
                        } else {
                            notifyObservers(oldTimepoint + 1)
                        }
                    }

                    if (hedgehogs.visible) {
                        if (hedgehogVisibility == HedgehogVisibility.PerTimePoint) {
                            hedgehogs.children.forEach { hh ->
                                val hedgehog = hh as InstancedNode
                                hedgehog.instances.forEach {
                                    if (it.metadata.isNotEmpty()) {
                                        it.visible =
                                            (it.metadata["spine"] as SpineMetadata).timepoint == volume.viewerState.currentTimepoint
                                    }
                                }
                            }
                        } else {
                            hedgehogs.children.forEach { hh ->
                                val hedgehog = hh as InstancedNode
                                hedgehog.instances.forEach { it.visible = true }
                            }
                        }
                    }

                    updateLoopActions.forEach { it.invoke() }
                }

                Thread.sleep((1000.0f / volumesPerSecond).toLong())
            }
            logger.info("CellTracking updater thread has stopped.")
        }
    }

    private val updateLoopActions: ArrayList<() -> Unit> = ArrayList()

    /** Allows hooking lambdas into the main update loop. This is needed for eye tracking related actions. */
    protected fun attachToLoop(action: () -> Unit) {
        updateLoopActions.add(action)
    }

    /** Samples a given [volume] from an [origin] point along a [direction].
     * @return a pair of lists, containing the samples and sample positions, respectively. */
    protected fun sampleRayThroughVolume(origin: Vector3f, direction: Vector3f, volume: Volume): Pair<List<Float>?, List<Vector3f>?> {
        val intersection = volume.spatial().intersectAABB(origin, direction.normalize(), ignoreChildren = true)

        if (intersection is MaybeIntersects.Intersection) {
            val localEntry = (intersection.relativeEntry)
            val localExit = (intersection.relativeExit)
            val (samples, samplePos) = volume.sampleRayGridTraversal(localEntry, localExit) ?: (null to null)
            val volumeScale = (volume as RAIVolume).getVoxelScale()
            return (samples?.map { it ?: 0.0f } to samplePos?.map { it?.mul(volumeScale) ?: Vector3f(0f) })
        } else {
            logger.warn("Ray didn't intersect volume! Origin was $origin, direction was $direction.")
        }
        return (null to null)
    }

    open fun addSpine(center: Vector3f, direction: Vector3f, volume: Volume, confidence: Float, timepoint: Int) {
        val cam = sciview.camera as? DetachedHeadCamera ?: return
        val sphere = volume.boundingBox?.getBoundingSphere() ?: return

        val sphereDirection = sphere.origin.minus(center)
        val sphereDist =
            Math.sqrt(sphereDirection.x * sphereDirection.x + sphereDirection.y * sphereDirection.y + sphereDirection.z * sphereDirection.z) - sphere.radius

        val p1 = center
        val temp = direction.mul(sphereDist + 2.0f * sphere.radius)
        val p2 = Vector3f(center).add(temp)

        val spine = (hedgehogs.children.last() as InstancedNode).addInstance()
        spine.spatial().orientBetweenPoints(p1, p2, true, true)
        spine.visible = false

        val intersection = volume.spatial().intersectAABB(p1, (p2 - p1).normalize(), true)

        if (volume.boundingBox?.isInside(cam.spatial().position)!!) {
            logger.info("Can't track inside the volume! Please move out of the volume and try again")
            return
        }
        if(intersection is MaybeIntersects.Intersection) {
            // get local entry and exit coordinates, and convert to UV coords
            val localEntry = (intersection.relativeEntry)
            val localExit = (intersection.relativeExit)
            // TODO We dont need the local direction for grid traversal, but its still in the spine metadata for now
            val localDirection = Vector3f(0f)
            val (samples, samplePos) = volume.sampleRayGridTraversal(localEntry, localExit) ?: (null to null)
            val volumeScale = (volume as RAIVolume).getVoxelScale()

            if (samples != null && samplePos != null) {
                val metadata = SpineMetadata(
                    timepoint,
                    center,
                    direction,
                    intersection.distance,
                    localEntry,
                    localExit,
                    localDirection,
                    cam.headPosition,
                    cam.headOrientation,
                    cam.spatial().position,
                    confidence,
                    samples.map { it ?: 0.0f },
                    samplePos.map { it?.mul(volumeScale) ?: Vector3f(0f) }
                )
                val count = samples.filterNotNull().count { it > 0.2f }

                spine.metadata["spine"] = metadata
                spine.instancedProperties["ModelMatrix"] = { spine.spatial().world }
                // TODO: Show confidence as color for the spine
                spine.instancedProperties["Metadata"] =
                    { Vector4f(confidence, timepoint.toFloat() / volume.timepointCount, count.toFloat(), 0.0f) }
            }
        }
    }


    protected fun writeHedgehogToFile(hedgehog: InstancedNode, hedgehogId: Int) {
        val hedgehogFile =
            sessionDirectory.resolve("Hedgehog_${hedgehogId}_${SystemHelpers.formatDateTime()}.csv").toFile()
        val hedgehogFileWriter = hedgehogFile.bufferedWriter()
        hedgehogFileWriter.write("Timepoint;Origin;Direction;LocalEntry;LocalExit;LocalDirection;HeadPosition;HeadOrientation;Position;Confidence;Samples\n")

        val spines = hedgehog.instances.mapNotNull { spine ->
            spine.metadata["spine"] as? SpineMetadata
        }

        spines.forEach { metadata ->
            hedgehogFileWriter.write(
                "${metadata.timepoint};${metadata.origin};${metadata.direction};${metadata.localEntry};${metadata.localExit};" +
                        "${metadata.localDirection};${metadata.headPosition};${metadata.headOrientation};" +
                        "${metadata.position};${metadata.confidence};${metadata.samples.joinToString(";")
                        }\n"
            )
        }
        hedgehogFileWriter.close()
        logger.info("Wrote hedgehog to file ${hedgehogFile.name}")
    }

    protected fun writeTrackToFile(
        points: List<Pair<Vector3f, SpineGraphVertex>>,
        hedgehogId: Int
    ) {
        val trackFile = sessionDirectory.resolve("Tracks.tsv").toFile()
        val trackFileWriter = BufferedWriter(FileWriter(trackFile, true))
        if(!trackFile.exists()) {
            trackFile.createNewFile()
            trackFileWriter.write("# BionicTracking cell track listing for ${sessionDirectory.fileName}\n")
            trackFileWriter.write("# TIME\tX\tYt\t\tZ\tTRACK_ID\tPARENT_TRACK_ID\tSPOT\tLABEL\n")
        }

        trackFileWriter.newLine()
        trackFileWriter.newLine()
        val parentId = 0
        trackFileWriter.write("# START OF TRACK $hedgehogId, child of $parentId\n")
        val volumeDimensions = volume.getDimensions()
        points.windowed(2, 1).forEach { pair ->
            val p = Vector3f(pair[0].first).mul(Vector3f(volumeDimensions)) // direct product
            val tp = pair[0].second.timepoint
            trackFileWriter.write("$tp\t${p.x()}\t${p.y()}\t${p.z()}\t${hedgehogId}\t$parentId\t0\t0\n")
        }

        trackFileWriter.close()
    }

    /**
     * Stops the current tracking environment and restore the original state.
     * This method should be overridden if functionality is extended, to make sure any extra objects are also deleted.
     */
    open fun stop() {
        logger.info("Objects in the scene: ${sciview.allSceneNodes.map { it.name }}")
        cellTrackingActive = false
        if (::lightTetrahedron.isInitialized) {
            lightTetrahedron.forEach { sciview.deleteNode(it) }
        }
        // Try to find and delete possibly existing VR objects
        listOf("Shell", "leftHand", "rightHand").forEach {
            val n = sciview.find(it)
            n?.let { sciview.deleteNode(n) }
        }
        rightVRController?.model?.let {
            sciview.deleteNode(it)
        }
        leftVRController?.model?.let {
            sciview.deleteNode(it)
        }

        logger.info("Cleaned up basic VR objects. Objects left: ${sciview.allSceneNodes.map { it.name }}")

        sciview.toggleVRRendering()
        logger.info("Shut down and disabled VR environment.")
        manvr3d.rebuildGeometry()
    }

}
