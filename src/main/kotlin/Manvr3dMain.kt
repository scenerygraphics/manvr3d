@file:Suppress("UNCHECKED_CAST")

import bdv.viewer.Source
import bdv.viewer.SourceAndConverter
import graphics.scenery.*
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.controls.behaviours.WithCameraDelegateBase
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
import org.mastodon.collection.RefCollections
import org.mastodon.mamut.model.Link
import org.mastodon.mamut.model.Spot
import org.mastodon.mamut.ui.Manvr3dUIMig
import org.mastodon.mamut.util.DataAxes
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
import org.mastodon.mamut.ProjectModel
import graphics.scenery.utils.TimepointObserver
import vr.EyeTracking
import util.GeometryHandler
import vr.CellTrackingBase
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

class Manvr3dMain: TimepointObserver {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))
    //data source stuff
    val mastodon: ProjectModel
    var sourceID = 0
    var volumeMipmapLevel = 0
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
        val sb = StringBuilder("Manvr3d internal settings:\n")
        sb.append("   SOURCE_ID = $sourceID\n")
        sb.append("   SOURCE_USED_RES_LEVEL = $volumeMipmapLevel\n")
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
    val geometryHandler: GeometryHandler
    //sink scene graph structuring nodes
    val axesParent: DataAxes

    // Worker queue for async 3D updating
    private val updateQueue = LinkedBlockingQueue<() -> Unit>()
    private val workerExecutor = Executors.newSingleThreadExecutor { thread ->
        Thread(thread, "GeometryHandlerUpdateWorker").apply { isDaemon = true }
    }

    var volumeNode: Volume
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
    lateinit var bdvWindow: MamutViewBdv
    var currentTimepoint: Int = 0
        private set
    var minTimepoint: Int = 0
        private set
    var maxTimepoint: Int = 0
        private set
    val currentColorizer: GraphColorGenerator<Spot, Link>
        get() = getCurrentColorizer(bdvWindow)

    var moveSpotInSciview: (Spot?) -> Unit?
    var associatedUI: Manvr3dUIMig? = null
    var uiFrame: JFrame? = null
    private var isRunning = true
    var isVRactive = false
    /** Factor to scale the native headset resolution by. Useful to increase performance at little visual impact.
     * Can only be changed through the UI, and is only applied when VR is started. */
    private var vrResolutionScale = 0.75f
    /** Default transforms for the volume. Can be reverted to when needing to reset the view. */
    var defaultVolumePosition: Vector3f
    var defaultVolumeScale: Vector3f
    var defaultVolumeRotation: Quaternionf

    lateinit var vrTracking: CellTrackingBase

    private val pluginActions: Actions
    private var predictSpotsAction: Action? = null
    private var trainSpotsAction: Action? = null
    private val trainFlowAction: Action? = null
    private var neighborLinkingAction: Action? = null

    constructor(
        mastodonMainWindow: ProjectModel,
        targetSciviewWindow: SciView
    ) : this(mastodonMainWindow, 0, 0, targetSciviewWindow)

    constructor(
        mastodonMainWindow: ProjectModel,
        sourceID: Int,
        volumeMipmapLevel: Int,
        targetSciviewWindow: SciView
    ) {
        mastodon = mastodonMainWindow
        sciviewWin = targetSciviewWindow
        sciviewWin.setPushMode(true)
        minTimepoint = mastodon.minTimepoint
        maxTimepoint = mastodon.maxTimepoint
        currentTimepoint = minTimepoint

        //adjust the default scene's settings
        sciviewWin.applicationName = ("sciview for Mastodon: " + mastodon.projectName)

        sciviewWin.floor?.visible = false
        sciviewWin.lights?.forEach { l: PointLight ->
            if (l.name.startsWith("headli")) adjustHeadLight(l)
        }
        sciviewWin.camera?.children?.forEach { l: Node ->
            if (l.name.startsWith("headli") && l is PointLight) adjustHeadLight(l)
        }
        sciviewWin.addNode(AmbientLight(0.05f, Vector3f(1f, 1f, 1f)))

        //add "root" with data axes
        axesParent = DataAxes()
        sciviewWin.addNode(axesParent, activePublish = false)

        //get necessary metadata - from image data
        this.sourceID = sourceID
        this.volumeMipmapLevel = volumeMipmapLevel
        sac = mastodon.sharedBdvData.sources[this.sourceID]
        spimSource = sac.spimSource
        // number of pixels for each dimension at the highest res level
        val volumeDims = spimSource.getSource(0, 0).dimensionsAsLongArray()    // TODO rename to something more meaningful
        // number of pixels for each dimension of the volume at current res level
        val volumeNumPixels = spimSource.getSource(0, this.volumeMipmapLevel).dimensionsAsLongArray()
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

        setMipmapLevel(this.volumeMipmapLevel)
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
        geometryHandler = GeometryHandler(sciviewWin, this, updateQueue, mastodon, volumeNode, volumeNode)

        geometryHandler.showInstancedSpots(0, noTSColorizer)
        geometryHandler.showInstancedLinks(GeometryHandler.ColorMode.LUT, colorizer = noTSColorizer)

        // lambda function that is passed to the event handler and called
        // when a vertex position change occurs on the BDV side
        moveSpotInSciview = { spot: Spot? ->
            spot?.let {
                selectedSpotInstances.clear()
                geometryHandler.findInstanceFromSpot(spot)?.let { selectedSpotInstances.add(it) }
                geometryHandler.moveAndScaleSpotInSciview(spot) }
        }

        defaultVolumePosition = volumeNode.spatial().position
        defaultVolumeScale = volumeNode.spatial().scale
        defaultVolumeRotation = volumeNode.spatial().rotation

        pluginActions = mastodon.plugins.pluginActions


        openSyncedBDV()

        registerKeyboardHandlers()

        submitToTaskExecutor()
    }

    val eventService: EventService?
        get() = sciviewWin.scijavaContext?.getService(EventService::class.java)

    /** Train the ELEPHANT model on all timepoints. */
    fun trainSpots() {
        if (trainSpotsAction == null) {
            trainSpotsAction = pluginActions.actionMap.get("[elephant] train detection model (all timepoints)")
        }
        val start = TimeSource.Monotonic.markNow()
        logger.info("Training spots from all timepoints...")
        (trainSpotsAction as TrainDetectionAction).run()
        logger.info("Training spots took ${start.elapsedNow()} ms")
        sciviewWin.camera?.showMessage("Training took ${start.elapsedNow()} ms", 2f, 0.2f, centered = true)
    }

    /** Predict spots with ELEPHANT. If [predictAll] is true, all timepoints will be predicted.
     * Otherwise, just the current timepoint will be predicted. */
    fun preditSpots(predictAll: Boolean) {
        if (predictSpotsAction == null) {
            predictSpotsAction = pluginActions.actionMap.get("[elephant] predict spots")
        }
        // Limitation of Elephant: we can only predict X number of frames in the past
        // So we have to temporarily move to the last TP and set the time range to the size of all TPs
        val settings = ElephantMainSettingsManager.getInstance().forwardDefaultStyle
        settings.timeRange = if (predictAll) volumeNode.timepointCount else 1
        logger.info("Elephant settings.timeRange was set to ${settings.timeRange}.")
        val start = TimeSource.Monotonic.markNow()
        val currentTP = currentTimepoint
        val groupHandle = mastodon.groupManager.createGroupHandle()
        groupHandle.groupId = 0
        val tpAdapter = TimepointModelAdapter(groupHandle.getModel(mastodon.TIMEPOINT))

        if (predictAll) {
            tpAdapter.timepoint = volumeNode.timepointCount
        } else {
            tpAdapter.timepoint = currentTP
        }
        (predictSpotsAction as PredictSpotsAction).run()
        logger.info("Predicting spots took ${start.elapsedNow()} ms")
        if (predictAll) {
            tpAdapter.timepoint = currentTP
        }
        geometryHandler.showInstancedSpots(currentTimepoint,
            currentColorizer)
        sciviewWin.camera?.showMessage("Prediction took ${start.elapsedNow()} ms", 2f, 0.2f, centered = true)

    }

    /** Prepare all spots in the scene for ELEPHANT training. */
    fun stageSpots() {
        logger.info("Adding all spots to the true positive tag set...")
        val tagResult = geometryHandler.applyTagToAllSpots("Detection", "tp")
        if (!tagResult) {
            logger.warn("Could not find tag or tag set! Please ensure both exist.")
        } else {
            geometryHandler.showInstancedSpots(
                currentTimepoint,
                currentColorizer)
        }
    }

    /** Use the nearest-neighbor linking algorithm in ELEPHANT to connect all spots in the scene. */
    fun linkNearestNeighbors() {
        if (neighborLinkingAction == null) {
            neighborLinkingAction = pluginActions.actionMap.get("[elephant] nearest neighbor linking")
        }
        logger.info("Linking nearest neighbors...")
        // Setting the NN linking range to always include the whole time range
        val settings = ElephantMainSettingsManager.getInstance().forwardDefaultStyle
        settings.timeRange = volumeNode.timepointCount
        // Store current TP so we can revert to it after the linking
        val currentTP = currentTimepoint
        // Get the group handle and move its TP to the last TP
        val groupHandle = mastodon.groupManager.createGroupHandle()
        groupHandle.groupId = 0
        val tpAdapter = TimepointModelAdapter(groupHandle.getModel(mastodon.TIMEPOINT))
        tpAdapter.timepoint = volumeNode.timepointCount
        (neighborLinkingAction as NearestNeighborLinkingAction).run()
        // Revert to the previous TP
        tpAdapter.timepoint = currentTP
        geometryHandler.showInstancedLinks()
        sciviewWin.camera?.showMessage("Linked nearest neighbors.", 2f, 0.2f, centered = true)
    }

    /** Train the ELEPHANT flow model on the scene. */
    fun trainFlow() {
        // TODO
    }

    /** Sets the [vrResolutionScale]. Changes are only applied once [Manvr3dMain.launchVR] is executed. */
    fun setVrResolutionScale(scale: Float) {
        vrResolutionScale = scale
    }

    /** Launches a worker that sequentially executes queued spot and link updates from [GeometryHandler]. */
    private fun submitToTaskExecutor() {
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
            logger.info("Worker executor loop ended.")
        }
    }

    /** Centers the camera on the volume and adjusts its distance to fully fit the volume into the camera's FOV. */
    private fun centerCameraOnVolume() {
        sciviewWin.camera?.centerOnNode(
            node = volumeNode,
            sceneScale = volumeNode.pixelToWorldRatio * sceneScale,
            resetPosition = true
        )
    }

    fun close() {
        stopAndDetachUI()
        deregisterKeyboardHandlers()
        logger.info("Manvr3d closing procedure: UI and keyboard handlers are removed now")
//        sciviewWin.setActiveNode(axesParent)
        logger.info("Manvr3d closing procedure: focus shifted away from our nodes")
        val updateGraceTime = 100L // in ms
        try {
            sciviewWin.deleteNode(volumeNode, true)
            logger.debug("Manvr3d closing procedure: red volume removed")
            Thread.sleep(updateGraceTime)
//            sciviewWin.deleteNode(sphereParent, true)
            logger.debug("Manvr3d closing procedure: spots were removed")
        } catch (e: InterruptedException) { /* do nothing */
        }
//        sciviewWin.deleteNode(axesParent, true)
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

    /** Converts a [Vector3f] from sciview scale to Mastodon scale without looking at positions or flipped coordinate systems.
     * Returns the scale factor itself if called without passing a vector. */
    fun sciviewToMastodonScale(v: Vector3f = Vector3f(1f)) : Vector3f {
        val scale = v
        scale.div(volumeNode.spatial().scale)
        scale.div(volumeNode.pixelToWorldRatio)
        return scale
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
            val srcImg = spimSource.getSource(0, spimSource.numMipmapLevels - 1) as RandomAccessibleInterval<UnsignedShortType>
            Views.iterable(srcImg).forEach { px -> maxVal = maxVal.coerceAtLeast(px.realFloat) }
            intensity.clampTop = 0.9f * maxVal //very fake 90% percentile...
            intensity.rangeMin = maxVal * 0.15f
            intensity.rangeMax = maxVal * 0.75f
            //TODO: change MIN and MAX to proper values
            logger.debug("Clamp at ${intensity.clampTop}," +
                    " range min to ${intensity.rangeMin} and range max to ${intensity.rangeMax}")
            updateSciviewTimepointFromBDV(force = true)
            updateUI()
        } else {
            intensity = intensityBackup.copy()
            updateSciviewTimepointFromBDV(force = true)
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
        val srcImg = spimSource.getSource(currentTimepoint, volumeMipmapLevel) as RandomAccessibleInterval<UnsignedShortType>
        volumeIntensityProcessing(srcImg)
    }

    /** Create a BDV window and launch a [BdvNotifier] instance to synchronize time point and viewing direction. */
    fun openSyncedBDV() {
        bdvWindow = mastodon.windowManager.createView(MamutViewBdv::class.java)
        bdvWindow.frame.setTitle("BDV linked to ${sciviewWin.getName()}")

        updateSciviewContent()
        bdvNotifier = BdvNotifier(
            { updateSciviewContent() },
            { updateSciviewCameraFromBDV() },
            moveSpotInSciview as (Spot?) -> Unit,
            {
                geometryHandler.showInstancedLinks(geometryHandler.currentColorMode, currentColorizer)
                geometryHandler.showInstancedSpots(currentTimepoint, currentColorizer)
            },
            mastodon,
            bdvWindow
        )
    }

    private var recentTagSet: TagSetStructure.TagSet? = null
    var recentColorizer: GraphColorGenerator<Spot, Link>? = null
    val noTSColorizer = DefaultGraphColorGenerator<Spot, Link>()

    private fun getCurrentColorizer(forThisBdv: MamutViewBdv): GraphColorGenerator<Spot, Link> {
        //NB: trying to avoid re-creating of new TagSetGraphColorGenerator objs with every new content rendering
        val ts = forThisBdv.coloringModel.tagSet
        if (ts != null) {
            if (ts !== recentTagSet) {
                recentColorizer = TagSetGraphColorGenerator(mastodon.model.tagSetModel, ts)
                recentTagSet = ts
            }
            return recentColorizer!!
        }
        return noTSColorizer
    }

    fun setTimepoint(tp: Int) {
        currentTimepoint = max(minTimepoint.toDouble(), min(maxTimepoint.toDouble(), tp.toDouble())).toInt()
    }

    fun nextTimepoint() {
        setTimepoint(currentTimepoint + 1)
    }

    fun prevTimepoint() {
        setTimepoint(currentTimepoint - 1)
    }

    /** Calls [updateSciviewTimepointFromBDV] and [GeometryHandler.showInstancedSpots] to update the current volume and corresponding spots. */
    fun updateSciviewContent() {
        volumeNode.goToTimepoint(currentTimepoint)
        geometryHandler.showInstancedSpots(currentTimepoint, currentColorizer)
        geometryHandler.updateSegmentVisibility(currentTimepoint)
        geometryHandler.updateLinkColors(currentColorizer)
    }

    /** Uses the current [bdvWinParamsProvider] to update the sciview spots of the current timepoint. */
    fun redrawSciviewSpots() {
        geometryHandler.showInstancedSpots(currentTimepoint, currentColorizer)
    }

    /** Rebuild all geometry on the sciview side for the default [bdvWinParamsProvider]. */
    fun rebuildGeometry() {
        logger.debug("Called rebuildGeometryCallback")
        geometryHandler.showInstancedSpots(currentTimepoint, currentColorizer)
        geometryHandler.showInstancedLinks(geometryHandler.currentColorMode, currentColorizer)
    }

    /** Takes a timepoint and updates the current BDV window's time accordingly. */
    fun updateBDV_TimepointFromSciview(tp: Int) {
        logger.debug("Updated BDV timepoint from sciview to $tp")
        bdvWindow.viewerPanelMamut.state().currentTimepoint = tp
    }

    /** Update the sciview content based on the timepoint from the BDV window.
     * Returns true if the content was updated. */
    @JvmOverloads
    fun updateSciviewTimepointFromBDV(force: Boolean = false): Boolean {
        if (updateVolAutomatically || force) {
            val bdvTP = bdvWindow.viewerPanelMamut?.state()?.currentTimepoint ?: currentTimepoint
            if (bdvTP != currentTimepoint) {
                currentTimepoint = bdvTP
                volumeNode.goToTimepoint(currentTimepoint)
                return true
            }
        }
        return false
    }

    private fun updateSciviewCameraFromBDV() {
        // Let's not move the camera around when the user is in VR
        if (isVRactive) {
            return
        }
        val auxTransform = AffineTransform3D()
        val viewMatrix = Matrix4f()
        val viewRotation = Quaternionf()
        bdvWindow.viewerPanelMamut.state().getViewerTransform(auxTransform)
        for (r in 0..2) for (c in 0..3) viewMatrix[c, r] = auxTransform[r, c].toFloat()
        viewMatrix.getUnnormalizedRotation(viewRotation)
        val camSpatial = sciviewWin.camera?.spatial() ?: return
        viewRotation.y *= -1f
        viewRotation.z *= -1f
        camSpatial.rotation = viewRotation
        val dist = camSpatial.position.length()
        camSpatial.position = sciviewWin.camera?.forward!!.normalize().mul(-1f * dist)
    }

    fun setVolumeOnlyVisibility(state: Boolean) {
        val spots = geometryHandler.mainSpotInstance
        val spotVis = spots?.visible ?: false
        val links = geometryHandler.mainLinkInstance
        val linksVis = links?.visible ?: false

        volumeNode.visible = state
        if (state) {
            volumeNode.children.stream()
                .filter { c: Node -> c.name.startsWith("Bounding") }
                .forEach { c: Node -> c.visible = false }
        }
        spots?.visible = spotVis
        links?.visible = linksVis
    }

    /** Sets the detail level of the volume node. */
    fun setMipmapLevel(level: Int) {
        volumeNode.multiResolutionLevelLimits = level to level + 1
    }

    fun showTimepoint(timepoint: Int) {
        geometryHandler.clearSelection()
        updateSciviewContent()
        vrTracking.volumeTimepointWidget.text = currentTimepoint.toString()
    }

    private fun registerKeyboardHandlers() {

        data class BehaviourTriple(val name: String, val key: String, val lambda: ClickBehaviour)

        val handler = sciviewWin.sceneryInputHandler ?: throw IllegalStateException("Could not find input handler!")

        val behaviourCollection = arrayOf(
            BehaviourTriple(desc_DEC_SPH, key_DEC_SPH, { _, _ -> geometryHandler.decreaseSphereInstanceScale(); updateUI() }),
            BehaviourTriple(desc_INC_SPH, key_INC_SPH, { _, _ -> geometryHandler.increaseSphereInstanceScale(); updateUI() }),
            BehaviourTriple(desc_DEC_LINK, key_DEC_LINK, { _, _ -> geometryHandler.decreaseLinkScale(); updateUI() }),
            BehaviourTriple(desc_INC_LINK, key_INC_LINK, { _, _ -> geometryHandler.increaseLinkScale(); updateUI() }),
            BehaviourTriple(desc_CTRL_WIN, key_CTRL_WIN, { _, _ -> createAndShowControllingUI() }),
            BehaviourTriple(desc_CTRL_INFO, key_CTRL_INFO, { _, _ -> logger.info(this.toString()) }),
            BehaviourTriple(desc_PREV_TP, key_PREV_TP, { _, _ -> prevTimepoint(); updateSciviewContent() }),
            BehaviourTriple(desc_NEXT_TP, key_NEXT_TP, { _, _ -> nextTimepoint(); updateSciviewContent() }),
            BehaviourTriple("Scale Instance Up", "ctrl E",
                {_, _ -> geometryHandler.changeSpotRadius(selectedSpotInstances, 1.1f)}),
            BehaviourTriple("Scale Instance Down", "ctrl Q",
                {_, _ -> geometryHandler.changeSpotRadius(selectedSpotInstances, 0.9f)}),
        )

        behaviourCollection.forEach {
            handler.addKeyBinding(it.name, it.key)
            handler.addBehaviour(it.name, it.lambda)
        }

        val scene = sciviewWin.camera?.getScene() ?: throw IllegalStateException("Could not find input scene!")
        val renderer = sciviewWin.getSceneryRenderer() ?: throw IllegalStateException("Could not find scenery renderer!")

        val clickInstance = SelectCommand(
            "Click Instance", renderer, scene, { scene.findObserver() },
            ignoredObjects = listOf(Volume::class.java, RAIVolume::class.java, BufferedVolume::class.java, Mesh::class.java),
            action = { result, _, _ ->
                if (result.matches.isNotEmpty()) {
                    // Remove previous selections first
                    geometryHandler.clearSelection()
                    // Try to cast the result to an instance, or clear the existing selection if it fails
                    selectedSpotInstances.add(result.matches.first().node as InstancedNode.Instance)
                    logger.debug("selected instance {}", selectedSpotInstances)
                    selectedSpotInstances.forEach { s ->
                        geometryHandler.selectSpot2D(s)
                        geometryHandler.showInstancedSpots(currentTimepoint, currentColorizer)
                    }
                } else {
                    geometryHandler.clearSelection()
                    geometryHandler.showInstancedSpots(currentTimepoint, currentColorizer)
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
        private var edges = mutableListOf<Int>()

        override fun init(x: Int, y: Int) {
            bdvNotifier?.lockUpdates = true
            cam?.let { cam ->
                val (rayStart, rayDir) = cam.screenPointToRay(x, y)
                rayDir.normalize()
                if (selectedSpotInstances.isNotEmpty()) {
                    distance = cam.spatial().position.distance(selectedSpotInstances.first().spatial().position)
                    currentHit = rayStart + rayDir * distance
                    val spot = geometryHandler.findSpotFromInstance(selectedSpotInstances.first())
                    spot?.let {
                        edges.addAll(it.edges().map { it.internalPoolIndex })
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
                            it.instancedParent.updateInstanceBuffers()
                        }
                        geometryHandler.moveSpotInBDV(it, movement)
                        geometryHandler.updateLinkTransforms(edges)
                        geometryHandler.links.values
                    }
                }

            }
        }

        override fun end(x: Int, y: Int) {
            edges.clear()
            bdvNotifier?.lockUpdates = false
            geometryHandler.showInstancedSpots(currentTimepoint,
                currentColorizer)
        }
    }

    var timeSinceUndo = TimeSource.Monotonic.markNow()

    /** Reverts to the point previously saved by Mastodon's undo recorder.
     * Performs redo events if [redo] is set to true. */
    fun undoRedo(redo: Boolean = false) {
        val now = TimeSource.Monotonic.markNow()
        if (now.minus(timeSinceUndo) > 0.5.seconds) {
            if (redo) {
                mastodon.model.redo()
                logger.info("Redid last change.")
            } else {
                mastodon.model.undo()
                logger.info("Undid last change.")
            }
            timeSinceUndo = now
        }
    }

    /**  */
    fun mergeSelectionAndUpdate() {
        val spots = RefCollections.createRefList(mastodon.model.graph.vertices())
        spots.addAll(selectedSpotInstances.map { geometryHandler.findSpotFromInstance(it) }.distinct())
        geometryHandler.mergeSpots(spots)
        geometryHandler.clearSelection()
        geometryHandler.showInstancedSpots(currentTimepoint, currentColorizer)
    }

    fun mergeOverlapsAndUpdate(tp: Int = volumeNode.currentTimepoint) {
        geometryHandler.mergeOverlappingSpots(tp)
        geometryHandler.showInstancedSpots(currentTimepoint, currentColorizer)
    }

    /** Deletes the whole graph and updates the geometry. */
    fun deleteGraphAndUpdate() {
        val spots = RefCollections.createRefSet<Spot>(mastodon.model.graph.vertices())
        spots.addAll(mastodon.model.graph.vertices())
        geometryHandler.deleteSpots(spots)
        rebuildGeometry()
    }

    /** Deletes all annotations from this timepoint. */
    fun deleteTimepointAndUpdate(tp: Int) {
        val tp = mastodon.model.spatioTemporalIndex.getSpatialIndex(tp)
        val spots = RefCollections.createRefSet<Spot>(mastodon.model.graph.vertices())
        spots.addAll(tp)
        geometryHandler.deleteSpots(spots)
        rebuildGeometry()
    }

    /** Recenter and set default scaling for the volume, then center camera on the volume. */
    fun resetView() {
        volumeNode.spatial {
            position = Vector3f(0f)
            scale = defaultVolumeScale
            rotation = defaultVolumeRotation
            needsUpdate = true
            needsUpdateWorld = true
        }
        centerCameraOnVolume()
        // TODO this is a hacky workaround for the geometry not updating properly when resetting the volume
        rebuildGeometry()
        rebuildGeometry()
    }

    /** Starts the sciview VR environment and optionally the eye tracking environment,
     * depending on the user's selection in the UI. Sends spot and track manipulation callbacks to the VR environment. */
    fun launchVR(wantEyeTracking: Boolean = true): Boolean {
        var useEyeTrackers = wantEyeTracking
        // Test whether a headset is connected before starting sciview's VR launch routines
        val hmd = OpenVRHMD(false, true)
        if (!hmd.initializedAndWorking()) {
            logger.warn("Could not find VR headset. Aborting launch of VR environment.")
            hmd.close()
            return false
        }
        hmd.close()

        isVRactive = true

        thread {

            vrTracking = if (useEyeTrackers) {
                val eyeTracking = EyeTracking(sciviewWin, this, geometryHandler, vrResolutionScale)
                if (eyeTracking.establishEyeTrackerConnection()) {
                    eyeTracking
                } else {
                    useEyeTrackers = false
                    CellTrackingBase(sciviewWin, this, geometryHandler, vrResolutionScale)
                }
            } else {
                CellTrackingBase(sciviewWin, this, geometryHandler, vrResolutionScale)
            }
            sciviewWin.getSceneryRenderer()?.setRenderingQuality(RenderConfigReader.RenderingQuality.Low)

            // register manvr3d as an observer to the timepoint changes by the user in VR,
            // allowing us to get updates via the onTimepointChanged() function
            vrTracking.registerObserver(this)

            if (useEyeTrackers) {
                (vrTracking as EyeTracking).run()
            } else {
                vrTracking.run()
            }
        }
        return true
    }

    /** Stop the VR session and clean up the scene. */
    fun stopVR() {
        isVRactive = false
        vrTracking.unregisterObserver(this)
        logger.info("Removed timepoint observer from VR bindings.")
        if (associatedUI!!.eyeTrackingToggle.isSelected) {
            (vrTracking as EyeTracking).stop()
        } else {
            vrTracking.stop()
        }

        // ensure that the volume is visible again (could be turned invisible during the calibration)
        volumeNode.visible = true
        logger.info("Requesting property editor refresh...")
        sciviewWin.requestPropEditorRefresh()
        logger.info("Registering keyboard handles again...")
        registerKeyboardHandlers()
        logger.info("Centering on volume...")
        centerCameraOnVolume()
    }

    /** Implementation of the [TimepointObserver] interface; this method is called whenever the VR user triggers
     *  a timepoint change or plays the animation */
    override fun onTimePointChanged(timepoint: Int) {
        logger.debug("Called onTimepointChanged with $timepoint")
        setTimepoint(when {
            timepoint < 0 -> maxTimepoint
            timepoint > maxTimepoint -> 0
            else -> timepoint
        })
        updateBDV_TimepointFromSciview(currentTimepoint)
        showTimepoint(currentTimepoint)
    }

    /** Quickly flashes the volume's bounding grid to indicate the borders of the volume. */
    fun flashVolumeGrid() {
        val bg = volumeNode.children.filterIsInstance<BoundingGrid>()
        bg.firstOrNull()?.flashGrid()
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
            associatedUI = Manvr3dUIMig(this@Manvr3dMain, panel)
            pack()
            isVisible = true
        }
    }

    fun stopAndDetachUI() {
        isRunning = false
        workerExecutor.shutdownNow()
        logger.info("Stopped manvr3d worker queue.")
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
