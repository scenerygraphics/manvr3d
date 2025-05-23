@file:Suppress("UNCHECKED_CAST")

package org.mastodon.mamut

import bdv.viewer.Source
import bdv.viewer.SourceAndConverter
import graphics.scenery.*
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.controls.behaviours.WithCameraDelegateBase
import graphics.scenery.primitives.Cylinder
import graphics.scenery.primitives.TextBoard
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.utils.lazyLogger
import graphics.scenery.volumes.BufferedVolume
import graphics.scenery.volumes.RAIVolume
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import net.imglib2.RandomAccessibleInterval
import net.imglib2.loops.LoopBuilder
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.view.Views
import org.elephant.actions.NearestNeighborLinkingAction
import org.elephant.actions.PredictSpotsAction
import org.elephant.actions.TrainDetectionAction
import org.elephant.setting.main.ElephantMainSettingsManager
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.mastodon.adapter.TimepointModelAdapter
import org.mastodon.mamut.model.Link
import org.mastodon.mamut.model.Spot
import org.mastodon.mamut.views.bdv.MamutViewBdv
import org.mastodon.model.tag.TagSetStructure
import org.mastodon.ui.coloring.DefaultGraphColorGenerator
import org.mastodon.ui.coloring.GraphColorGenerator
import org.mastodon.ui.coloring.TagSetGraphColorGenerator
import org.scijava.event.EventService
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.ui.behaviour.DragBehaviour
import org.scijava.ui.behaviour.util.Actions
import sc.iview.SciView
import sc.iview.commands.demo.advanced.CellTrackingBase
import sc.iview.commands.demo.advanced.EyeTracking
import sc.iview.commands.demo.advanced.TimepointObserver
import util.SphereLinkNodes
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.Action
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource


class SciviewBridge: TimepointObserver {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))
    //data source stuff
    val mastodon: ProjectModel
    var sourceID = 0
    var sourceResLevel = 0
    /** default intensity parameters */
    var intensity = Intensity()

    /** Collection of parameters for value and color intensity mapping */
    data class Intensity(
        var contrast: Float = 1.0f,         // raw data multiplier
        var shift: Float = 0.0f,            // raw data shift
        var clampTop: Float = 65535.0f,    // upper clamp value
        var gamma: Float = 1.0f,            // gamma correction with exp()
        var rangeMin: Float = 0f,
        var rangeMax: Float = 5000f,
    )

    var updateVolAutomatically = true

    override fun toString(): String {
        val sb = StringBuilder("Mastodon-sciview bridge internal settings:\n")
        sb.append("   SOURCE_ID = $sourceID\n")
        sb.append("   SOURCE_USED_RES_LEVEL = $sourceResLevel\n")
        sb.append("   INTENSITY_CONTRAST = ${intensity.contrast}\n")
        sb.append("   INTENSITY_SHIFT = ${intensity.shift}\n")
        sb.append("   INTENSITY_CLAMP_AT_TOP = ${intensity.clampTop}\n")
        sb.append("   INTENSITY_GAMMA = ${intensity.gamma}\n")
        sb.append("   INTENSITY_RANGE_MAX = ${intensity.rangeMax}\n")
        sb.append("   INTENSITY_RANGE_MIN = ${intensity.rangeMin}\n")
        return sb.toString()
    }

    //data sink stuff
    val sciviewWin: SciView
    val sphereLinkNodes: SphereLinkNodes
    //sink scene graph structuring nodes
    val axesParent: Node?

    // Worker queue for async 3D updating
    private val updateQueue = LinkedBlockingQueue<() -> Unit>()
    private val workerExecutor = Executors.newSingleThreadExecutor { thread ->
        Thread(thread, "SphereLinkUpdateWorker").apply { isDaemon = true }
    }

    var volumeNode: Volume
    val volumeTPWidget = TextBoard()
    var spimSource: Source<out Any>
    // the source and converter that contains our volume data
    var sac: SourceAndConverter<*>
    var isVolumeAutoAdjust = false
    val sceneScale: Float = 10f
    // keep track of the currently selected spot globally so that edit behaviors can access it
    var selectedSpotInstances = mutableListOf<InstancedNode.Instance>()
    // the event watcher for BDV, needed here for the lock handling to prevent BDV from
    // triggering the event watcher while a spot is edited in Sciview
    var bdvNotifier: BdvNotifier? = null
    var moveSpotInSciview: (Spot?) -> Unit?
    var associatedUI: SciviewBridgeUIMig? = null
    var uiFrame: JFrame? = null
    private var isRunning = true
    private var isVRactive = false

    var VRTracking: CellTrackingBase? = null
    private var adjacentEdges: MutableList<Link> = ArrayList()
    private var moveInstanceVRInit: (Vector3f) -> Unit
    private var moveInstanceVRDrag: (Vector3f) -> Unit
    private var moveInstanceVREnd: (Vector3f) -> Unit
//    private var resetControllerTrack: () -> Unit

    private val pluginActions: Actions
    private val predictSpotsAction: Action
    private val predictSpotsCallback: ((all: Boolean) -> Unit)
    private val trainSpotsAction: Action
    private val trainsSpotsCallback: (() -> Unit)
