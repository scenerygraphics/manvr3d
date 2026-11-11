package graphics.scenery.manvr3d.vr

import graphics.scenery.manvr3d.Manvr3dMain
import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.controls.*
import graphics.scenery.controls.behaviours.AnalogInputWrapper
import graphics.scenery.controls.behaviours.VRTouch
import graphics.scenery.primitives.TextBoard
import graphics.scenery.ui.*
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.*
import graphics.scenery.utils.lazyLogger
import graphics.scenery.volumes.RAIVolume
import graphics.scenery.volumes.Volume
import org.mastodon.mamut.model.Spot
import org.scijava.ui.behaviour.ClickBehaviour
import sc.iview.SciView
import graphics.scenery.manvr3d.analysis.HedgehogAnalysis.SpineGraphVertex
import graphics.scenery.controls.behaviours.MultiButtonManager
import graphics.scenery.controls.behaviours.ThumbstickMovement
import graphics.scenery.controls.behaviours.VRTwoHandNodeTransform
import graphics.scenery.controls.behaviours.VRGrabTheWorld
import graphics.scenery.controls.behaviours.VRPress
import graphics.scenery.manvr3d.util.GeometryHandler
import graphics.scenery.utils.TimepointObservable
import graphics.scenery.manvr3d.util.SpineMetadata
import graphics.scenery.manvr3d.util.CellTrackingButtonMapper
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import java.io.BufferedWriter
import java.io.FileWriter
import java.lang.Math
import java.nio.file.Path
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlin.time.DurationUnit

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
    val logger by lazyLogger()

    lateinit var sessionId: String
    lateinit var sessionDirectory: Path

    lateinit var hmd: OpenXRHMD

    val hedgehogs = Mesh()
    val hedgehogIds = AtomicInteger(0)
    lateinit var volume: Volume

    val referenceTarget = Icosphere(0.004f, 2)

    @Volatile var eyeTrackingActive = false
    var playing = false
    var playDirection = PlaybackDirection.Backward
    var volumesPerSecond = 6f
    var skipToNext = false
    var skipToPrevious = false

    var volumeScaleFactor = 1.0f

    private lateinit var lightTetrahedron: List<PointLight>

    val volumeTimepointWidget = TextBoard(inFront = true)

    enum class HedgehogVisibility { Hidden, PerTimePoint, Visible }

    enum class PlaybackDirection { Forward, Backward }

    enum class ElephantMode { StageSpots, TrainAll, PredictTP, PredictAll, NNLinking }

    var hedgehogVisibility = HedgehogVisibility.Hidden


    var leftVRController: TrackedDevice? = null
    var rightVRController: TrackedDevice? = null

    val cursor = CursorTool()
    val cursorSelectColor = Vector3f(1f, 0.25f, 0.25f)
    val cursorTrackingColor = Vector3f(0.65f, 1f, 0.22f)

    val controllerColor = Vector3f(0.12f)

    lateinit var leftWristMenu: MultiWristMenu

    var enableTrackingPreview = true

    val grabButtonManager = MultiButtonManager()
    val resetRotationBtnManager = MultiButtonManager()
    lateinit var thumbstickMovement: ThumbstickMovement

    val buttonMapper = CellTrackingButtonMapper

    val fileLogger = manvr3d.fileLogger

    var lensPosition = Vector3f(0f)
    var wantLensAtCell = false
    val selectedTrackForLens = mutableMapOf<Int, Spot>()

    open fun run() {
        sciview.toggleVRRendering(resolutionScale = resolutionScale)
        hmd = sciview.hub.getWorkingHMD() as? OpenXRHMD ?: throw IllegalStateException("Could not find headset")

        if (!hmd.awaitInteractionProfile()) {
            logger.warn("Headset did not report a controller profile in time (session not focused?)")
            return
        }

        // Load profile for this headset
        if (!buttonMapper.loadProfileForHMD(hmd)) {
            logger.warn("Failed to load controller profile")
            return
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
        hmd.onDeviceConnect { hmd, device, timestamp ->
            logger.info("onDeviceConnect called, cam=${sciview.camera}")
            if (device.type == TrackedDeviceType.Controller) {
                logger.info("Got device ${device.name} at $timestamp")
                device.model?.let {
                    hmd.attachToNode(device, it, sciview.camera)
                    val meshPrimitives = mutableListOf<Mesh>()
                    fun collectMeshChildren(node: Node) {
                        if (node is Mesh) {
                            meshPrimitives.add(node)
                        }
                        node.children.forEach { n ->
                            collectMeshChildren(n)
                        }
                    }
                    collectMeshChildren(it)
                    meshPrimitives.forEach { mesh ->
                        mesh.ifMaterial { diffuse = controllerColor }
                    }
                }
                when (device.role) {
                    TrackerRole.Invalid -> {}
                    TrackerRole.LeftHand -> {
                        leftVRController = device
                        device.model?.let {
                            it.name = "leftHand"
                            leftWristMenu = MultiWristMenu(it,
                                columnScale = 0.03f,
                                columnBasePosition = Vector3f(0.03f, 0.02f, 0.11f),
                                columnRotation = Quaternionf().rotationXYZ(-1.5f, 1.7f, 0f)
                            )
                            setupElephantMenu()
                            setupGeneralMenu()
                            setupTrackingMenu()
                            onLeftHandReady()
                            leftWristMenu.hideAll()
                            buttonMapper.mapper.attachUIForRole(device.role, it, textScale = 0.018f)
                            logger.info("Finished setting up left controller button layout")
                        }
                    }
                    TrackerRole.RightHand -> {
                        rightVRController = device
                        attachCursorAndTimepointWidget()
                        device.model?.let {
                            it.name = "rightHand"
                            buttonMapper.mapper.attachUIForRole(device.role, it, textScale = 0.02f)
                            logger.info("Finished setting up right controller button layout")
                        }
                    }
                }
                Unit
            }
        }
        inputSetup()

        manvr3d.rebuildGeometry()
        launchUpdaterThread()
        launchLensingThread()
        launchMenuPointerIntersectionThread()
    }

    /** Callback that can be used in [EyeTracking] to perform additional logic once the controllers were found. */
    protected open fun onLeftHandReady() {}

    var controllerTrackingActive = false

    /** This class combines information from tracked points during tracking. Supports storing spots if the user
     * wants to connect existing spots instead of creating new ones. */
    data class TrackedPoint( val pos: Vector3f, val tp: Int, val radius: Float, val spot: Spot? = null )

    /** Intermediate storage for a single track created with the controllers.
     * Once tracking is finished, this track is sent to Mastodon. Each point contains the position, timepoint and radius,
     * and possibly existing spots that were selected. */
    val trackPointList = mutableListOf<TrackedPoint>()

    /** Stops the current controller tracking process and sends the created track to Mastodon. */
    fun endControllerTracking() {
        if (!controllerTrackingActive) return

        logger.info("Ending controller tracking now and sending ${trackPointList.size} spots to Mastodon to chew on.")
        controllerTrackingActive = false
        geometryHandler.addTrackToMastodon(trackPointList)
        cursor.resetColor()

        buttonMapper.let {
            it.mapper.updateLabel(it.ADD_DELETE_RESET, "Add", it.defaultColor)
        }

        fileLogger.endControllerTracking(trackPointList)
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
        leftWristMenu.addButton(colName, "Link All",
            command = { updateElephantActions(ElephantMode.NNLinking) }, depressDelay = delay,
            color = unpressedColor, touchingColor = touchingColor, pressedColor = pressedColor)

        logger.debug("Set up Elephant menu.")
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
                    ElephantMode.PredictTP -> manvr3d.predictSpots(false)
                    ElephantMode.PredictAll -> manvr3d.predictSpots(true)
                    ElephantMode.NNLinking -> manvr3d.linkSpots()
                }
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
            command = { manvr3d.undoRedo() }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )
        val redoButton = Button(
            "Redo",
            command = { manvr3d.undoRedo(redo = true) }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )
        val resetViewButton = Button(
            "Recenter", command = {
                manvr3d.resetView()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val togglePlaybackDirBtn = ToggleButton(
            textFalse = "BW", textTrue = "FW", command = {
                playDirection = if (playDirection == PlaybackDirection.Forward) {
                    PlaybackDirection.Backward
                } else {
                    PlaybackDirection.Forward
                }
                manvr3d.sciviewWin.camera?.showMessage(
                    "Play & Track ${if (playDirection == PlaybackDirection.Forward) "forward" else "backward"}",
                    distance = 1.2f, size = 0.2f, centered = true
                )
            }, byTouch = false, defaultColor = Vector3f(0.52f, 0.87f, 0.86f), default = false,
            touchingColor = color, pressedColor = Vector3f(0.84f, 0.87f, 0.52f), textInFront = true
        )
        val playSlowerBtn = Button(
            "<", command = {
                volumesPerSecond = maxOf(volumesPerSecond - 1f, 1f)
                cam.showMessage(
                    "Speed: ${"%.0f".format(volumesPerSecond)} vol/s",
                    distance = 1.2f, size = 0.2f, centered = true
                )
            }, byTouch = false, depressDelay = 250, defaultColor = color,
            pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )
        val playFasterBtn = Button(
            ">", command = {
                volumesPerSecond = minOf(volumesPerSecond + 1f, 20f)
                cam.showMessage(
                    "Speed: ${"%.0f".format(volumesPerSecond)} vol/s",
                    distance = 1.2f, size = 0.2f, centered = true
                )
            }, byTouch = false, depressDelay = 250, defaultColor = color,
            pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )
        val goToLastBtn = Button(
            ">|", command = {
                playing = false
                volume.goToLastTimepoint()
                notifyObservers(volume.currentTimepoint)
                cam.showMessage("Jumped to timepoint ${volume.currentTimepoint}.",
                    distance = 1.2f, size = 0.2f, centered = true)
            }, byTouch = false, depressDelay = 250, defaultColor = color,
            pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )
        val goToFirstBtn = Button(
            "|<", command = {
                playing = false
                volume.goToFirstTimepoint()
                notifyObservers(volume.currentTimepoint)
                cam.showMessage("Jumped to timepoint ${volume.currentTimepoint}.",
                    distance = 1.2f, size = 0.2f, centered = true)
            }, byTouch = false, depressDelay = 250, defaultColor = color,
            pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )


        leftWristMenu.addColumn("General Menu")
        leftWristMenu.addRow("General Menu",
            goToFirstBtn, playSlowerBtn, togglePlaybackDirBtn, playFasterBtn, goToLastBtn)
        leftWristMenu.addRow(
            "General Menu", undoButton, redoButton, resetViewButton)


        leftWristMenu.addColumn("Toggle Menu")

        val decTransparencyBtn = Button(
            " - ", command = {
                manvr3d.shiftVolumeTransparency(delta = -0.1f)
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val incTransparencyBtn = Button(
            " + ", command = {
                manvr3d.shiftVolumeTransparency(delta = 0.1f)
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val volumeToggleBtn = ToggleButton(
            "Volume off", "Volume on", command = {
                val state = volume.visible
                manvr3d.setVolumeOnlyVisibility(!state)
            }, byTouch = false, defaultColor = color, pressedColor = pressedColor,
            touchingColor = touchingColor, default = true, textInFront = true)

        leftWristMenu.addRow("Toggle Menu", volumeToggleBtn, decTransparencyBtn, incTransparencyBtn)

        val autoContrastBtn = Button(
            "Auto Contrast", command = {
                manvr3d.toggleAutoIntensity()
            }, byTouch = false, defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true)

        val lowerBoundUpBtn = Button(
            " L+ ", command =  {
                manvr3d.intensity.rangeMin /= 0.8f
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val lowerBoundDownBtn = Button(
            " L- ", command =  {
                manvr3d.intensity.rangeMin *= 0.8f
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val upperBoundUpBtn = Button(
            " H+ ", command =  {
                manvr3d.intensity.rangeMax /= 0.8f
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val upperBoundDownBtn = Button(
            " H- ", command =  {
                manvr3d.intensity.rangeMax *= 0.8f
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        leftWristMenu.addRow("Toggle Menu", autoContrastBtn,
            lowerBoundDownBtn, lowerBoundUpBtn, upperBoundDownBtn, upperBoundUpBtn)

        val lensToggleButton = ToggleButton(
            "Lens off", "Lens on", command = {
                isLensingActive = !isLensingActive
                // We accumulate lensing time per session for statistics
                if (isLensingActive) {
                    fileLogger.beginLensing()
                } else {
                    fileLogger.endLensing()
                }
            }, byTouch = false, defaultColor = color, pressedColor = pressedColor,
            touchingColor = touchingColor, default = false, textInFront = true)

        val lensRadiusDownBtn = Button(
            " R- ", command = {
                changeLensingRadius(0.8f)
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val lensRadiusUpBtn = Button(
            " R+ ", command = {
                changeLensingRadius(1.2f)
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val lensBackgroundButton = ToggleButton("Context Off", "Context On", command = {
            val backgroundState = volume.lensingBackgroundStrength > 0.001
            volume.lensingBackgroundStrength = if (backgroundState) 0.0f else 0.04f
        }, byTouch = false, defaultColor = color,pressedColor = pressedColor,
            touchingColor = touchingColor, default = true, textInFront = true)

        leftWristMenu.addRow("Toggle Menu",
            lensToggleButton, lensRadiusDownBtn, lensRadiusUpBtn, lensBackgroundButton)

        val spotRadiusUpBtn = Button(
            " + ", command = {
                manvr3d.geometryHandler.increaseSphereInstanceScale()
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val spotRadiusDownBtn = Button(
            " - ", command = {
                manvr3d.geometryHandler.decreaseSphereInstanceScale()
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val toggleSpotVisBtn = ToggleButton(
            "Spots off", "Spots on",
            command = {
                manvr3d.isSpotVisible = !manvr3d.isSpotVisible
                geometryHandler.setSpotVisibility(manvr3d.isSpotVisible)
            }, byTouch = false, defaultColor = color, pressedColor = pressedColor,
            touchingColor = touchingColor, default = true, textInFront = true
        )

        val resetSpotRadiusBtn = Button(
            "Reset Size", command = {
                manvr3d.geometryHandler.resetSphereInstanceScale()
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        leftWristMenu.addRow("Toggle Menu", toggleSpotVisBtn, spotRadiusDownBtn, spotRadiusUpBtn, resetSpotRadiusBtn)

        val trackRadiusUpBtn = Button(
            " + ", command = {
                manvr3d.geometryHandler.increaseLinkScale()
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val trackRadiusDownBtn = Button(
            " - ", command = {
                manvr3d.geometryHandler.decreaseLinkScale()
                manvr3d.updateUI()
            }, byTouch = false, depressDelay = 250,
            defaultColor = color, pressedColor = pressedColor, touchingColor = touchingColor, textInFront = true
        )

        val toggleTrackVisBtn = ToggleButton(
            "Tracks off", "Tracks on",
            command = {
                manvr3d.isTrackVisible = !manvr3d.isTrackVisible
                geometryHandler.setTrackVisibility(manvr3d.isTrackVisible)
            }, byTouch = false, defaultColor = color, pressedColor = pressedColor,
            touchingColor = touchingColor, default = true, textInFront = true
        )

        leftWristMenu.addRow("Toggle Menu", toggleTrackVisBtn, trackRadiusDownBtn, trackRadiusUpBtn)

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
        }, byTouch = false, color = color, pressedColor = pressedColor, touchingColor = touchingColor)
        leftWristMenu.addButton("Cleanup Menu", "Delete TP", command = {
            manvr3d.deleteTimepointAndUpdate(volume.currentTimepoint)
        }, byTouch = false, color = color, pressedColor = pressedColor, touchingColor = touchingColor)

        logger.debug("Set up general menus.")
    }

    fun setupTrackingMenu() {
        leftWristMenu.addColumn("Tracking Menu")

        val trackAllButton = Button(" |<->| ", command = {
            thread {
                val selected = manvr3d.mastodon.selectionModel.selectedVertices
                val fwdTime = manvr3d.semiAutoTracker.track(true)
                manvr3d.mastodon.selectionModel.clearSelection()
                selected.forEach {
                    manvr3d.mastodon.selectionModel.setSelected(it, true)
                }
                val bwdTime = manvr3d.semiAutoTracker.track(false)
                val totalTime = fwdTime.plus(bwdTime)
                val logText = "Tracked ${selected.size} cell${if (selected.size == 1) "" else "s"} " +
                        "forward and backward in ${totalTime.toString(DurationUnit.SECONDS, 2)}."
                logger.info(logText)
                fileLogger.append(logText)
                manvr3d.recordVREditEvent("Semi-auto Track Full", totalTime)
                manvr3d.geometryHandler.clearSpotSelection()
                manvr3d.geometryHandler.clearLinkSelection()
            }
        }, depressDelay = 250, textInFront = true)

        val trackFwdButton = Button(" |-> ", command = {
            thread {
                val selected = manvr3d.mastodon.selectionModel.selectedVertices
                val fwdTime = manvr3d.semiAutoTracker.track(true)
                val logText = "Tracked ${selected.size} cell${if (selected.size == 1) "" else "s"} forward " +
                        "in ${fwdTime.toString(DurationUnit.SECONDS, 2)}."
                logger.info(logText)
                fileLogger.append(logText)
                manvr3d.recordVREditEvent("Semi-auto Track Forward", fwdTime)
                manvr3d.geometryHandler.clearSpotSelection()
                manvr3d.geometryHandler.clearLinkSelection()
            }
        }, depressDelay = 250, textInFront = true)

        val trackBwdButton = Button(" <-| ", command = {
            thread {
                val selected = manvr3d.mastodon.selectionModel.selectedVertices
                val bwdTime = manvr3d.semiAutoTracker.track(false)
                val logText = "Tracked ${selected.size} cell${if (selected.size == 1) "" else "s"} backward " +
                        "in ${bwdTime.toString(DurationUnit.SECONDS, 2)}."
                logger.info(logText)
                fileLogger.append(logText)
                manvr3d.recordVREditEvent("Semi-auto Track Backward", bwdTime)
                manvr3d.geometryHandler.clearSpotSelection()
                manvr3d.geometryHandler.clearLinkSelection()
            }
        }, depressDelay = 250, textInFront = true)

        leftWristMenu.addRow("Tracking Menu", trackAllButton, trackBwdButton, trackFwdButton)

        // Store whether lensing was previously active already
        var wasLensingActive = false

        val attachLensToCellBtn = Button("Lens to cell", command = {
            // If this returns a boolean, it indicates whether (controller) lensing was previously active
            // so we can restore the state later
            attachLensToCell()?.let { wasLensingActive = it }
        }, depressDelay = 250, textInFront = true)

        val resetLensBtn = Button("Reset lens", command = {
            wantLensAtCell = false
            isLensingActive = wasLensingActive
        }, depressDelay = 250, textInFront = true)

        leftWristMenu.addRow("Tracking Menu", attachLensToCellBtn, resetLensBtn)

        val selectTrackBtn = Button("Select Track", command = {

        }, depressDelay = 250, textInFront = true)

        val markVerifiedBtn = Button("Mark Verified", command = {

        }, depressDelay = 250, textInFront = true)

        leftWristMenu.addRow("Tracking Menu", selectTrackBtn, markVerifiedBtn)

        val hideUnselectedBtn = Button("Hide Unselected", command = {

        }, depressDelay = 250, textInFront = true)

        val resetVisibilityBtn = Button("Reset Visibility", command = {

        }, depressDelay = 250, textInFront = true)

        leftWristMenu.addRow("Tracking Menu", hideUnselectedBtn, resetVisibilityBtn)

    }

    var isLensingActive = false
        set(value) {
            manvr3d.volumeNode.slicingMode = if (value) Volume.SlicingMode.LensingSmooth else Volume.SlicingMode.None
            manvr3d.geometryHandler.sphere.enableLens = value
            manvr3d.geometryHandler.cylinder.enableLens = value
            field = value
        }

    private fun updateLensAtCellPosition() {
        if (!wantLensAtCell) return
        val posArray = FloatArray(3)
        selectedTrackForLens[volume.viewerState.currentTimepoint]?.let {
            it.localize(posArray)
            val pos = manvr3d.mastodonToSciviewCoords(posArray)
            lensPosition = pos
        }
    }

    /** Attach a volume lens to the first selected cell in the current timepoint. This selects the whole connected
     * branch and follows it with the lens.
     * Returns a boolean that indicates whether (controller) lensing was previously active or not.*/
    private fun attachLensToCell() : Boolean? {
        // Grab the first selected spot in the current timepoint
        val selected =
            manvr3d.mastodon.selectionModel.selectedVertices.firstOrNull { it.timepoint == manvr3d.currentTimepoint }
                ?: return null
        val wasLensingActive = isLensingActive
        selectedTrackForLens.clear()
        val track = manvr3d.geometryHandler.gatherBranch(selected)
        // Group the full branch by timepoints and only take the first one. This isn't great, but handles subbranches
        // by selecting one
        selectedTrackForLens.putAll(
            track.groupBy { it.timepoint }.
            map { it.value.first().timepoint to it.value.first() })
        wantLensAtCell = true
        isLensingActive = true
        updateLensAtCellPosition()
        return wasLensingActive
    }

    fun changeLensingRadius(factor: Float) {
        val oldRadius = manvr3d.volumeNode.lensingRadius
        val newRadius = oldRadius * factor
        manvr3d.geometryHandler.updateLensRadius(newRadius)
        manvr3d.volumeNode.lensingRadius = newRadius
    }

    fun launchLensingThread() {
        thread {
            logger.debug("Launched lensing thread")
            while (sciview.running && manvr3d.isVRactive) {
                if (isLensingActive) {
                    val pos = if (wantLensAtCell) lensPosition else cursor.getPosition()
                    manvr3d.geometryHandler.updateLensPosition(pos)
                    Thread.sleep(20)
                } else {
                    Thread.sleep(200)
                }
            }
        }
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
            scale = Vector3f(0.06f)
            position = Vector3f(-.035f, -.03f, .02f)
            rotation = Quaternionf().rotationXYZ(-1.2f, .1f, 0f)
        }

        rightVRController?.model?.let {
            cursor.attachCursorAndPointer(it, debug)
            sciview.addNode(volumeTimepointWidget, activePublish = false, parent = it)
            logger.debug("Attached cursor and timepoint widget to right controller.")
        }
    }

    open fun inputSetup()
    {
        logger.debug("Starting to set up VR controller bindings")
        val cam = sciview.camera ?: throw IllegalStateException("Could not find camera")

        val mapper = buttonMapper.mapper

        thumbstickMovement = ThumbstickMovement.createAndSet(
            hmd, TrackerRole.LeftHand, cam, 1.5f, 1.5f,
            Quaternionf().rotateX(Math.toRadians(-45.0).toFloat())
        )

        val scaleCursorOrSpotsUp = AnalogInputWrapper(ScaleCursorOrSpotsBehaviour(manvr3d, .02f), sciview.currentScene)
        val scaleCursorOrSpotsDown = AnalogInputWrapper(ScaleCursorOrSpotsBehaviour(manvr3d,0.98f), sciview.currentScene)

        val toggleMenu = ClickBehaviour { _, _ ->
            leftWristMenu.toggleVisibility()
        }

        val cycleMenuPrevious = ClickBehaviour { _, _ ->
            leftWristMenu.cyclePrevious()
        }

        mapper.bind(hmd, buttonMapper.STEP_FWD, AnalogInputWrapper(
            TimepointScrollBehaviour(this, PlaybackDirection.Forward), sciview.currentScene))
        mapper.bind(hmd, buttonMapper.STEP_BWD, AnalogInputWrapper(
            TimepointScrollBehaviour(this, PlaybackDirection.Backward), sciview.currentScene))

        mapper.bind(hmd, buttonMapper.TOGGLE_MENU, toggleMenu)
        mapper.bind(hmd, buttonMapper.CYCLE_MENU_BWD, cycleMenuPrevious)
        mapper.bind(hmd, buttonMapper.RADIUS_INCREASE, scaleCursorOrSpotsUp)
        mapper.bind(hmd, buttonMapper.RADIUS_DECREASE, scaleCursorOrSpotsDown)

        val leftAButtonBehavior = CycleMenuAndLockAxisBehaviour(this, OpenXRHMD.OpenXRButton.A, TrackerRole.LeftHand)
        leftAButtonBehavior.let {
            it.registerConfig()
            mapper.bind(hmd, buttonMapper.CYCLE_MENU_FWD, it)
        }

        val trackCellsWithController = ControllerTrackBehaviour(this)

        mapper.bind(hmd, buttonMapper.CONTROLLER_TRACKING, trackCellsWithController)
        mapper.bind(hmd, "addDeleteReset", AddDeleteResetBehavior(this))
        mapper.bind(hmd, "select", DragSelectBehavior(this))

        // this behavior is needed for touching the menu buttons
        VRTouch.createAndSet(
            sciview.currentScene,
            hmd,
            listOf(TrackerRole.RightHand),
            false,
            customTip = cursor.cursorPointer
        )

        VRPress.createAndSet(
            sciview.currentScene,
            hmd,
            listOf(OpenXRHMD.OpenXRButton.Trigger),
            listOf(TrackerRole.RightHand),
            customTip = cursor.cursorPointer
        )

        VRGrabTheWorld.createAndSet(
            sciview.currentScene,
            hmd,
            listOf(OpenXRHMD.OpenXRButton.Side),
            listOf(TrackerRole.LeftHand),
            grabButtonManager
        )

        val defaultCycleFwdLabel = mapper.getMapping(buttonMapper.CYCLE_MENU_FWD)?.label ?: ""

        VRTwoHandNodeTransform.createAndSet(
            hmd,
            OpenXRHMD.OpenXRButton.Side,
            sciview.currentScene,
            lockYaxis = false,
            target = volume,
            onStartCallback = {
                geometryHandler.setSpotVisibility(false)
                geometryHandler.setTrackVisibility(false)
                // always set volume to true during movement
                manvr3d.isVolumeVisible = volume.visible
                manvr3d.setVolumeOnlyVisibility(true)

                mapper.updateLabel(buttonMapper.CYCLE_MENU_FWD, "Reset",
                    Vector3f(1f, 0.5f, 0.5f))
            },
            onDragCallback = {
                updateLensAtCellPosition()
            },
            onEndCallback = {
                thread {
                    manvr3d.rebuildGeometry()
                    // Only re-enable the spots or tracks if they were enabled in the first place
                    geometryHandler.setSpotVisibility(manvr3d.isSpotVisible)
                    geometryHandler.setTrackVisibility(manvr3d.isTrackVisible)
                    // change volume visibility to what it was before
                    manvr3d.setVolumeOnlyVisibility(manvr3d.isVolumeVisible)
                    updateLensAtCellPosition()
                    mapper.updateLabel(
                        buttonMapper.CYCLE_MENU_FWD, defaultCycleFwdLabel,
                        buttonMapper.defaultColor
                    )
                }

            },
            resetRotationBtnManager = resetRotationBtnManager,
            resetRotationButton = MultiButtonManager.ButtonConfig(leftAButtonBehavior.button, leftAButtonBehavior.role)
        )

        // drag behavior can stay enabled regardless of current tool mode
        MoveInstanceVR.createAndSet(manvr3d, hmd,
            listOf(OpenXRHMD.OpenXRButton.Side), listOf(TrackerRole.RightHand),
            grabButtonManager,
            { cursor.getPosition() }
        )

        hmd.allowRepeats += OpenXRHMD.OpenXRButton.A to TrackerRole.RightHand
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
            logger.debug("Launched updater thread")
            while (sciview.running && manvr3d.isVRactive) {
                if (playing || skipToNext || skipToPrevious) {
                    val oldTimepoint = volume.viewerState.currentTimepoint
                    if (skipToNext || playing) {
                        skipToNext = false
                        if (playDirection == PlaybackDirection.Forward) {
                            notifyObservers(oldTimepoint + 1)
                        } else {
                            notifyObservers(oldTimepoint - 1)
                        }
                    } else {
                        skipToPrevious = false
                        if (playDirection == PlaybackDirection.Forward) {
                            notifyObservers(oldTimepoint - 1)
                        } else {
                            notifyObservers(oldTimepoint + 1)
                        }
                    }
                    updateLensAtCellPosition()

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

    private var lastMenuProximity = false

    /** Thread for testing proximity of the cursor/pointer to the wrist menu.
     * Switches from cursor to pointer if the bounding spheres overlap. */
    fun launchMenuPointerIntersectionThread() {
        logger.info("Launched pointer menu intersection thread")
        while (sciview.running) {
            if (::leftWristMenu.isInitialized) {
                if (cursor.cursorPointer.boundingBox != null && leftWristMenu.boundingBox != null) {
                    var intersects = leftWristMenu.boundingBox!!.intersects(cursor.cursorSphere.boundingBox!!)
                    // Only intersect if the menu is visible
                    intersects = intersects && leftWristMenu.isMenuVisible
                    // No need to call the switch logic every iteration, only when the state changes
                    if (intersects != lastMenuProximity) {
                        cursor.switchSphereAndPointer(wantPointer = intersects)
                        lastMenuProximity = intersects
                    }
                }
            }
            Thread.sleep(50)
        }
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
            sqrt(sphereDirection.x * sphereDirection.x + sphereDirection.y * sphereDirection.y + sphereDirection.z * sphereDirection.z) - sphere.radius

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
                spine.instancedProperties["Color"] =
                    { Vector4f(confidence, timepoint.toFloat() / volume.timepointCount, count.toFloat(), 1.0f) }
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
     * Stops the current VR tracking environment and restore the original state.
     * This method should be overridden if functionality is extended, to make sure any extra objects are also deleted.
     */
    open fun stop() {
        logger.debug("Objects in the scene: ${sciview.allSceneNodes.map { it.name }}")
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

        if (::thumbstickMovement.isInitialized) {
            thumbstickMovement.deregisterBehavior()
        }

        logger.info("Cleaned up basic VR objects. Objects left: ${sciview.allSceneNodes.map { it.name }}")

        sciview.toggleVRRendering()

        if (isLensingActive) {
            isLensingActive = false
            fileLogger.endLensing()
        }
        logger.info("Shut down and disabled VR environment.")
        manvr3d.rebuildGeometry()
    }

}
