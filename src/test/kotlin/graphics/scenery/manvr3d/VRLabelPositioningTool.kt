package graphics.scenery.manvr3d

import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.TrackedDevice
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.manvr3d.util.CellTrackingButtonMapper
import graphics.scenery.primitives.TextBoard
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.xyzw
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.ui.behaviour.DragBehaviour
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Standalone VR tool for interactively positioning controller button labels.
 *
 * Cycles through all [CellTrackingButtonMapper] mappings that have a label assigned for the
 * target hand (right by default, left when [leftHandMode] is true).
 *
 * Controls:
 *  - **Grab button (Side) on the HELPER controller**: Hold to drag the current label in 3D space.
 *    Releasing places it at that position.
 *  - **Trigger on the HELPER controller**: Confirms and logs the current label's local position
 *    and rotation relative to the controller model, then advances to the next label.
 *  - **A button on the HELPER controller**: Skip the current label without logging.
 *
 * In RIGHT-hand mode the right controller carries the labels and the LEFT controller is the helper.
 * In LEFT-hand  mode the left  controller carries the labels and the RIGHT controller is the helper.
 *
 * Logged output looks like:
 *   [LABEL RESULT] "CT" offset=Vector3f(x, y, z)  rotation=Quaternionf(x=…, y=…, z=…, w=…)
 * Copy these values directly into [CellTrackingButtonMapper].
 *
 * Transparency notice: this class was mainly developed by Claude
 */