//    private val trainFlowAction: Action
//    private val trainFlowCallback: (() -> Unit)
    private val neighborLinkingAction: Action
    private val neighborLinkingCallback: (() -> Unit)
    private val stageSpotsCallback: (() -> Unit)

    constructor(
        mastodonMainWindow: ProjectModel,
        targetSciviewWindow: SciView
    ) : this(mastodonMainWindow, 0, 0, targetSciviewWindow)

    constructor(
        mastodonMainWindow: ProjectModel,
        sourceID: Int,
        sourceResLevel: Int,
        targetSciviewWindow: SciView
    ) {
        mastodon = mastodonMainWindow
        sciviewWin = targetSciviewWindow
        sciviewWin.setPushMode(true)
        detachedDPP_withOwnTime = DPP_DetachedOwnTime(
            mastodon.minTimepoint,
            mastodon.maxTimepoint
        )

        //adjust the default scene's settings
        sciviewWin.applicationName = ("sciview for Mastodon: " + mastodon.projectName)
        sciviewWin.toggleSidebar()
        sciviewWin.floor?.visible = false
        sciviewWin.lights?.forEach { l: PointLight ->
            if (l.name.startsWith("headli")) adjustHeadLight(l)
        }
        sciviewWin.camera?.children?.forEach { l: Node ->
            if (l.name.startsWith("headli") && l is PointLight) adjustHeadLight(l)
        }
        sciviewWin.addNode(AmbientLight(0.05f, Vector3f(1f, 1f, 1f)))

        //add "root" with data axes
        axesParent = constructDataAxes()
        sciviewWin.addNode(axesParent, activePublish = false)

        //get necessary metadata - from image data
        this.sourceID = sourceID
        this.sourceResLevel = sourceResLevel
        sac = mastodon.sharedBdvData.sources[this.sourceID]
        spimSource = sac.spimSource
        // number of pixels for each dimension at the highest res level
        val volumeDims = spimSource.getSource(0, 0).dimensionsAsLongArray()    // TODO rename to something more meaningful
        // number of pixels for each dimension of the volume at current res level
        val volumeNumPixels = spimSource.getSource(0, this.sourceResLevel).dimensionsAsLongArray()
        val volumeDownscale = Vector3f(
            volumeDims[0].toFloat() / volumeNumPixels[0].toFloat(),
            volumeDims[1].toFloat() / volumeNumPixels[1].toFloat(),
            volumeDims[2].toFloat() / volumeNumPixels[2].toFloat()
        )
        logger.info("downscale factors: ${volumeDownscale[0]} x, ${volumeDownscale[1]} x, ${volumeDownscale[2]} x")
        logger.info("number of mipmap levels: ${spimSource.numMipmapLevels}, available timepoints: ${mastodon.sharedBdvData.numTimepoints}")

        volumeNode = sciviewWin.addVolume(
            sac as SourceAndConverter<UnsignedShortType>,
            mastodon.sharedBdvData.numTimepoints,
            "volume",
            floatArrayOf(1f, 1f, 1f)
        )
        logger.info("current mipmap range: ${volumeNode.multiResolutionLevelLimits}")

        while (!volumeNode.volumeManager.readyToRender()) {
            Thread.sleep(20)
        }

        setMipmapLevel(this.sourceResLevel)
        setVolumeRanges(
            volumeNode,
            "Grays.lut",
            Vector3f(sceneScale),
            intensity.rangeMin,
            intensity.rangeMax
        )

        // flip Z axis to align it with the synced BDV view
        volumeNode.spatial().scale *= Vector3f(1f, 1f, -1f)

        centerCameraOnVolume()

        logger.info("volume node scale is ${volumeNode.spatialOrNull()?.scale}")

        logger.info("volume size is ${volumeNode.boundingBox!!.max - volumeNode.boundingBox!!.min}")
        //add the sciview-side displaying handler for the spots
        sphereLinkNodes = SphereLinkNodes(sciviewWin, this, updateQueue, mastodon, volumeNode, volumeNode)

        sphereLinkNodes.showInstancedSpots(0, noTSColorizer)
        sphereLinkNodes.showInstancedLinks(SphereLinkNodes.ColorMode.LUT, colorizer = noTSColorizer)

        // lambda function that is passed to the event handler and called
        // when a vertex position change occurs on the BDV side
        moveSpotInSciview = { spot: Spot? ->
            spot?.let {
                selectedSpotInstances.clear()
                sphereLinkNodes.findInstanceFromSpot(spot)?.let { selectedSpotInstances.add(it) }
                sphereLinkNodes.moveAndScaleSpotInSciview(spot) }
        }

        var currentControllerPos = Vector3f()

        // Three lambdas that are passed to the sciview class to handle the three drag behavior stages with controllers
        moveInstanceVRInit = fun (pos: Vector3f) {

            if (mastodon.selectionModel.selectedVertices == null) {
                selectedSpotInstances.clear()
                return
            } else {
                selectedSpotInstances.forEach { inst ->
                    logger.debug("selected spot instance is $inst")
                    if (inst == null) {
                        return@forEach
                    }
                    val spot = sphereLinkNodes.findSpotFromInstance(inst)
                    val selectedTP = spot?.timepoint ?: -1
                    if (selectedTP != volumeNode.currentTimepoint) {
                        selectedSpotInstances.clear()
                        logger.warn("Tried to move a spot that was outside the current timepoint. Aborting.")
                        return
                    } else {
                        bdvNotifier?.lockUpdates = true
                        currentControllerPos = sciviewToMastodonCoords(pos)
                        spot?.let { s ->
                            adjacentEdges.addAll(s.incomingEdges())
                            adjacentEdges.addAll(s.outgoingEdges())
                            logger.info("Moving ${s.incomingEdges().size()} incoming and ${s.outgoingEdges().size()} outgoing edges for spot $s.")
                            logger.info("adjacentEdges are ${adjacentEdges.map { it.internalPoolIndex }.joinToString(", ")}")
                        }
                    }
                }
            }
        }

        moveInstanceVRDrag = fun (pos: Vector3f) {
            selectedSpotInstances.forEach {
                val newPos = sciviewToMastodonCoords(pos)
                val movement = newPos - currentControllerPos
                it.spatial {
                    position += movement
                }
                currentControllerPos = newPos
                sphereLinkNodes.moveSpotInBDV(it, movement)
                sphereLinkNodes.updateLinkTransforms(adjacentEdges)
            }
        }

        moveInstanceVREnd = fun (pos: Vector3f) {
            bdvNotifier?.lockUpdates = false
            sphereLinkNodes.showInstancedSpots(detachedDPP_showsLastTimepoint.timepoint,
                detachedDPP_showsLastTimepoint.colorizer)
            adjacentEdges.clear()
        }

        pluginActions = mastodon.plugins.pluginActions

        predictSpotsAction = pluginActions.actionMap.get("[elephant] predict spots")
        predictSpotsCallback = { predictAll ->
            predictSpotsAction?.let {
                // Limitation of Elephant: we can only predict X number of frames in the past
                // So we have to temporarily move to the last TP and set the time range to the size of all TPs
                val settings = ElephantMainSettingsManager.getInstance().forwardDefaultStyle
                settings.timeRange = if (predictAll) volumeNode.timepointCount else 1
                logger.info("Elephant settings.timeRange was set to ${settings.timeRange}.")
                val start = TimeSource.Monotonic.markNow()
                logger.info("Predicting spots for this timepoint...")
                val currentTP = detachedDPP_showsLastTimepoint.timepoint
                val groupHandle = mastodon.groupManager.createGroupHandle()
                groupHandle.groupId = 0
                val tpAdapter = TimepointModelAdapter(groupHandle.getModel(mastodon.TIMEPOINT))

                if (predictAll) {
                    tpAdapter.timepoint = volumeNode.timepointCount
                }
                (it as PredictSpotsAction).run()
                logger.info("Predicting spots took ${start.elapsedNow()} ms")
                if (predictAll) {
                    tpAdapter.timepoint = currentTP
                }
                sphereLinkNodes.showInstancedSpots(detachedDPP_showsLastTimepoint.timepoint,
                    detachedDPP_showsLastTimepoint.colorizer)
                sciviewWin.camera?.showMessage("Prediction took ${start.elapsedNow()} ms", 2f, 0.2f, centered = true)
            }
        }

        trainSpotsAction = pluginActions.actionMap.get("[elephant] train detection model (all timepoints)")
        trainsSpotsCallback = {
            trainSpotsAction?.let {
                val start = TimeSource.Monotonic.markNow()
                logger.info("Training spots from all timepoints...")
                (it as TrainDetectionAction).run()
                logger.info("Training spots took ${start.elapsedNow()} ms")
                sciviewWin.camera?.showMessage("Training took ${start.elapsedNow()} ms", 2f, 0.2f, centered = true)
            }
        }

        neighborLinkingAction = pluginActions.actionMap.get("[elephant] nearest neighbor linking")
        neighborLinkingCallback = {
            neighborLinkingAction?.let {
                logger.info("Linking nearest neighbors...")
                (it as NearestNeighborLinkingAction).run()
                sciviewWin.camera?.showMessage("Linked nearest neighbors.", 2f, 0.2f, centered = true)
            }
        }

        stageSpotsCallback = {
            logger.info("Adding all spots to the true positive tag set...")
            val tagResult = sphereLinkNodes.applyTagToAllSpots("Detection", "tp")
            if (!tagResult) {
                logger.warn("Could not find tag or tag set! Please ensure both exist.")
            } else {
                sphereLinkNodes.showInstancedSpots(
                    detachedDPP_showsLastTimepoint.timepoint,
                    detachedDPP_showsLastTimepoint.colorizer)
            }
        }

        openSyncedBDV()

        registerKeyboardHandlers()

        startWorker()
    }

    val eventService: EventService?
        get() = sciviewWin.scijavaContext?.getService(EventService::class.java)

    /** Launches a worker thread that sequentially executes queued spot and link updates from [SphereLinkNodes]. */
    private fun startWorker() {
        workerExecutor.submit {
            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    // Timeout instead of blocking to allow for shutdown
                    val task = updateQueue.poll(1, TimeUnit.SECONDS)
                    task?.invoke()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn("Interrupted while waiting for update task to finish!")
                    break
                } catch (e: Exception) {
                    logger.error("Error while waiting for update task to finish: ", e)
                }
            }
        }
    }

    /** Centers the camera on the volume and adjusts its distance to fully fit the volume into the camera's FOV. */
    private fun centerCameraOnVolume() {
        // get the extend of the volume in sciview coordinates
        val volSize = (volumeNode.boundingBox!!.max - volumeNode.boundingBox!!.min) * volumeNode.pixelToWorldRatio * sceneScale
        val hFOVRad = Math.toRadians((sciviewWin.camera?.fov ?: 70f).toDouble())
        val aspectRatio = sciviewWin.camera?.aspectRatio() ?: 1f
        val vFOVRad = 2 * atan(tan(hFOVRad / 2.0) / aspectRatio)
        // calculate the maximum distances for vertical and horizontal FOV
        val distanceHeight = (volSize.y / 2f) / tan(vFOVRad / 2.0)
        val distanceWidth = (volSize.x / 2f) / tan(hFOVRad / 2.0)
        val maxDistance = max(distanceWidth, distanceHeight) * 1.2f // add a little margin

        sciviewWin.camera?.spatial {
            rotation = Quaternionf().lookAlong(Vector3f(0f, 0f, 1f), Vector3f(0f, 1f, 0f))
            position = Vector3f(0f, 0f, maxDistance.toFloat())
        }
    }

    fun close() {
        stopAndDetachUI()
        deregisterKeyboardHandlers()
        logger.info("Mastodon-sciview Bridge closing procedure: UI and keyboard handlers are removed now")
        sciviewWin.setActiveNode(axesParent)
        logger.info("Mastodon-sciview Bridge closing procedure: focus shifted away from our nodes")
        val updateGraceTime = 100L // in ms
        try {
            sciviewWin.deleteNode(volumeNode, true)
            logger.debug("Mastodon-sciview Bridge closing procedure: red volume removed")
            Thread.sleep(updateGraceTime)
//            sciviewWin.deleteNode(sphereParent, true)
            logger.debug("Mastodon-sciview Bridge closing procedure: spots were removed")
        } catch (e: InterruptedException) { /* do nothing */
        }
        sciviewWin.deleteNode(axesParent, true)
    }

    /** Convert a [Vector3f] from sciview space into Mastodon's voxel coordinate space,
     * taking the volume's transforms into account. This method assumes the volume has a centered origin. */
    fun sciviewToMastodonCoords(v: Vector3f) : Vector3f {

        val localCoords = Vector3f(v)
        localCoords.sub(volumeNode.spatial().position)
        Quaternionf(volumeNode.spatial().rotation).conjugate().transform(localCoords)
        // Normalize the scale factor, because the volume node isn't scale 1 per default
        val scaleFactor = Vector3f(volumeNode.spatial().scale).div(sceneScale).mul(1f, 1f, -1f)
        localCoords.div(scaleFactor)
        localCoords.div(volumeNode.pixelToWorldRatio)
        localCoords.div(sceneScale)
        // Flip Y and Z axes to match Mastodon's coordinate system
        localCoords.mul(1f, -1f, -1f)
        // Add offset to center coordinates
        val offset = volumeNode.boundingBox!!.max * 0.5f
        localCoords.add(offset)
        return localCoords
    }

    /** Convert a [Vector3f] from Mastodon's voxel coordinate space into sciview space,
     * taking the volume's transforms into account. This assumes the volume has a centered origin. */
    fun mastodonToSciviewCoords(v: Vector3f) : Vector3f {

        val globalCoords = Vector3f(v)
        val offset = volumeNode.boundingBox!!.max * 0.5f
        globalCoords.sub(offset)
        globalCoords.div(Vector3f(1f, -1f, -1f))
        globalCoords.mul(sceneScale)
        globalCoords.mul(volumeNode.pixelToWorldRatio)
        val scaleFactor = Vector3f(volumeNode.spatial().scale).div(sceneScale).mul(1f, 1f, -1f)
        globalCoords.mul(scaleFactor)
        Quaternionf(volumeNode.spatial().rotation).conjugate().transform(globalCoords)
        globalCoords.add(volumeNode.spatial().position)

        return globalCoords
    }

    /** Adds a volume to the sciview scene, scales it by [scale], adjusts the transfer function to a ramp from [0, 0] to [1, 1]
     * and sets the node children visibility to false. */
    private fun setVolumeRanges(
        v: Volume?,
        colorMapName: String,
        scale: Vector3f,
        displayRangeMin: Float,
        displayRangeMax: Float
    ) {
        v?.let {
            sciviewWin.setColormap(it, colorMapName)
            it.spatial().scale = scale
            it.minDisplayRange = displayRangeMin
            it.maxDisplayRange = displayRangeMax
            val tf = TransferFunction()
            tf.addControlPoint(0f, 0f)
            tf.addControlPoint(1f, 0.5f)
            it.transferFunction = tf
            //make Bounding Box Grid invisible
            it.children.forEach { n: Node -> n.visible = false }
        }
    }

    /** We backup the current contrast/min/max values so that we can revert back if we toggle off the auto intensity */
    private var intensityBackup = intensity.copy()

    /** Makes an educated guess about the value range of the volume and adjusts the min/max range values accordingly. */
    fun autoAdjustIntensity() {
        // toggle boolean state
        isVolumeAutoAdjust = !isVolumeAutoAdjust

        if (isVolumeAutoAdjust) {
            var maxVal = 0.0f
            val srcImg = spimSource.getSource(0, sourceResLevel) as RandomAccessibleInterval<UnsignedShortType>
            Views.iterable(srcImg).forEach { px -> maxVal = maxVal.coerceAtLeast(px.realFloat) }
            intensity.clampTop = 0.9f * maxVal //very fake 90% percentile...
            intensity.rangeMin = maxVal * 0.15f
            intensity.rangeMax = maxVal * 0.75f
            //TODO: change MIN and MAX to proper values
            logger.debug("Clamp at ${intensity.clampTop}," +
                    " range min to ${intensity.rangeMin} and range max to ${intensity.rangeMax}")
            updateSciviewTPfromBDV(force = true)
            updateUI()
        } else {
            intensity = intensityBackup.copy()
            updateSciviewTPfromBDV(force = true)
            updateUI()
        }
    }

    // TODO for now this is not used because it introduces lag to timeline scrubbing. Should maybe be done on the GPU instead?
    /** Change voxel values based on the intensity values like contrast, shift, gamma, etc. */
    fun <T : IntegerType<T>?> volumeIntensityProcessing(
        srcImg: RandomAccessibleInterval<T>?
    ) {
        logger.info("started volumeIntensityProcessing...")
        val gammaEnabledIntensityProcessor: (T) -> Unit =
            { src: T -> src?.setReal(
                    intensity.clampTop * ( //TODO, replace pow() with LUT for several gammas
                            min(
                                intensity.contrast * src.realFloat + intensity.shift,
                                intensity.clampTop
                            ) / intensity.clampTop
                        ).pow(intensity.gamma)
                    )
            }
        val noGammaIntensityProcessor: (T) -> Unit =
            { src: T -> src?.setReal(
                        min(
                            // TODO This needs to incorporate INTENSITY_RANGE_MIN and MAX
                            intensity.contrast * src.realFloat + intensity.shift,
                            intensity.clampTop
                        )
                    )
            }

        // choose processor depending on the gamma value selected
        val intensityProcessor = if (intensity.gamma != 1.0f)
            gammaEnabledIntensityProcessor else noGammaIntensityProcessor

        if (srcImg == null) logger.warn("volumeIntensityProcessing: srcImg is null !!!")

        // apply processor lambda to each pixel using ImgLib2
        LoopBuilder.setImages(srcImg)
            .multiThreaded()
            .forEachPixel(intensityProcessor)

    }

    /** Overload that implicitly uses the existing [spimSource] for [volumeIntensityProcessing] */
    fun volumeIntensityProcessing() {
        val srcImg = spimSource.getSource(detachedDPP_withOwnTime.timepoint, sourceResLevel) as RandomAccessibleInterval<UnsignedShortType>
        volumeIntensityProcessing(srcImg)
    }

    private var bdvWinParamsProvider: DisplayParamsProvider? = null

    /** Create a BDV window and launch a [BdvNotifier] instance to synchronize time point and viewing direction. */
    fun openSyncedBDV() {
        val bdvWin = mastodon.windowManager.createView(MamutViewBdv::class.java)
        bdvWin.frame.setTitle("BDV linked to ${sciviewWin.getName()}")
            //initial spots content:
            bdvWinParamsProvider = DPP_BdvAdapter(bdvWin)
            bdvWinParamsProvider?.let {
                updateSciviewContent(it)
                bdvNotifier = BdvNotifier(
                    // time point processor
                    { updateSciviewContent(it) },
                    // view update processor
                    { updateSciviewCameraFromBDV(bdvWin) },
                    // vertex update processor
                    moveSpotInSciview as (Spot?) -> Unit,
                    // graph update processor: redraws track segments and spots
                    {
                        sphereLinkNodes.showInstancedLinks(sphereLinkNodes.currentColorMode, it.colorizer)
                        sphereLinkNodes.showInstancedSpots(it.timepoint, it.colorizer)
                    },
                    mastodon,
                    bdvWin
                )
            }
       }

    private var recentTagSet: TagSetStructure.TagSet? = null
    var recentColorizer: GraphColorGenerator<Spot, Link>? = null
    val noTSColorizer = DefaultGraphColorGenerator<Spot, Link>()

    private fun getCurrentColorizer(forThisBdv: MamutViewBdv): GraphColorGenerator<Spot, Link> {
        //NB: trying to avoid re-creating of new TagSetGraphColorGenerator objs with every new content rending
        val colorizer: GraphColorGenerator<Spot, Link>
        val ts = forThisBdv.coloringModel.tagSet
        if (ts != null) {
            if (ts !== recentTagSet) {
                recentColorizer = TagSetGraphColorGenerator(mastodon.model.tagSetModel, ts)
            }
            colorizer = recentColorizer!!
        } else {
            colorizer = noTSColorizer
        }
        recentTagSet = ts
        return colorizer
    }

    interface DisplayParamsProvider {
        val timepoint: Int
        val colorizer: GraphColorGenerator<Spot, Link>
    }

    internal inner class DPP_BdvAdapter(ofThisBdv: MamutViewBdv) : DisplayParamsProvider {
        val bdv: MamutViewBdv = ofThisBdv
        override val timepoint: Int
            get() = bdv.viewerPanelMamut.state().currentTimepoint
        override val colorizer: GraphColorGenerator<Spot, Link>
            get() = getCurrentColorizer(bdv)
    }

    internal inner class DPP_Detached : DisplayParamsProvider {
        override val timepoint: Int
            get() = lastUpdatedSciviewTP
        override val colorizer: GraphColorGenerator<Spot, Link>
            get() = recentColorizer ?: noTSColorizer
    }

    inner class DPP_DetachedOwnTime(val min: Int, val max: Int) : DisplayParamsProvider {

        override var timepoint = 0
            set(value) {
                field = max(min.toDouble(), min(max.toDouble(), value.toDouble())).toInt()
            }

        fun prevTimepoint() {
            timepoint = max(min.toDouble(), (timepoint - 1).toDouble()).toInt()
        }

        fun nextTimepoint() {
            timepoint = min(max.toDouble(), (timepoint + 1).toDouble()).toInt()
        }

        override val colorizer: GraphColorGenerator<Spot, Link>
            get() = recentColorizer ?: noTSColorizer
    }

    /** Calls [updateSciviewTPfromBDV] and [SphereLinkNodes.showInstancedSpots] to update the current volume and corresponding spots. */
    fun updateSciviewContent(forThisBdv: DisplayParamsProvider) {
        logger.debug("Called updateSciviewContent")
        updateSciviewTPfromBDV(forThisBdv)
//        volumeTPWidget.text = volumeNode.currentTimepoint.toString()
        sphereLinkNodes.showInstancedSpots(forThisBdv.timepoint, forThisBdv.colorizer)
        sphereLinkNodes.updateLinkVisibility(forThisBdv.timepoint)
        sphereLinkNodes.updateLinkColors(forThisBdv.colorizer)
    }

    /** Takes a timepoint and updates the current BDV window's time accordingly. */
    fun updateBDV_TPfromSciview(tp: Int) {
        logger.debug("Updated BDV timepoint from sciview")
        if (bdvWinParamsProvider != null) {
            (bdvWinParamsProvider as DPP_BdvAdapter).bdv.viewerPanelMamut.state().currentTimepoint = tp
        } else {
            logger.warn("BDV window was likely not initialized, can't synchronize sciview timepoint to BDV window!")
        }
    }

    var lastUpdatedSciviewTP = 0
    val detachedDPP_showsLastTimepoint: DisplayParamsProvider = DPP_Detached()

    /** Fetch the volume state at the current time point,
     * then call [volumeIntensityProcessing] to adjust the intensity values */
    @JvmOverloads
    fun updateSciviewTPfromBDV(
        forThisBdv: DisplayParamsProvider = detachedDPP_showsLastTimepoint,
        force: Boolean = false
    ) {

        if (updateVolAutomatically || force) {
            val currTP = forThisBdv.timepoint

            if (currTP != lastUpdatedSciviewTP) {
                lastUpdatedSciviewTP = currTP

                val tp = forThisBdv.timepoint
                volumeNode.goToTimepoint(tp)
            }
        }
    }

    private fun updateSciviewCameraFromBDV(forThisBdv: MamutViewBdv) {
        // Let's not move the camera around when the user is in VR
        if (isVRactive) {
            return
        }
        val auxTransform = AffineTransform3D()
        val viewMatrix = Matrix4f()
        val viewRotation = Quaternionf()
        forThisBdv.viewerPanelMamut.state().getViewerTransform(auxTransform)
        for (r in 0..2) for (c in 0..3) viewMatrix[c, r] = auxTransform[r, c].toFloat()
        viewMatrix.getUnnormalizedRotation(viewRotation)
        val camSpatial = sciviewWin.camera?.spatial() ?: return
        viewRotation.y *= -1f
        viewRotation.z *= -1f
        camSpatial.rotation = viewRotation
        val dist = camSpatial.position.length()
        camSpatial.position = sciviewWin.camera?.forward!!.normalize().mul(-1f * dist)
    }

    fun setVisibilityOfVolume(state: Boolean) {
        volumeNode.visible = state
        if (state) {
            volumeNode.children.stream()
                .filter { c: Node -> c.name.startsWith("Bounding") }
                .forEach { c: Node -> c.visible = false }
        }
    }

    /** Sets the detail level of the volume node. */
    fun setMipmapLevel(level: Int) {
        volumeNode.multiResolutionLevelLimits = level to level + 1
    }

    val detachedDPP_withOwnTime: DPP_DetachedOwnTime

    fun showTimepoint(timepoint: Int) {
        val maxTP = detachedDPP_withOwnTime.max
        // if we play backwards, start with the highest TP once we reach below 0, otherwise play forward and wrap at maxTP
        detachedDPP_withOwnTime.timepoint = if (timepoint < 0) maxTP else timepoint % maxTP
        updateSciviewContent(detachedDPP_withOwnTime)
        VRTracking?.volumeTPWidget?.text = detachedDPP_withOwnTime.timepoint.toString()
    }

    private fun registerKeyboardHandlers() {

        data class BehaviourTriple(val name: String, val key: String, val lambda: ClickBehaviour)

        val handler = sciviewWin.sceneryInputHandler ?: throw IllegalStateException("Could not find input handler!")

        val behaviourCollection = arrayOf(
            BehaviourTriple(desc_DEC_SPH, key_DEC_SPH, { _, _ -> sphereLinkNodes.decreaseSphereScale(); updateUI() }),
            BehaviourTriple(desc_INC_SPH, key_INC_SPH, { _, _ -> sphereLinkNodes.increaseSphereScale(); updateUI() }),
            BehaviourTriple(desc_DEC_LINK, key_DEC_LINK, { _, _ -> sphereLinkNodes.decreaseLinkScale(); updateUI() }),
            BehaviourTriple(desc_INC_LINK, key_INC_LINK, { _, _ -> sphereLinkNodes.increaseLinkScale(); updateUI() }),
            BehaviourTriple(desc_CTRL_WIN, key_CTRL_WIN, { _, _ -> createAndShowControllingUI() }),
            BehaviourTriple(desc_CTRL_INFO, key_CTRL_INFO, { _, _ -> logger.info(this.toString()) }),
            BehaviourTriple(desc_PREV_TP, key_PREV_TP, { _, _ -> detachedDPP_withOwnTime.prevTimepoint()
                updateSciviewContent(detachedDPP_withOwnTime) }),
            BehaviourTriple(desc_NEXT_TP, key_NEXT_TP, { _, _ -> detachedDPP_withOwnTime.nextTimepoint()
                updateSciviewContent(detachedDPP_withOwnTime) }),
            BehaviourTriple("Scale Instance Up", "ctrl E",
                {_, _ -> sphereLinkNodes.changeSpotRadius(selectedSpotInstances, true)}),
            BehaviourTriple("Scale Instance Down", "ctrl Q",
                {_, _ -> sphereLinkNodes.changeSpotRadius(selectedSpotInstances, false)}),
        )

        behaviourCollection.forEach {
            handler.addKeyBinding(it.name, it.key)
            handler.addBehaviour(it.name, it.lambda)
        }

        val scene = sciviewWin.camera?.getScene() ?: throw IllegalStateException("Could not find input scene!")
        val renderer = sciviewWin.getSceneryRenderer() ?: throw IllegalStateException("Could not find scenery renderer!")

        val clickInstance = SelectCommand(
            "Click Instance", renderer, scene, { scene.findObserver() },
            ignoredObjects = listOf(Volume::class.java, RAIVolume::class.java, BufferedVolume::class.java), action = { result, _, _ ->
                if (result.matches.isNotEmpty()) {
                    // Try to cast the result to an instance, or clear the existing selection if it fails
                    selectedSpotInstances.add(result.matches.first().node as InstancedNode.Instance)
                    logger.debug("selected instance {}", selectedSpotInstances)
                    selectedSpotInstances.forEach { s ->
                        sphereLinkNodes.selectSpot2D(s)
                        sphereLinkNodes.showInstancedSpots(
                            detachedDPP_showsLastTimepoint.timepoint,
                            detachedDPP_showsLastTimepoint.colorizer
                        )
                    }
                } else {
                    sphereLinkNodes.clearSpotSelection()
                    selectedSpotInstances.clear()
                }
            }
        )

        sciviewWin.getSceneryRenderer()?.let { r ->
            // Triggered when the user clicks on any object
            handler.addBehaviour("Click Instance", clickInstance)
            handler.addKeyBinding("Click Instance", "button1")

            handler.addBehaviour("Move Instance", MoveInstanceByMouse(
                { scene.findObserver() } ))
            handler.addKeyBinding("Move Instance", "SPACE")
        }

    }

    inner class MoveInstanceByMouse(
        camera: () -> Camera?
    ): DragBehaviour, WithCameraDelegateBase(camera) {

        private var currentHit: Vector3f = Vector3f()
        private var distance: Float = 0f
        private var edges: MutableList<Link> = ArrayList()

        override fun init(x: Int, y: Int) {
            bdvNotifier?.lockUpdates = true
            cam?.let { cam ->
                val (rayStart, rayDir) = cam.screenPointToRay(x, y)
                rayDir.normalize()
                if (selectedSpotInstances.isNotEmpty()) {
                    distance = cam.spatial().position.distance(selectedSpotInstances.first().spatial().position)
                    currentHit = rayStart + rayDir * distance
                    val spot = sphereLinkNodes.findSpotFromInstance(selectedSpotInstances.first())
                    mastodon.model.graph.vertexRef().refTo(spot).incomingEdges()?.forEach {
                        edges.add(it)
                    }
                    mastodon.model.graph.vertexRef().refTo(spot).outgoingEdges()?.forEach {
                        edges.add(it)
                    }
                }
            }
        }

        override fun drag(x: Int, y: Int) {
            if (distance <= 0)
                return

            cam?.let { cam ->
                if (selectedSpotInstances.isNotEmpty()) {
                    selectedSpotInstances.first().let {
                        val (rayStart, rayDir) = cam.screenPointToRay(x, y)
                        rayDir.normalize()
                        val newHit = rayStart + rayDir * distance
                        val movement = newHit - currentHit
                        movement.y *= -1f
                        it.ifSpatial {
                            // Rotation around camera's center
                            val newPos = position + movement / worldScale() / volumeNode.spatial().scale / 1.7f
                            it.spatialOrNull()?.position = newPos
                            currentHit = newHit
                        }
                        sphereLinkNodes.moveSpotInBDV(it, movement)
                        sphereLinkNodes.updateLinkTransforms(edges)
                        sphereLinkNodes.links.values
                    }
                }

            }
        }

        override fun end(x: Int, y: Int) {
            bdvNotifier?.lockUpdates = false
            sphereLinkNodes.showInstancedSpots(detachedDPP_showsLastTimepoint.timepoint,
                detachedDPP_showsLastTimepoint.colorizer)
        }
    }

    /** Starts the sciview VR environment and optionally the eye tracking environment,
     * depending on the user's selection in the UI. Sends spot and track manipulation callbacks to the VR environment. */
    fun launchVR(withEyetracking: Boolean = true) {
        isVRactive = true


        thread {
            if (withEyetracking) {
                VRTracking = EyeTracking(sciviewWin)
                (VRTracking as EyeTracking).run()
            } else {
                VRTracking = CellTrackingBase(sciviewWin)
                VRTracking?.run()
            }

            // Pass track and spot handling callbacks to sciview
            VRTracking?.trackCreationCallback = sphereLinkNodes.addTrackToMastodon
            VRTracking?.spotCreateDeleteCallback = sphereLinkNodes.addOrRemoveSpot
            VRTracking?.spotSelectionCallback = sphereLinkNodes.selectClosestSpotVR
            VRTracking?.spotMoveInitCallback = moveInstanceVRInit
            VRTracking?.spotMoveDragCallback = moveInstanceVRDrag
            VRTracking?.spotMoveEndCallback = moveInstanceVREnd
            VRTracking?.spotLinkCallback = sphereLinkNodes.mergeSelectedToClosestSpot
            VRTracking?.singleLinkTrackedCallback = sphereLinkNodes.addTrackedPoint
            VRTracking?.toggleTrackingPreviewCallback = sphereLinkNodes.toggleLinkPreviews
            VRTracking?.rebuildGeometryCallback = {
                logger.info("Called rebuildGeometryCallback")
                sphereLinkNodes.showInstancedSpots(
                    detachedDPP_showsLastTimepoint.timepoint,
                    detachedDPP_showsLastTimepoint.colorizer
                )
                sphereLinkNodes.showInstancedLinks(
                    sphereLinkNodes.currentColorMode,
                    detachedDPP_showsLastTimepoint.colorizer
                )
            }
            VRTracking?.predictSpotsCallback = predictSpotsCallback
            VRTracking?.trainSpotsCallback = trainsSpotsCallback
            VRTracking?.trainFlowCallback = null
            VRTracking?.neighborLinkingCallback = neighborLinkingCallback
            VRTracking?.stageSpotsCallback = stageSpotsCallback

            var timeSinceUndo = TimeSource.Monotonic.markNow()
            VRTracking?.mastodonUndoCallback = {
                val now = TimeSource.Monotonic.markNow()
                if (now.minus(timeSinceUndo) > 0.5.seconds) {
                    mastodon.model.undo()
                    logger.info("Undid last change.")
                    timeSinceUndo = now
                }

            }

            // register the bridge as an observer to the timepoint changes by the user in VR,
            // allowing us to get updates via the onTimepointChanged() function
            VRTracking?.registerObserver(this)
        }
    }

    /** Stop the VR session and clean up the scene. */
    fun stopVR() {
        isVRactive = false
        VRTracking?.unregisterObserver(this)
        logger.info("Removed timepoint observer from VR bindings.")
        if (associatedUI!!.eyeTrackingToggle.isSelected) {
            (VRTracking as EyeTracking).stop()
        } else {
            VRTracking?.stop()
        }

        // ensure that the volume is visible again (could be turned invisible during the calibration)
        volumeNode.visible = true
        sciviewWin.centerOnNode(axesParent)
        sciviewWin.requestPropEditorRefresh()
        registerKeyboardHandlers()
        centerCameraOnVolume()
    }

    /** Implementation of the [TimepointObserver] interface; this method is called whenever the VR user triggers
     *  a timepoint change or plays the animation */
    override fun onTimePointChanged(timepoint: Int) {
        logger.debug("Called onTimepointChanged")
        updateBDV_TPfromSciview(timepoint)
        showTimepoint(timepoint)
    }

    private fun deregisterKeyboardHandlers() {
        val handler = sciviewWin.sceneryInputHandler
        if (handler != null) {
            listOf(desc_DEC_SPH,
                desc_INC_SPH,
                desc_DEC_LINK,
                desc_INC_LINK,
                desc_CTRL_WIN,
                desc_CTRL_INFO,
                desc_PREV_TP,
                desc_NEXT_TP)
                .forEach {
                    handler.removeKeyBinding(it)
                    handler.removeBehaviour(it)
                }
        }
    }

    @JvmOverloads
    fun createAndShowControllingUI(windowTitle: String? = "Controls for " + sciviewWin.getName()): JFrame {
        return JFrame(windowTitle).apply {
            val panel = JPanel()
            add(panel)
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
            associatedUI = SciviewBridgeUIMig(this@SciviewBridge, panel)
            pack()
            isVisible = true
        }
    }

    fun stopAndDetachUI() {
        isRunning = false
        workerExecutor.shutdownNow()
        logger.info("Stopped bridge worker queue.")
        try {
            // Wait for graceful termination
            if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.error("Worker thread did not terminate gracefully")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        updateQueue.clear()
        sciviewWin.mainWindow.close()
        logger.info("Closed sciview main window.")
        if (associatedUI != null) {
            associatedUI?.deactivateAndForget()
            associatedUI = null
        }
        if (uiFrame != null) {
            uiFrame?.isVisible = false
            uiFrame?.dispose()
        }
    }

    fun updateUI() {
        if (associatedUI == null) return
        associatedUI?.updatePaneValues()
    }

    companion object {
        fun getDisplayVoxelRatio(forThisSource: Source<*>): Vector3f {
            val vxAxisRatio = forThisSource.voxelDimensions.dimensionsAsDoubleArray()
            val finalRatio = FloatArray(vxAxisRatio.size)
            var minLength = vxAxisRatio[0]
            for (i in 1 until vxAxisRatio.size) minLength = min(vxAxisRatio[i], minLength)
            for (i in vxAxisRatio.indices) finalRatio[i] = (vxAxisRatio[i] / minLength).toFloat()
            return Vector3f(finalRatio[0], finalRatio[1], finalRatio[2])
        }

        // --------------------------------------------------------------------------
        fun adjustHeadLight(hl: PointLight) {
            hl.intensity = 1.5f
            hl.spatial().rotation = Quaternionf().rotateY(Math.PI.toFloat())
        }

        fun constructDataAxes(): Node {
            //add the data axes
            val AXES_LINE_WIDTHS = 0.01f
            val AXES_LINE_LENGTHS = 0.1f
            //
            val axesParent = Group()
            axesParent.name = "Data Axes"
            //
            var c = Cylinder(AXES_LINE_WIDTHS / 2.0f, AXES_LINE_LENGTHS, 12)
            c.name = "Data x axis"
            c.material().diffuse = Vector3f(1f, 0f, 0f)
            val halfPI = Math.PI.toFloat() / 2.0f
            c.spatial().rotation = Quaternionf().rotateLocalZ(-halfPI)
            axesParent.addChild(c)
            //
            c = Cylinder(AXES_LINE_WIDTHS / 2.0f, AXES_LINE_LENGTHS, 12)
            c.name = "Data y axis"
            c.material().diffuse = Vector3f(0f, 1f, 0f)
            c.spatial().rotation = Quaternionf().rotateLocalZ(Math.PI.toFloat())
            axesParent.addChild(c)
            //
            c = Cylinder(AXES_LINE_WIDTHS / 2.0f, AXES_LINE_LENGTHS, 12)
            c.name = "Data z axis"
            c.material().diffuse = Vector3f(0f, 0f, 1f)
            c.spatial().rotation = Quaternionf().rotateLocalX(-halfPI)
            axesParent.addChild(c)
            return axesParent
        }

        // --------------------------------------------------------------------------
        const val key_DEC_SPH = "O"
        const val key_INC_SPH = "shift O"
        const val key_DEC_LINK = "L"
        const val key_INC_LINK = "shift L"
        const val key_CTRL_WIN = "ctrl I"
        const val key_CTRL_INFO = "shift I"
        const val key_PREV_TP = "T"
        const val key_NEXT_TP = "shift T"
        const val desc_DEC_SPH = "decrease_initial_spheres_size"
        const val desc_INC_SPH = "increase_initial_spheres_size"
        const val desc_DEC_LINK = "decrease_initial_links_size"
        const val desc_INC_LINK = "increase_initial_links_size"
        const val desc_CTRL_WIN = "controlling_window"
        const val desc_CTRL_INFO = "controlling_info"
        const val desc_PREV_TP = "show_previous_timepoint"
        const val desc_NEXT_TP = "show_next_timepoint"
    }
}