class VRLabelPositioningTool(
    /** When true, positions labels on the LEFT controller using the RIGHT controller as helper. */
    val leftHandMode: Boolean = false
) : SceneryBase(
    "VR Label Positioning Tool",
    windowWidth = 1280,
    windowHeight = 720
) {

    private lateinit var hmd: OpenVRHMD

    // The controller that wears the labels being positioned
    private var labelController: TrackedDevice? = null
    // The controller the user operates to move/confirm labels
    private var helperController: TrackedDevice? = null

    private val labelRole   = if (leftHandMode) TrackerRole.LeftHand  else TrackerRole.RightHand
    private val helperRole  = if (leftHandMode) TrackerRole.RightHand else TrackerRole.LeftHand


    // Build the ordered list of mappings we want to position
    // We only look at mappings that target the correct hand and have a label
    private val targetMappings by lazy {
        CellTrackingButtonMapper.mapper.getCurrentMappings()
            ?.values?.filter { it.role == labelRole && it.label != null }?.toList() ?: emptyList()
    }

    private var currentIndex = 0

    /** The TextBoard currently being positioned. */
    private var activeBoard: TextBoard? = null

    /** Whether the helper's grab button is held, meaning the label follows the helper tip. */
    @Volatile private var grabbing = false

    /** Offset of the grab contact point relative to the helper controller tip at grab start. */
    private var grabOffset = Vector3f()


    // Instruction board shown in the world (not on a controller)

    private lateinit var instructionBoard: TextBoard
    private lateinit var progressBoard: TextBoard

    override fun init() {
        hmd = OpenVRHMD(useCompositor = true)

        if (!hmd.initializedAndWorking()) {
            logger.error("No OpenVR-compatible HMD found. Exiting.")
            exitProcess(1)
        }

        hub.add(SceneryElement.HMDInput, hmd)
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        renderer?.toggleVR()

        // Camera
        val cam = DetachedHeadCamera(hmd)
        cam.spatial { position = Vector3f(0f, 0f, 0f) }
        cam.perspectiveCamera(50f, windowWidth, windowHeight)
        scene.addChild(cam)

        // Environment shell
        val shell = Box(Vector3f(20f, 20f, 20f), insideNormals = true)
        shell.ifMaterial {
            cullingMode = Material.CullingMode.Front
            diffuse = Vector3f(0.15f, 0.15f, 0.18f)
        }
        shell.name = "Shell"
        scene.addChild(shell)

        // Lights
        val lights = Light.createLightTetrahedron<PointLight>(
            Vector3f(0f, 0f, 0f), spread = 5f, radius = 15f, intensity = 5f
        )
        lights.forEach { scene.addChild(it) }

        // World-space instruction board (floats ~1 m in front of origin)
        instructionBoard = TextBoard(inFront = true).apply {
            name = "InstructionBoard"
            text = buildInstructionText()
            fontColor = Vector4f(0.85f, 0.85f, 1f, 1f)
            spatial {
                position = Vector3f(0f, 0.3f, -1.2f)
                scale = Vector3f(0.25f)
            }
        }
        scene.addChild(instructionBoard)

        progressBoard = TextBoard(inFront = true).apply {
            name = "ProgressBoard"
            text = buildProgressText()
            fontColor = Vector4f(0.6f, 1f, 0.7f, 1f)
            spatial {
                position = Vector3f(0f, 0f, -1.2f)
                scale = Vector3f(0.20f)
            }
        }
        scene.addChild(progressBoard)

        // Wait for controllers to connect
        thread {
            while (!running) Thread.sleep(100)
            hmd.events.onDeviceConnect.add { hmd, device, timestamp ->
                if (device.type == TrackedDeviceType.Controller) {
                    logger.info("Controller connected: ${device.name} role=${device.role} at $timestamp")
                    device.model?.let { hmd.attachToNode(device, it, cam) }

                    when (device.role) {
                        TrackerRole.RightHand -> {
                            if (helperRole == TrackerRole.RightHand) {
                                helperController = device
                                logger.info("Right hand assigned as HELPER controller")
                            } else {
                                labelController = device
                                logger.info("Right hand assigned as LABEL controller")
                                onLabelControllerReady()
                            }
                        }
                        TrackerRole.LeftHand -> {
                            if (helperRole == TrackerRole.LeftHand) {
                                helperController = device
                                logger.info("Left hand assigned as HELPER controller")
                            } else {
                                labelController = device
                                logger.info("Left hand assigned as LABEL controller")
                                onLabelControllerReady()
                            }
                        }
                        else -> {}
                    }

                    // Attach an update hook to the helper controller for continuous grab movement
                    if (device.role == helperRole) {
                        device.model?.let { helperModel ->
                            helperModel.update.add {
                                if (grabbing) {
                                    updateGrabbedLabelPosition(helperModel)
                                }
                            }
                        }
                    }
                }
            }
        }

        logger.info("=== VR Label Positioning Tool ===")
        logger.info("Mode: ${if (leftHandMode) "LEFT-hand labels" else "RIGHT-hand labels"}")
        logger.info("Found ${targetMappings.size} labelled mappings to position.")
        targetMappings.forEachIndexed { i, m ->
            logger.info("  [$i] \"${m.label}\" on ${m.role} ${m.button}")
        }
        if (targetMappings.isEmpty()) {
            logger.warn("No labelled mappings found for $labelRole! Nothing to do.")
        }
    }

    // Called once the label controller model is available – attach the first board
    private fun onLabelControllerReady() {
        if (targetMappings.isEmpty()) return
        attachBoardAtIndex(currentIndex)
        updateProgressText()
    }


    // Create and attach a TextBoard for the mapping at [index]
    private fun attachBoardAtIndex(index: Int) {
        val labelControllerModel = labelController?.model ?: return
        if (index >= targetMappings.size) {
            logger.info("All labels positioned! Exiting tool.")
            progressBoard.text = "ALL DONE! Check logs for output."
            return
        }

        val mapping = targetMappings[index]

        // Remove any previously active board
        activeBoard?.let { old ->
            labelControllerModel.removeChild(old)
        }

        // Create a new board with sensible defaults; inherit existing offset/rotation if present
        val board = TextBoard(inFront = true).apply {
            name = "Label_${mapping.label}"
            text = mapping.label ?: "?"
            fontColor = mapping.color?.xyzw() ?: Vector4f(0.9f)
            spatial {
                // Start from existing stored offset, or a sane default right in front of the controller tip
                position = mapping.offset ?: Vector3f(0f, 0.02f, 0.08f)
                rotation = mapping.rotation ?: Quaternionf()
                scale = Vector3f(0.04f)
            }
        }

        labelControllerModel.addChild(board)
        activeBoard = board

        logger.info(">>> Positioning label [${index + 1}/${targetMappings.size}]: \"${mapping.label}\" (${mapping.button})")
    }

    // called when helper's Side button is pressed
    private fun startGrab() {
        val board = activeBoard ?: return
        val helperModel = helperController?.model ?: return

        // Record the vector from the helper tip's world position to the board's world position
        // so the board doesn't "snap" when we start dragging
        val helperWorldPos = helperModel.spatialOrNull()?.position ?: Vector3f()
        val boardWorldPos  = board.spatialOrNull()?.worldPosition() ?: Vector3f()
        grabOffset = boardWorldPos - helperWorldPos
        grabbing = true
        logger.debug("Grab started. helperPos=$helperWorldPos boardWorldPos=$boardWorldPos offset=$grabOffset")
    }

    private fun endGrab() {
        grabbing = false
        logger.debug("Grab released. Label is now at local position: ${activeBoard?.spatialOrNull()?.position}")
    }

    // Called every frame while grabbing, from the helper controller's update hook
    private fun updateGrabbedLabelPosition(helperModel: Node) {
        val board = activeBoard ?: return
        val labelModel = labelController?.model ?: return

        // Desired world position of the board = helper tip + original grab offset
        val helperWorldPos = helperModel.spatialOrNull()?.worldPosition() ?: return
        val desiredWorldPos = helperWorldPos + grabOffset

        // Convert to local space of the label controller model
        val labelWorldInverse = labelModel.spatialOrNull()?.world?.invert(org.joml.Matrix4f()) ?: return
        val desiredLocalPos = labelWorldInverse.transformPosition(desiredWorldPos, Vector3f())

        board.spatialOrNull()?.position = desiredLocalPos
    }

    // log the current local offset and advance to next label
    private fun confirmAndAdvance() {
        val board = activeBoard ?: run {
            logger.warn("No active board to confirm.")
            return
        }
        val mapping = targetMappings.getOrNull(currentIndex) ?: return

        val localPos = board.spatialOrNull()?.position ?: Vector3f()
        val localRot = board.spatialOrNull()?.rotation ?: Quaternionf()

        // Pretty-print for direct copy-paste into CellTrackingButtonMapper
        logger.info("=== [LABEL RESULT] \"${mapping.label}\" (${mapping.role} / ${mapping.button}) ===")
        logger.info("  offset   = Vector3f(${localPos.x}f, ${localPos.y}f, ${localPos.z}f)")
        logger.info("  rotation = Quaternionf(${localRot.x}f, ${localRot.y}f, ${localRot.z}f, ${localRot.w}f)")
        logger.info("  -- ButtonMapping entry:")
        logger.info("     ${CellTrackingButtonMapper.mapper.getCurrentMappings()?.entries?.find { it.value === mapping }?.key
            ?: "<action>"} to ButtonMapping(")
        logger.info("         TrackerRole.${mapping.role},")
        logger.info("         OpenVRButton.${mapping.button},")
        logger.info("         label = \"${mapping.label}\",")
        logger.info("         offset = Vector3f(${localPos.x}f, ${localPos.y}f, ${localPos.z}f),")
        logger.info("         rotation = Quaternionf(${localRot.x}f, ${localRot.y}f, ${localRot.z}f, ${localRot.w}f)")
        logger.info("     )")

        currentIndex++
        updateProgressText()
        attachBoardAtIndex(currentIndex)
    }

    private fun skipCurrent() {
        val mapping = targetMappings.getOrNull(currentIndex) ?: return
        logger.info("Skipped label \"${mapping.label}\"")
        currentIndex++
        updateProgressText()
        attachBoardAtIndex(currentIndex)
    }

    private fun buildInstructionText(): String {
        val helperHandName = if (leftHandMode) "RIGHT" else "LEFT"
        return """
            VR Label Positioning Tool
            [$helperHandName controller]
              Hold SIDE  → grab & move label
              TRIGGER    → confirm & log position
              A button   → skip label
        """.trimIndent()
    }

    private fun buildProgressText(): String {
        return if (targetMappings.isEmpty()) "No labels to position."
        else "Label ${currentIndex + 1} / ${targetMappings.size}: \"${targetMappings.getOrNull(currentIndex)?.label ?: "DONE"}\""
    }

    private fun updateProgressText() {
        progressBoard.text = buildProgressText()
    }

    override fun inputSetup() {
        super.inputSetup()

        // Grab – Side button on the helper controller
        hmd.addBehaviour("grab_label", object : DragBehaviour {
            override fun init(x: Int, y: Int)  { startGrab() }
            override fun drag(x: Int, y: Int)  { /* position update handled in controller update hook */ }
            override fun end(x: Int, y: Int)   { endGrab() }
        })
        hmd.addKeyBinding("grab_label", helperRole, OpenVRHMD.OpenVRButton.Side)

        // Confirm – Trigger on the helper controller
        hmd.addBehaviour("confirm_label", ClickBehaviour { _, _ -> confirmAndAdvance() })
        hmd.addKeyBinding("confirm_label", helperRole, OpenVRHMD.OpenVRButton.Trigger)

        // Skip – A button on the helper controller
        hmd.addBehaviour("skip_label", ClickBehaviour { _, _ -> skipCurrent() })
        hmd.addKeyBinding("skip_label", helperRole, OpenVRHMD.OpenVRButton.A)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val leftHandMode = args.contains("--left") || args.contains("-l")
            VRLabelPositioningTool(leftHandMode = leftHandMode).main()
        }
    }
}