package graphics.scenery.manvr3d.util

import graphics.scenery.*
import graphics.scenery.attribute.material.DefaultMaterial
import graphics.scenery.attribute.material.Material
import graphics.scenery.primitives.Arrow
import graphics.scenery.primitives.Cylinder
import graphics.scenery.utils.extensions.*
import graphics.scenery.utils.lazyLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.EigenDecomposition
import org.apache.commons.math3.linear.RealMatrix
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3i
import org.joml.Vector4f
import org.mastodon.collection.RefCollections
import org.mastodon.collection.RefList
import org.mastodon.collection.RefSet
import org.mastodon.mamut.ProjectModel
import graphics.scenery.manvr3d.Manvr3dMain
import org.mastodon.mamut.model.Link
import org.mastodon.mamut.model.Spot
import org.mastodon.spatial.SpatialIndex
import org.mastodon.ui.coloring.GraphColorGenerator
import org.scijava.event.EventService
import sc.iview.SciView
import graphics.scenery.manvr3d.analysis.HedgehogAnalysis.SpineGraphVertex
import graphics.scenery.manvr3d.vr.CellTrackingBase.TrackedPoint
import graphics.scenery.volumes.Colormap
import kotlinx.coroutines.joinAll
import net.imglib2.KDTree
import net.imglib2.RealPoint
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree
import org.elephant.setting.main.ElephantMainSettingsManager
import org.mastodon.model.tag.TagSetStructure
import org.mastodon.tracking.mamut.detection.DetectionQualityFeature
import spim.fiji.spimdata.interestpoints.InterestPoint
import java.awt.Color
import java.lang.Math
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.TimeSource

/** Class that constructs 3D representations of spots and links/tracks in sciview from data provided by Mastodon
 * in the form of instanced geometry. Spots are represented as spheres, and track segments (links) are represented as cylinders.
 * It handles initialization, updates and actions like addition, deletion and movement of spots.
 * @param sv The sciview instance to use.
 * @param manvr3d An instance of manvr3d.
 * @param updateQueue Queue that executes scene updates sequentially in its own thread.
 * @param mastodonData Instance of the Mastodon ProjectModel
 * @param sphereParentNode Parent node for the instanced spheres
 * @param linkParentNode Parent node for the instanced links */
class GeometryHandler(
    val sv: SciView,
    val manvr3d: Manvr3dMain,
    val updateQueue: LinkedBlockingQueue<() -> Unit>,
    val mastodonData: ProjectModel,
    val sphereParentNode: Node,
    val linkParentNode: Node
) {

    private val logger by lazyLogger("debug")
    var sphereScaleFactor = 1f
    var linkScaleFactor = 1f
    var DEFAULT_COLOR = 0x00FFFFFF
    var numTimePoints: Int
    lateinit var lut: Colormap
    var currentColorMode: ColorMode
    val spotPool: MutableList<InstancedNode.Instance> = ArrayList(10000)
    val linkPool: MutableList<InstancedNode.Instance> = ArrayList(10000)
    var events: EventService? = null

    val sphere = IcosphereLensed(1f, 2)
    val cylinder = CylinderLensed(0.2f, 1f, 6, true, true)
    var mainSpotInstance: InstancedNode? = null
    var mainLinkInstance: InstancedNode? = null
    lateinit var visibleSpots: SpatialIndex<Spot>
    var linkForwardRange: Int
    var linkBackwardRange: Int

    /** Allows finding a spot instance based on a spot pool index. */
    private val spotToInstanceMap = ConcurrentHashMap<Int, InstancedNode.Instance>()
    /** Allows finding a spot pool index based on a spot instance. */
    private val instanceToSpotMap = ConcurrentHashMap<InstancedNode.Instance, Int>()
    /** Cache the already calculated radii to prevent recalculating them all the time. Stores the vertex index and the radius. */
    private val radiusCache = ConcurrentHashMap<Int, Float>()

    lateinit var currentColorizer: GraphColorGenerator<Spot, Link>

    private var selectedColor = Vector4f(1f, 0.25f, 0.25f, 1f)
    /** Class that connects link data with their corresponding instances.  */
    data class LinkNode (val instance: InstancedNode.Instance, val link: Link, val tp: Int, val center: Vector3f)
    /** A segment of a link, used during tracking to preview the tracking history. */
    data class LinkPreview( val instance: InstancedNode.Instance, val from: Vector3f, val to: Vector3f , val tp: Int)

    /** Accelerated data structure for storing [LinkNode]s for fast spatial querying during selections. */
    private var edgeCenterTree: KDTree<LinkNode>? = null

    val linkPreviewList = mutableListOf<LinkPreview>()
    val linkSize = 2.0
    // list of all link segments
    var links: ConcurrentHashMap<Int, LinkNode> = ConcurrentHashMap()

    init {
        events = sv.scijavaContext?.getService(EventService::class.java)
        numTimePoints = mastodonData.maxTimepoint

        setLUT("plasma")
        currentColorMode = ColorMode.LUT

        linkForwardRange = mastodonData.maxTimepoint
        linkBackwardRange = mastodonData.maxTimepoint
    }

    class IcosphereLensed(radius: Float, subdivisions: Int) : Icosphere(radius, subdivisions) {
        @ShaderProperty var lensingPosition: Vector3f = Vector3f(0f)
        @ShaderProperty var lensingRadius: Float = 0.1f
        @ShaderProperty var enableLens: Boolean = false
    }

    class CylinderLensed(radius: Float, height: Float, segments: Int, fillCaps: Boolean, smoothSides: Boolean
    ): Cylinder(radius, height, segments, fillCaps, smoothSides) {
        @ShaderProperty var lensingPosition: Vector3f = Vector3f(0f)
        @ShaderProperty var lensingRadius: Float = 0.1f
        @ShaderProperty var enableLens: Boolean = false
    }

    fun setLUT(lutName: String) {
        try {
            lut = Colormap.get(lutName)
        } catch (e: Exception) {
            logger.error("Could not find LUT $lutName.")
        }
    }

    /** The following types are allowed for track coloring:
     * - [LUT] uses a colormap, defaults to Fire.lut
     * - [SPOT] uses the spot color from the connected spot */
    enum class ColorMode { LUT, SPOT, UNCERTAINTY }

    /** Allocates a [number] of instances to a [pool] that are part of [mainInstance]. */
    private fun addMoreInstances(
        mainInstance: InstancedNode,
        number: Int = 10000,
        pool: MutableList<InstancedNode.Instance>
    ) {
        val tStart = TimeSource.Monotonic.markNow()

        runBlocking {
            val batchSize = 3000
            val jobs = mutableListOf<Job>()

            for (batchStart in 0 until number step batchSize) {
                val batchEnd = minOf(batchStart + batchSize, number)
                val job = launch {
                    val localInstances = ArrayList<InstancedNode.Instance>(batchEnd - batchStart)
                    var inst: InstancedNode.Instance
                    for (i in batchStart until batchEnd) {
                        inst = mainInstance.addInstance()
                        inst.parent = mainInstance.parent
                        localInstances.add(inst)
                    }
                    // Add all instances from this batch to the pool at once
                    synchronized(pool) {
                        pool.addAll(localInstances)
                    }
                }
                jobs.add(job)
            }

            // Wait for all jobs to complete
            jobs.joinAll()
        }

        logger.info("adding $number ${mainInstance.name} instances took ${TimeSource.Monotonic.markNow()-tStart}.")
    }

    /** Helper method for safe enqueueing. Will drop tasks if the [updateQueue] exceeds its capacity (currently 100),
     * unless [isCritical] was set to true. */
    private fun enqueueUpdate(description: String, isCritical: Boolean = false, task: () -> Unit) {
        if (!updateQueue.offer(task)) {
            // Queue is full, now warn the user or execute if critical
            if (isCritical) {
                task.invoke()
            } else {
                logger.warn("Update queue full, dropping: $description")
            }
        }
    }

    /** Shows or initializes the main spot instance, publishes it to the scene and populates it
     * with instances from the current time-point, then adds color to them. */
    fun showInstancedSpots(
        timepoint: Int = manvr3d.currentTimepoint,
        colorizer: GraphColorGenerator<Spot, Link> = manvr3d.currentColorizer
    ) {
        enqueueUpdate("showInstancedSpots(tp=$timepoint)") {
            // Skip the rest if spots aren't visible
            if (!manvr3d.isSpotVisible) { return@enqueueUpdate }
            currentColorizer = colorizer
            logger.debug("Called showInstancedSpots")
            val tStart = TimeSource.Monotonic.markNow()
            sv.blockOnNewNodes = false
            manvr3d.bdvNotifier?.lockUpdates = true
            mastodonData.model.graph.lock.readLock().lock()

            // Ensure that mainSpotInstance is not null and properly initialized
            val mainSpot = mainSpotInstance ?: initSpotInstance()

            try {
                layoutSpots(timepoint, mainSpot)
                colorSpots()
            } finally {
                manvr3d.bdvNotifier?.lockUpdates = false
                mastodonData.model.graph.lock.readLock().unlock()
            }
            mainSpot.updateInstanceBuffers()

            val tElapsed = TimeSource.Monotonic.markNow() - tStart
            logger.debug("Spot updates took $tElapsed")
        }
    }

    /** Creates a new spot instance if [mainSpotInstance] is null, applies the correct material
     * and populates the [spotPool] with instances. */
    private fun initSpotInstance(): InstancedNode {
        if (mainSpotInstance != null) { return mainSpotInstance!! }
        sphere.setMaterial(
            ShaderMaterial.fromFiles(
                "DeferredInstancedColor.vert",
                "DeferredInstancedColorLensing.frag"
            )
        ) {
            diffuse = Vector3f(1.0f, 1.0f, 1.0f)
            ambient = Vector3f(1.0f, 1.0f, 1.0f)
            specular = Vector3f(.0f, 1.0f, 1.0f)
            metallic = 0.0f
            roughness = 1.0f
        }

        val mainSpot = InstancedNode(sphere)
        mainSpot.name = "SpotInstance"
        // Instanced properties should be aligned to 4*32bit boundaries, hence the use of Vector4f instead of Vector3f here
        mainSpot.instancedProperties["Color"] = { Vector4f(1f) }
        var inst: InstancedNode.Instance
        val maxSpotCount =
            mastodonData.model.spatioTemporalIndex.getSpatialIndex(mastodonData.maxTimepoint).size()
        // initialize the whole pool with instances once
        for (i in 0..<(maxSpotCount * 1.5).toInt()) {
            inst = mainSpot.addInstance()
            inst.parent = sphereParentNode
            spotPool.add(inst)
        }

        sv.addNode(mainSpot, parent = sphereParentNode, activePublish = false)
        mainSpot.updateInstanceBuffers()
        mainSpotInstance = mainSpot

        return mainSpot
    }

    /** Iterates over the current [timepoint] and draws all visible spots at their correct position and size,
     * reusing cached instances in [spotPool]. */
    private fun layoutSpots(timepoint: Int, mainSpot: InstancedNode) {
        // Clear the maps on the start of each timepoint
        instanceToSpotMap.clear()
        spotToInstanceMap.clear()
        visibleSpots = mastodonData.model.spatioTemporalIndex.getSpatialIndex(timepoint)
        // Pre-allocate memory to prevent recreation of variables inside the loop
        val pos = FloatArray(3)
        var index = 0

        for (spot in visibleSpots) {
            // TODO later: if (hideFilter?.contains(spot) == false) continue
            // reuse a spot instance from the pool if the pool is large enough
            val inst = if (index < spotPool.size) {
                spotPool[index]
            } else {
                // otherwise create a new instance and add it to the pool
                mainSpot.addInstance().also {
                    it.parent = sphereParentNode
                    spotPool.add(it)
                }
            }
            inst.visible = true

            spot.localize(pos)
            inst.spatial {
                position = Vector3f(pos)
                scale = Vector3f(sphereScaleFactor * getSpotRadius(spot))
            }
            spotToInstanceMap[spot.internalPoolIndex] = inst
            instanceToSpotMap[inst] = spot.internalPoolIndex
            index++
        }
        // turn all leftover spots from the pool invisible
        for (i in index until spotPool.size) spotPool[i].visible = false
    }

    private fun colorSpots() {
        val selection = mastodonData.selectionModel
        val focusRef = mastodonData.model.graph.vertexRef()
        val focused = mastodonData.focusModel.getFocusedVertex(focusRef)
        // We need to store elephants detection threshold here so we can normalize the uncertainty data.
        // They will be in range elephantThreshold to 1
        val threshold = ElephantMainSettingsManager.getInstance().forwardDefaultStyle.probThreshold
        val selectedInstances = ArrayList<InstancedNode.Instance>()

        // Check for the verified tags once, then use isVerified as a small lambda to check against the tag map
        val isVerified = verifiedSpotCheck()

        for (spot in visibleSpots) {
            val inst = spotToInstanceMap[spot.internalPoolIndex] ?: continue
            val isSelected = selection.isSelected(spot)
            if (isSelected) selectedInstances.add(inst)
            when {
                isSelected || spot == focused -> inst.instancedProperties["Color"] = { selectedColor }
                isVerified(spot) -> inst.instancedProperties["Color"] = { verifiedColor }
                else -> inst.setBaseSpotColor(spot, threshold, manvr3d.currentColorizer)
            }
        }
        // derived cache for the edit behaviors, only ever written here
        manvr3d.selectedSpotInstances.clear()
        manvr3d.selectedSpotInstances.addAll(selectedInstances)
        mastodonData.model.graph.releaseRef(focusRef)
    }

    /** Extension function that takes a spot and colors the corresponding instance according to the [colorizer]
     * or by Elephant's uncertainty feature, if available. Also */
    private fun InstancedNode.Instance.setBaseSpotColor(
        spot: Spot,
        threshold: Double,
        colorizer: GraphColorGenerator<Spot, Link>
    ) {
        if (manvr3d.showUncertainty) {
            val factor = (((manvr3d.qualityFeature?.value(spot) ?: 1.0) - threshold) / (1 - threshold)).toFloat()
            val color = lut.sample(if (manvr3d.invertLut) 1f - factor else factor)
            instancedProperties["Color"] = { color }
        } else {
            var intColor = colorizer.color(spot)
            if (intColor == 0x00000000) {
                intColor = DEFAULT_COLOR
            }
            val col = intColor.unpackRGB()
            this.instancedProperties["Color"] = { col }
        }
    }

    /** Recolors spots and links from the Mastodon selection/focus state without touching the layout. */
    fun refreshSelectionColors() {
        enqueueUpdate("refreshSelectionColors") {
            mainSpotInstance?.let { main ->
                if (!::visibleSpots.isInitialized) return@let
                mastodonData.model.graph.lock.readLock().lock()
                try { colorSpots() } finally { mastodonData.model.graph.lock.readLock().unlock() }
                main.updateInstanceBuffers()
            }
            updateLinkColors()
        }
    }

    private fun computeEigen(covariance: Array2DRowRealMatrix): Pair<DoubleArray, RealMatrix> {
        val eigenDecomposition = EigenDecomposition(covariance)
        val eigenvalues = eigenDecomposition.realEigenvalues
        val eigenvectors = eigenDecomposition.v
        return Pair(eigenvalues, eigenvectors)
    }

    // helper variable to make it easy to try out different vector orders
    // for converting covariance matrices to rotation quaternions
    val matrixOrder = Vector3i(0, 1, 2)

    private fun computeSemiAxes(eigenvalues: DoubleArray): Vector3f {
        return Vector3f(
            sqrt(eigenvalues[matrixOrder[0]]).toFloat(),
            sqrt(eigenvalues[matrixOrder[1]]).toFloat(),
            sqrt(eigenvalues[matrixOrder[2]]).toFloat()
        )
    }

    private fun getSpotRadius(spot: Spot): Float {
        return radiusCache.getOrPut(spot.internalPoolIndex) {
            val covArray = Array(3) { DoubleArray(3) }
            spot.getCovariance(covArray)
            val eig = EigenDecomposition(Array2DRowRealMatrix(covArray))
            val eigVals = eig.realEigenvalues
            var volume = 4.0 / 3.0 * Math.PI
            for (k in eigVals.indices) {
                val semiAxis = sqrt(eigVals[k])
                volume *= semiAxis
            }
            return (volume * 3.0 / 4.0 / Math.PI).pow(1.0 / 3.0).toFloat()
        }
    }

    /** Debug function to help with aligning ellipsoids with the eigenvectors from the covariance matrix.
     * @param [eigenVectors] The column-based eigenvectors of the covariance matrix
     * @param [axisLengths] The lengths per axis, given as [Vector3f]
     * */
    fun InstancedNode.Instance.drawEigenVectors(eigenVectors: RealMatrix, axisLengths: Vector3f) {

        val x = Vector3f(eigenVectors.getColumn(0).map { it.toFloat() }.toFloatArray()).normalize()
        val y = Vector3f(eigenVectors.getColumn(1).map { it.toFloat() }.toFloatArray()).normalize()
        val z = Vector3f(eigenVectors.getColumn(2).map { it.toFloat() }.toFloatArray()).normalize()

        val red = DefaultMaterial()
        red.diffuse = Vector3f(1f,0.2f, 0.2f)
        red.cullingMode = Material.CullingMode.None
        val green = DefaultMaterial()
        green.diffuse = Vector3f(0.2f,1f,0.2f)
        green.cullingMode = Material.CullingMode.None
        val blue = DefaultMaterial()
        blue.diffuse = Vector3f(0.2f,0.2f,1f)
        blue.cullingMode = Material.CullingMode.None

        val arrowX = Arrow(x.times(axisLengths.x))
        arrowX.addAttribute(Material::class.java, red)
        val arrowY = Arrow(y.times(axisLengths.y))
        arrowY.addAttribute(Material::class.java, green)
        val arrowZ = Arrow(z.times(axisLengths.z))
        arrowZ.addAttribute(Material::class.java, blue)

        for (a in arrayOf(arrowX, arrowY, arrowZ)) {
            a.spatial().position = this.spatial().position
            sv.addNode(a, false, parent = sphereParentNode)
        }
    }

    /** Converts this [RealMatrix] into a rotation [Quaternionf]. */
    private fun RealMatrix.matrixToQuaternion(verbose: Boolean = false): Quaternionf {

        val matrix3f = Matrix3f()

        val x = Vector3f(getColumn(0).map { it.toFloat() }.toFloatArray())
        val y = Vector3f(getColumn(1).map { it.toFloat() }.toFloatArray())
        val z = Vector3f(getColumn(2).map { it.toFloat() }.toFloatArray())

        matrix3f.setRow(matrixOrder.x, x)
        matrix3f.setRow(matrixOrder.y, y)
        matrix3f.setRow(matrixOrder.z, z)

        // matrix3f.transpose()

        val quaternion = Quaternionf()
        matrix3f.getNormalizedRotation(quaternion)
        quaternion.invert()
        if (verbose) {
            logger.info("converted matrix is \n $matrix3f")
            logger.info("quaternion is $quaternion")
        }
        return quaternion
    }

    /** Converts this [RealMatrix] into a rotation [Quaternionf].
     * Uses a different approach than [RealMatrix.matrixToQuaternion] for testing purposes. */
    private fun RealMatrix.alignToQuaternion(): Quaternionf {

        // extract
        val v1 = Vector3f(getColumn(0).map { it.toFloat() }.toFloatArray()).normalize()
        val v2 = Vector3f(getColumn(1).map { it.toFloat() }.toFloatArray()).normalize()
        val v3 = Vector3f(getColumn(2).map { it.toFloat() }.toFloatArray()).normalize()

        val quaternion = Quaternionf()
        // align longest axis
        quaternion.rotateTo(Vector3f(1f, 0f, 0f), v1)
        // align second longest axis
        val tempY = Vector3f(0f, 1f, 0f).rotate(quaternion)
        val correction = Quaternionf().rotateTo(tempY, v2)
        quaternion.mul(correction)
        // TODO does this need to be flipped for right- vs left-handed coordinate system? (Mastodon vs Sciview)
        quaternion.invert()
        return quaternion
    }

    /** Takes a tag set name and a tag name and tries to apply it to the selected [spots]. Uses all spots if [spots] is null.
     * Returns false if either name can't be found. */
    fun applyTagToSpots(tagSetName: String, tagName: String, spots: List<Spot>? = null): Boolean {
        val (ts, tag) = findTag(tagSetName, tagName) ?: return false
        val tsModel = mastodonData.model.tagSetModel
        val spots = spots ?: mastodonData.model.graph.vertices()
        spots.forEach { s ->
            tsModel.vertexTags.set(s, tag)
            s.outgoingEdges().forEach {e ->
                tsModel.edgeTags.set(e, tag)
            }
        }
        return true
    }


    val VERIFIED_SET = "Tracking"
    val VERIFIED_TAG = "Approved"
    private val verifiedColor = Vector4f(0.3f, 1f, 0.4f, 1f)

    private fun findTag(tagSetName: String, tagName: String): Pair<TagSetStructure.TagSet, TagSetStructure.Tag>? {
        val ts = mastodonData.model.tagSetModel.tagSetStructure.tagSets.find { it.name == tagSetName } ?: return null
        val tag = ts.tags.find { it.label() == tagName } ?: return null
        return ts to tag
    }

    /** Returns a cheap per-spot check. Resolve it once per color pass, not once per spot. */
    private fun verifiedSpotCheck(): (Spot) -> Boolean {
        val (ts, tag) = findTag(VERIFIED_SET, VERIFIED_TAG) ?: return { false }
        val map = mastodonData.model.tagSetModel.vertexTags.tags(ts)
        return { map.get(it) == tag }
    }
    private fun verifiedLinkCheck(): (Link) -> Boolean {
        val (ts, tag) = findTag(VERIFIED_SET, VERIFIED_TAG) ?: return { false }
        val map = mastodonData.model.tagSetModel.edgeTags.tags(ts)
        return { map.get(it) == tag }
    }

    fun markSelectionVerified() {
        enqueueUpdate("markSelectionVerified") {
            val (_, tag) = findTag(VERIFIED_SET, VERIFIED_TAG) ?: run {
                logger.warn("Tag set '$VERIFIED_SET' with tag '$VERIFIED_TAG' not found.")
                return@enqueueUpdate
            }
            val selection = mastodonData.selectionModel
            if (selection.isEmpty) return@enqueueUpdate
            val tags = mastodonData.model.tagSetModel
            selection.selectedEdges.forEach { tags.edgeTags.set(it, tag) }
            selection.selectedVertices.forEach { s ->
                tags.vertexTags.set(s, tag)
                s.outgoingEdges().forEach { e -> if (selection.isSelected(e.target)) tags.edgeTags.set(e, tag) }
            }
            mastodonData.model.setUndoPoint()
            refreshSelectionColors()
        }
    }

    fun updateLensPosition(position: Vector3f) {
        manvr3d.volumeNode.lensingPosition = position
        sphere.lensingPosition = position
        cylinder.lensingPosition = position
    }

    fun updateLensRadius(radius: Float) {
        sphere.lensingRadius = radius
        cylinder.lensingRadius = radius
    }

    /** Tries to find a spot in the current time point for the given [instance].
     * @return either a [Spot] or null. */
    fun findSpotFromInstance(instance: InstancedNode.Instance): Spot? {
        val spotIdx = instanceToSpotMap[instance] ?: return null
        return visibleSpots.find { it.internalPoolIndex == spotIdx }
    }

    /** Tries to find a spot instance in the current time point for the given [spot].
     * It does that by filtering through the names of the instances, which contain the internalPoolIndex.
     * @return either an [InstancedNode.Instance] or null. */
    fun findInstanceFromSpot(spot: Spot): InstancedNode.Instance? {
        return spotToInstanceMap[spot.internalPoolIndex]
    }

    /** Tries to find a link instance for the given [link].
     * @return either an [InstancedNode.Instance] or null. */
    fun findInstanceFromLink(link: Link): InstancedNode.Instance? {
        val results = links[link.internalPoolIndex]
        return if (results != null) {
            results.instance
        } else {
            logger.debug("Couldn't find an instance for $link")
            null
        }
    }

    /** Select a spot in Mastodon by passing its [instance] from the sciview side. */
    fun selectSpot2D(instance: InstancedNode.Instance) {
        // if one accidentally clicks a link instance and triggers this function, don't continue
        val selectedSpot = findSpotFromInstance(instance)
        selectedSpot?.let {
            mastodonData.focusModel.focusVertex(it)
            mastodonData.highlightModel.highlightVertex(it)
            mastodonData.selectionModel.setSelected(it, true)
        }
        refreshSelectionColors()
    }

    /** Given a [timepoint] and a [pos]ition, return the nearest spot in the Mastodon graph. */
    private fun findNearestSpot(timepoint: Int, pos: Vector3f): Spot? {
        val spatialIndex = mastodonData.model.spatioTemporalIndex.getSpatialIndex(timepoint)
        // only proceed if there are spots in the dataset to select from
        if (spatialIndex.size() > 0) {
            val spotSearch = spatialIndex.nearestNeighborSearch
            val p = InterestPoint(0, pos.toDoubleArray())
            spotSearch.search(p)
            val spot = spotSearch.sampler.get()
            return spot
        } else {
            return null
        }
    }

    /** Given an existing spot, find the closest neighbor in the Mastodon graph. */
    private fun findNearestSpot(spot: Spot): Spot? {
        val spatialIndex = mastodonData.model.spatioTemporalIndex.getSpatialIndex(spot.timepoint)
        if (spatialIndex.size() > 1) {
            val spotSearch = spatialIndex.incrementalNearestNeighborSearch
            spotSearch.search(spot)
            var found = spotSearch.next()
            // We don't want to accidentally loop forever
            var safetyIndex = 0
            // Grab the first spot that is not the spot itself, since it tends to be the first result
            while (spot == found && safetyIndex < 10) {
                found = spotSearch.next()
                safetyIndex++
            }
            return found
        } else {
            return null
        }
    }

    /** Returns a list of all spots within the given [radius] around the [origin] in the current [timepoint]. */
    private fun findSpotsInRange(timepoint: Int, origin: Vector3f, radius: Float, adjustedScale: Float = 1f): RefList<Spot> {
        val spots = RefCollections.createRefList(mastodonData.model.graph.vertices())
        val spatialIndex = mastodonData.model.spatioTemporalIndex.getSpatialIndex(timepoint)
        if (spatialIndex.size() > 0) {
            val spotSearch = spatialIndex.incrementalNearestNeighborSearch
            val p = InterestPoint(0, origin.toDoubleArray())
            spotSearch.search(p)
            var found: Spot
            while (spotSearch.hasNext()) {
                found = spotSearch.next()
                if (spotSearch.distance < (radius + getSpotRadius(found) * adjustedScale ) ) {
                    spots.add(found)
                } else {
                    break
                }
            }
        }
        return spots
    }

    /** Iterates over all spots of a given timepoint [tp], checks whether there are overlapping spots and merges them. */
    fun mergeOverlappingSpots(tp: Int) {
        enqueueUpdate("mergeOverlappingSpots(tp=$tp)") {
            val graph = mastodonData.model.graph
            mastodonData.model.setUndoPoint()
            manvr3d.bdvNotifier?.lockUpdates = true
            val spatialIndex = mastodonData.model.spatioTemporalIndex.getSpatialIndex(tp)
            val queue = RefCollections.createRefDeque(graph.vertices())
            queue.addAll(spatialIndex)
            val currentSpot = graph.vertexRef()
            val pos = FloatArray(3)
            var overlaps: RefList<Spot>
            while (queue.isNotEmpty()) {
                currentSpot.refTo(queue.poll())
                currentSpot.localize(pos)
                overlaps = findSpotsInRange(currentSpot.timepoint, Vector3f(pos), getSpotRadius(currentSpot))
                // The target spot is going to be the first in the overlap list, so we remove it
                overlaps.removeFirstOrNull()
                if (overlaps.isNotEmpty()) {
                    val spotList = RefCollections.createRefList(graph.vertices())
                    spotList.addAll(overlaps)
                    spotList.add(currentSpot)
                    mergeSpots(spotList)
                    // This would have been easier by simply writing queue.removeAll(overlaps), but this always
                    // missed the spot with index 0 for whatever reason.
                    val overlapIndices = overlaps.map { it.internalPoolIndex }.toSet()
                    queue.removeIf { spot -> overlapIndices.contains(spot.internalPoolIndex) }
                    queue.add(currentSpot)
                }
            }
            mastodonData.model.graph.releaseRef(currentSpot)
            clearSpotSelection()
            clearLinkSelection()
            manvr3d.bdvNotifier?.lockUpdates = false
            manvr3d.fileLogger.incrementMerge()
        }
    }

    /** Merges a list of spots together while keeping the connected edges.
     * Original spots will be removed from the graph and a new merged spot is created.
     * Positions and radii are averaged. */
    fun mergeSpots(spots: RefList<Spot>) {
        if (spots.isEmpty()) {
            return
        }
        manvr3d.bdvNotifier?.lockUpdates = true
        val graph = mastodonData.model.graph
        val sourceRef = graph.vertexRef()
        val targetRef = graph.vertexRef()
        val meanPos = Vector3f()
        var meanRadius = 0.0
        val pos = FloatArray(3)

        val incomingSpots = RefCollections.createRefList(graph.vertices())
        val outgoingSpots = RefCollections.createRefList(graph.vertices())

        // Collect incoming and outgoing spots from the other spots.
        // Prevent duplicates when A and B are connected to C, and A and B are merged
        incomingSpots.addAll(spots.flatMap { it.incomingEdges().map { it.source } }
            .distinctBy { it.internalPoolIndex })

        outgoingSpots.addAll(spots.flatMap { it.outgoingEdges().map { it.target } }
            .distinctBy { it.internalPoolIndex })

        // Accumulate positions and radii
        spots.forEach { spot ->
            targetRef.refTo(spot)
            targetRef.localize(pos)
            meanPos.add(Vector3f(pos))
            meanRadius += getSpotRadius(targetRef)
        }
        // Get the mean values
        meanPos /= spots.size.toFloat()
        meanRadius /= spots.size.toDouble()
        graph.lock.writeLock().lock()

        // Create a new spot that we reconnect before deleting the old ones
        val newSpot = graph.addVertex()
        newSpot.init(spots.first().timepoint, meanPos.toDoubleArray(), meanRadius)

        // Incoming edges
        incomingSpots.forEach { spot ->
            sourceRef.refTo(spot)
            targetRef.refTo(newSpot)
            val e = graph.addEdge(sourceRef, targetRef)
            e.init()
            logger.debug("Initialized edge $e with source $sourceRef and target $targetRef")
        }
        // Outgoing edges
        outgoingSpots.forEach { spot ->
            sourceRef.refTo(newSpot)
            targetRef.refTo(spot)
            val e = graph.addEdge(sourceRef, targetRef)
            e.init()
            logger.debug("Initialized edge $e with source $sourceRef and target $targetRef")
        }

        // Remove all old spots
        spots.forEach { spot ->
            sourceRef.refTo(spot)
            graph.remove(sourceRef)
        }
        logger.debug("Newly merged spot now has incoming edges ${newSpot.incomingEdges().map { it.internalPoolIndex }}" +
                " and outgoing edges ${newSpot.outgoingEdges().map { it.internalPoolIndex }}")

        graph.lock.writeLock().unlock()
        graph.releaseRef(sourceRef)
        graph.releaseRef(targetRef)
        manvr3d.bdvNotifier?.lockUpdates = false
    }

    /** Extension function that allows setting [Spot] covariances via passing a [radius]. */
    private fun Spot.setRadius(radius: Float) {
        val rSquared = radius.toDouble() * radius.toDouble()
        this.setCovariance(Array(3) { DoubleArray(3) }.also {
            it[0][0] = rSquared
            it[1][1] = rSquared
            it[2][2] = rSquared
        })
    }

    /** Perform incremental nearest neighbor search in the current timepoint [timepoint],
     * based on a position given by the VR controller [pos] and a search [radius].
     * [addOnly] specifies whether to only add to the selection. If false, clicking away from a spot will deselect everything.
     * [visualScale] adjust the search radius based on the visual scaling of the spots.
     * @return a Pair of the first selected spot itself and a boolean if the selection was valid (within the spot radius). */
    fun selectClosestSpotsVR(
        pos: Vector3f,
        timepoint: Int,
        radius: Float,
        addOnly: Boolean,
        visualScale: Float = 1f
    ): Pair<Spot?, Boolean> {
            val start = TimeSource.Monotonic.markNow()
            val localPos = manvr3d.sciviewToMastodonCoords(pos)
            val localRadius = manvr3d.sciviewToMastodonScale().max() * radius * visualScale
            val spots = findSpotsInRange(timepoint, localPos, localRadius, visualScale)
            // only proceed if we found at least one spot
            if (spots.isNotEmpty()) {
                spots.forEach { spot ->
                    if (mastodonData.selectionModel.isSelected(spot) && !addOnly) {
                        // if the spot is already selected, and we have a click event, deselect it
                        deselectSpot(spot)
                    } else {
                        selectSpot(spot)
                    }
                }
                logger.debug("Selecting spots in range took ${TimeSource.Monotonic.markNow() - start}")
                refreshSelectionColors()
                // Return the first spot if we found one
                return Pair(spots.firstOrNull(), true)
            } else {
                // Only clear the selection if no drag select behavior is currently active
                if (!addOnly) {
                    // Only clear and notify if there's actually something selected
                    if (manvr3d.selectedSpotInstances.isNotEmpty() || mastodonData.selectionModel.selectedVertices.isNotEmpty()) {
                        clearSpotSelection()
                        refreshSelectionColors()
                    }
                }

                return Pair(spots.firstOrNull(), false)
            }
        }

    /** Search for edges within a [radius] around [pos]. If [addOnly] is true, new edges will be added to the selection,
     * otherwise old selections will be cleared when a new selection event occurs. */
    fun selectClosestEdgesVR (pos: Vector3f, radius: Float, addOnly: Boolean) {
        val time = TimeSource.Monotonic.markNow()
        val localPos = manvr3d.sciviewToMastodonCoords(pos)
        val localRadius = manvr3d.sciviewToMastodonScale().max() * radius

        val tree = edgeCenterTree ?: return

        val search = RadiusNeighborSearchOnKDTree(tree)

        search.search(RealPoint(localPos.x.toDouble(),
            localPos.y.toDouble(),
            localPos.z.toDouble()),
            // Add a bit of margin for selecting the edge centers
            localRadius.toDouble() * 1.1, true)

        val anyHit = search.numNeighbors() > 0
        var linkNode: LinkNode

        for (i in 0 until search.numNeighbors()) {
            linkNode = search.getSampler(i).get()
            if (mastodonData.selectionModel.isSelected(linkNode.link) && !addOnly) {
                deselectLink(linkNode)
            } else {
                selectLink(linkNode)
            }
        }
        // Clicking away clears the selection, but only on click events, not with drag
        if (!anyHit && !addOnly) {
            manvr3d.selectedLinkNodes.forEach { deselectLink(it) }
        }
        refreshSelectionColors()

        logger.debug("Selecting links took ${TimeSource.Monotonic.markNow() - time}, got ${search.numNeighbors()} hits")
    }

    private fun selectSpot(spot: Spot) = mastodonData.selectionModel.setSelected(spot, true)

    private fun deselectSpot(spot: Spot) = mastodonData.selectionModel.setSelected(spot, false)

    private fun selectLink(linkNode: LinkNode) = mastodonData.selectionModel.setSelected(linkNode.link, true)

    private fun deselectLink(linkNode: LinkNode) = mastodonData.selectionModel.setSelected(linkNode.link, false)


    fun selectBranch() {
        val graph = mastodonData.model.graph
        val selection = mastodonData.selectionModel
        // Grab all selected spots, or source spots from selected edges
        val seeds = RefCollections.createRefSet(graph.vertices())
        seeds.addAll(selection.selectedVertices)
        selection.selectedEdges.forEach { seeds.add(it.source) }
        if (seeds.isEmpty()) { return }

        val branch = RefCollections.createRefSet(graph.vertices())

        // Traverse all selected spots, gather the branch of them only if they weren't traversed yet
        seeds.forEach { spot ->
            if (!branch.contains(spot)) {
                branch.addAll(gatherBranch(spot))
            }
        }

        // Now also prepare the edge selection
        val edges = RefCollections.createRefSet(graph.edges())
        branch.forEach { edges.addAll(it.outgoingEdges()) }

        // Set all newly found spots and edges to selected
        selection.pauseListeners()
        selection.setVerticesSelected(branch, true)
        selection.setEdgesSelected(edges, true)
        selection.resumeListeners()
        refreshSelectionColors()
    }

    /** Deletes the passed spots from the graph. */
    fun deleteSpots(spots: RefSet<Spot>) {
        enqueueUpdate("deleteSpots(count=${spots.size})") {
            manvr3d.bdvNotifier?.lockUpdates = true
            mastodonData.model.setUndoPoint()
            mastodonData.model.graph.lock.writeLock().lock()
            spots.forEach {
                mastodonData.model.graph.remove(it)
                logger.debug("Deleted spot {}", it)
            }
            mastodonData.model.graph.lock.writeLock().unlock()
            manvr3d.bdvNotifier?.lockUpdates = false
            clearSpotSelection()
            manvr3d.fileLogger.incrementSpotDeleted()
        }
    }

    fun clearSpotSelection() {
        manvr3d.selectedSpotInstances.clear()
        mastodonData.focusModel.focusVertex(null)
        mastodonData.selectionModel.clearSelection()
        mastodonData.highlightModel.clearHighlight()
        // If a selection was present, this label text was "Del" -> change it back to default
        if (manvr3d.isVRactive) {
            manvr3d.vrTracking.buttonMapper.let {
                it.mapper.updateLabel(it.ADD_DELETE_RESET, "Add", it.defaultColor)
            }
        }
        refreshSelectionColors()
    }

    fun clearLinkSelection() {
        manvr3d.selectedLinkNodes.clear()
        mastodonData.selectionModel.clearSelection()
        mastodonData.highlightModel.clearHighlight()
        if (manvr3d.isVRactive) {
            manvr3d.vrTracking.buttonMapper.let {
                it.mapper.updateLabel(it.ADD_DELETE_RESET, "Add", it.defaultColor)
            }
        }
        refreshSelectionColors()
    }

    /** Takes the given spot that was already moved in the BDV window and moves it in Sciview.
     * It also updates the connected edges and it is also called when a vertex is scaled on the BDV side. */
    fun moveAndScaleSpotInSciview(spot: Spot) {
         radiusCache.remove(spot.internalPoolIndex)
        val selectedInstance = findInstanceFromSpot(spot)
        val spotPosition = FloatArray(3)
        spot.localize(spotPosition)
        selectedInstance?.spatial {
            position = Vector3f(spotPosition)
            scale = Vector3f(sphereScaleFactor * getSpotRadius(spot))
        }
        val graph = mastodonData.model.graph
        val src = graph.vertexRef()
        val tgt = graph.vertexRef()
        spot.edges().forEach { e ->
            findInstanceFromLink(e)?.let { setLinkTransform(e.getSource(src), e.getTarget(tgt), it) }
        }
        graph.releaseRef(src)
        graph.releaseRef(tgt)
        mainSpotInstance?.updateInstanceBuffers()
        mainLinkInstance?.updateInstanceBuffers()
    }

    /** THIS IS A PURELY COSMETIC SETTING AND DOESN'T AFFECT THE TRUE RADIUS.
     * Takes a single instance, looks for the corresponding spot in the current timepoint,
     * and updates the instance's scale based on the current [sphereScaleFactor] and the spot's radius. */
    private fun adjustSpotInstanceScale(inst: InstancedNode.Instance) {
        findSpotFromInstance(inst)?.let { spot ->
            inst.spatial().scale = Vector3f(sphereScaleFactor * getSpotRadius(spot))
            inst.instancedParent.updateInstanceBuffers()
        }
    }

    /** Scales the covariance of [spots] by [factor] (radius changes by sqrt(factor)) and updates their instances.
     * Doesn't notify Mastodon, call [commitSpotChanges] once when the interaction ends. */
    fun scaleSpots(spots: Collection<Spot>, factor: Float) {
        val cov = Array(3) { DoubleArray(3) }
        spots.forEach { spot ->
            spot.getCovariance(cov)
            for (row in cov) for (j in row.indices) row[j] *= factor.toDouble()
            spot.setCovariance(cov)
            radiusCache.remove(spot.internalPoolIndex)
            findInstanceFromSpot(spot)?.spatial { scale = Vector3f(sphereScaleFactor * getSpotRadius(spot)) }
        }
        mainSpotInstance?.updateInstanceBuffers()
    }

    /** One-shot scaling of the selected spots in timepoint [tp], e.g. for keyboard shortcuts. */
    fun scaleSelectedSpots(tp: Int, factor: Float) {
        enqueueUpdate("scaleSelectedSpots") {
            val spots = RefCollections.createRefList(mastodonData.model.graph.vertices())
            collectSelectedSpots(tp, spots)
            if (spots.isEmpty()) return@enqueueUpdate
            scaleSpots(spots, factor)
            commitSpotChanges()
        }
    }

    fun commitSpotChanges() {
        mastodonData.model.setUndoPoint()
        mastodonData.model.graph.notifyGraphChanged()
    }

    fun invalidateRadii() = radiusCache.clear()

    /** Takes a list of Mastodon [Link]s, tries to find their corresponding instances and updates their transforms. */
    fun updateLinkTransforms(edgeIndices: List<Int>) {
        val graph = mastodonData.model.graph
        val edgeRef = graph.edgeRef()
        val sourceRef = graph.vertexRef()
        val targetRef = graph.vertexRef()

        for (edgeIdx in edgeIndices) {
            val edge = links[edgeIdx]?.link
            if (edge != null ) {
                sourceRef.refTo(edge.source)
                targetRef.refTo(edge.target)

                links[edgeIdx]?.let {
                    setLinkTransform(sourceRef, targetRef, it.instance)
                }
            } else {
                logger.warn("Couldn't find edge with index $edgeIdx for transform update.")
            }
        }
        mainLinkInstance?.updateInstanceBuffers()
        graph.releaseRef(sourceRef)
        graph.releaseRef(targetRef)
        graph.releaseRef(edgeRef)
    }

    /** Move [spots] by [delta] (Mastodon coords) in Mastodon and sciview, then update the [adjacentEdges]. */
    fun moveSpots(spots: Collection<Spot>, delta: Vector3f, adjacentEdges: List<Int>) {
        val d = delta.toFloatArray()
        spots.forEach { spot ->
            spot.move(d)
            findInstanceFromSpot(spot)?.spatial { position = position + delta }
        }
        mainSpotInstance?.updateInstanceBuffers()
        updateLinkTransforms(adjacentEdges)
    }

    /** Fills [spots] with the selected spots of timepoint [tp] and [adjacentEdges] with their adjacent edge indices. */
    fun collectSelectedSpots(tp: Int, spots: RefList<Spot>, adjacentEdges: MutableList<Int>? = null) {
        spots.clear()
        mastodonData.selectionModel.selectedVertices.forEach {
            if (it.timepoint == tp) spots.add(it)
        }
        adjacentEdges?.let { edges ->
            edges.clear()
            spots.forEach { s ->
                s.edges().forEach {
                    edges.add(it.internalPoolIndex)
                }
            }
        }

    }

    /** Sort a list of instances by their distance to a given [origin] position (e.g. of the camera)
     * @return a sorted copy of the mutable instance list.*/
    fun sortInstancesByDistance(
        spots: MutableList<InstancedNode. Instance>, origin: Vector3f
    ): MutableList<InstancedNode.Instance> {

        val start = TimeSource.Monotonic.markNow()
        val sortedSpots = spots.sortedBy { it.spatial().position.distance(origin) }.toMutableList()
        val end = TimeSource.Monotonic.markNow()
        logger.info("Spot sorting took ${end - start}.")
        return sortedSpots
    }


    fun updateSphereInstanceScales() {
        enqueueUpdate("updateSphereInstanceScales") {
            if (!::visibleSpots.isInitialized) return@enqueueUpdate
            for (spot in visibleSpots) {
                findInstanceFromSpot(spot)?.spatial { scale = Vector3f(sphereScaleFactor * getSpotRadius(spot)) }
            }
            mainSpotInstance?.updateInstanceBuffers()
        }
    }

    fun decreaseSphereInstanceScale(amount: Float = 0.1f) {
        sphereScaleFactor -= amount
        if (sphereScaleFactor < 0.1f) sphereScaleFactor = 0.1f
    }

    fun increaseSphereInstanceScale(amount: Float = 0.1f) {
        sphereScaleFactor += amount
    }

    fun resetSphereInstanceScale() {
        sphereScaleFactor = 1f
    }

    fun increaseLinkScale(amount: Float = 0.2f) {
        val oldScale = linkScaleFactor
        linkScaleFactor += amount
        val factor = linkScaleFactor / oldScale
        logger.debug("Increasing scale to $linkScaleFactor, by factor $factor")
    }

    fun decreaseLinkScale(amount: Float = 0.2f) {
        val oldScale = linkScaleFactor
        linkScaleFactor -= amount
        val factor = linkScaleFactor / oldScale
        logger.debug("Decreasing scale to $linkScaleFactor, by factor $factor")
    }

    /** Shows or initializes the main links instance, publishes it to the scene and populates it with instances from the current Mastodon graph. */
    fun showInstancedLinks(
        colorMode: ColorMode = currentColorMode
    ) {
        enqueueUpdate("showInstancedLinks()") {
            // Skip the rest if links aren't visible
            if (!manvr3d.isTrackVisible) {
                return@enqueueUpdate
            }

            val tStart = TimeSource.Monotonic.markNow()

            links.forEach {
                mastodonData.model.graph.releaseRef(it.value.link)
            }
            links.clear()
            if (mainLinkInstance == null) {
                cylinder.setMaterial(
                    ShaderMaterial.fromFiles("DeferredInstancedColor.vert", "DeferredInstancedColorLensing.frag" )
                ) {
                    diffuse = Vector3f(1.0f, 1.0f, 1.0f)
                    ambient = Vector3f(1.0f, 1.0f, 1.0f)
                    specular = Vector3f(.0f, 1.0f, 1.0f)
                    metallic = 0.0f
                    roughness = 1.0f
                }
                val mainLink = InstancedNode(cylinder)
                mainLink.name = "LinkInstance"
                mainLink.instancedProperties["Color"] = { Vector4f(1f) }

                // initialize the whole pool with instances once
                for (i in 0..<10000) {
                    linkPool.add(mainLink.addInstance())
                }
                logger.debug("initialized mainLinkInstance")
                sv.addNode(mainLink, parent = linkParentNode, activePublish = false)
                mainLink.updateInstanceBuffers()

                mainLinkInstance = mainLink
            }

            val mainLink = mainLinkInstance ?: throw IllegalStateException("InstancedLink is null, instance was not initialized.")

            currentColorMode = colorMode
            numTimePoints = mastodonData.maxTimepoint
            val graph = mastodonData.model.graph
            val from = graph.vertexRef()
            val to = graph.vertexRef()
            var inst: InstancedNode.Instance
            var index = 0
            val start = TimeSource.Monotonic.markNow()
            logger.debug("iterating over ${mastodonData.model.graph.edges().size} mastodon edges...")
            graph.edges().forEach { edge ->
                // reuse a link instance from the pool if the pool is large enough
                if (index < linkPool.size) {
                    inst = linkPool[index]
                    inst.visible = true
                }
                // otherwise create a new instance and add it to the pool
                else {
                    inst = mainLink.addInstance()
                    linkPool.add(inst)
                }

                edge.getSource(from)
                edge.getTarget(to)

                setLinkTransform(from, to, inst)
                inst.instancedProperties["Color"] = { Vector4f(1f, 1f, 1f, 1f) }
                inst.name = "${edge.internalPoolIndex}"
                inst.parent = linkParentNode

                // Calculate the edge center, which will then be used for edge selection
                val fromPos = FloatArray(3).also { from.localize(it) }
                val toPos = FloatArray(3).also { to.localize(it) }
                val center = Vector3f(
                    (fromPos[0] + toPos[0]) * 0.5f,
                    (fromPos[1] + toPos[1]) * 0.5f,
                    (fromPos[2] + toPos[2]) * 0.5f
                )

                // add a new key-value pair to the hash map
                links[edge.internalPoolIndex] =
                    LinkNode(inst, graph.edgeRef().refTo(edge), to.timepoint, center)

                index++
            }
            graph.releaseRef(from)
            graph.releaseRef(to)

            // turn all leftover links from the pool invisible
            var i = index
            while (i < linkPool.size) {
                linkPool[i++].visible = false
            }

            val transformStart = TimeSource.Monotonic.markNow()
            // treat link previews (placeholders during tracking) separately
            linkPreviewList.forEach { link ->
                setLinkTransform(link.from, link.to, link.instance)
            }
            logger.debug("Link transform updates took ${TimeSource.Monotonic.markNow() - transformStart}")

            val treeStart = TimeSource.Monotonic.markNow()
            // Build a KD tree for fast spatial querying of edges
            val points = links.values.map { linkNode ->
                // KDTree needs RealLocalizable points
                val point = RealPoint(
                    linkNode.center.x.toDouble(),
                    linkNode.center.y.toDouble(),
                    linkNode.center.z.toDouble()
                )
                Pair(point, linkNode)
            }
            edgeCenterTree = if (points.isNotEmpty()) KDTree(
                points.map { it.second },  // values
                points.map { it.first }    // positions
            ) else null

            logger.debug("Link kd tree building took ${TimeSource.Monotonic.markNow() - treeStart}")

            logger.debug("${links.size} links in the hashmap, ${linkPool.size} link instances in the pool. " +
                    "Mastodon provides ${mastodonData.model.graph.edges().size} links.")
            val end = TimeSource.Monotonic.markNow()

            logger.debug("Edge traversel took ${end - start}.")

            // Don't update buffers now, they'll be updated in updateSegmentVisibility again
            updateLinkColors(linkList = null, updateBuffers = false)
            updateSegmentVisibility(manvr3d.currentTimepoint)

            val tElapsed = TimeSource.Monotonic.markNow() - tStart
            logger.debug("Total link updates (with coloring) took $tElapsed")
        }
    }

    /** Takes a cylinder instance [inst] and two spots, [from] and [to], and positions the cylinder between them.
     * This function has an overload that takes vectors instead of spots. */
    private fun setLinkTransform(from: Spot, to: Spot, inst: InstancedNode.Instance) {
        // temporary container to get the position as array
        val pos = FloatArray(3)
        from.localize(pos)
        val posOrigin = Vector3f(pos)
        to.localize(pos)
        val posTarget = Vector3f(pos)
        posTarget.sub(posOrigin)
        inst.spatial {
            scale.set(linkSize * linkScaleFactor, posTarget.length().toDouble(), linkSize * linkScaleFactor)
            rotation = Quaternionf().rotateTo(Vector3f(0f, 1f, 0f), posTarget).normalize()
            position = Vector3f(posOrigin)
        }
    }

    /** Takes a cylinder instance [inst] and two [Vector3f], [from] and [to], and positions the cylinder between them.
     * This function has an overload that takes spots instead of vectors. */
    private fun setLinkTransform(from: Vector3f, to: Vector3f, inst: InstancedNode.Instance) {
        val linkVector = Vector3f(to).sub(from)
        inst.spatial {
            scale.set(linkSize, linkVector.length().toDouble(), linkSize)
            rotation = Quaternionf().rotateTo(Vector3f(0f, 1f, 0f), linkVector).normalize()
            position = Vector3f(from)
        }
    }

    /** Traverse and update the colors of all [links] using the provided color mode [cm].
     * Only updates the colors of certain links when a [linkList] is passed.
     * When set to [ColorMode.SPOT], it uses the [colorizer] to get the spot colors. */
    fun updateLinkColors (
        colorizer: GraphColorGenerator<Spot, Link>? = currentColorizer,
        cm: ColorMode = currentColorMode,
        linkList: List<LinkNode>? = null,
        updateBuffers: Boolean = true
    ) {
        val start = TimeSource.Monotonic.markNow()
        val links = linkList ?: links.entries.map { it.value }

        when (cm) {
            ColorMode.LUT -> {
                // Two separate loops to not perform the conditional logic for each iteration
                if (manvr3d.invertLut) {
                    links.forEach { linkNode ->
                        val factor = 1 - linkNode.tp / numTimePoints.toDouble()
                        val color = lut.sample(factor.toFloat())
                        linkNode.instance.instancedProperties["Color"] = { color }
                    }
                } else {
                    links.forEach { linkNode ->
                        val factor = linkNode.tp / numTimePoints.toDouble()
                        val color = lut.sample(factor.toFloat())
                        linkNode.instance.instancedProperties["Color"] = { color }
                    }
                }
            }
            ColorMode.SPOT -> {
                if (colorizer != null) {
                    var link: Link
                    links.forEach { linkNode ->
                        // Color based on the target spot
                        link = linkNode.link
                        var intColor = colorizer.color(link, link.source, link.target)
                        if (intColor == 0x00000000) {
                            intColor = DEFAULT_COLOR
                        }
                        val col = intColor.unpackRGB()
                        linkNode.instance.instancedProperties["Color"] = { col }
                    }
                }
            }
            ColorMode.UNCERTAINTY -> {
                val qualitySpec = mastodonData.model.featureModel.featureSpecs.find { it.key == "Detection quality" }

                if (qualitySpec != null) {
                    val qualityFeature =
                        mastodonData.model.featureModel.getFeature(qualitySpec) as? DetectionQualityFeature

                    // We need to store elephants detection threshold here so we can normalize the uncertainty data.
                    // They will be in range elephantThreshold to 1
                    val elephantSettings = ElephantMainSettingsManager.getInstance().forwardDefaultStyle
                    val elephantThreshold = elephantSettings.probThreshold

                    if (manvr3d.invertLut) {
                        links.forEach { linkNode ->
                            // Normalize the uncertainty into range 0-1
                            val uncertainty = qualityFeature?.value(linkNode.link.source) ?: 1.0
                            val factor = (uncertainty - elephantThreshold) / ( 1 - elephantThreshold)
                            val color = lut.sample(1 - factor.toFloat())
                            linkNode.instance.instancedProperties["Color"] = { color }
                        }
                    } else {
                        links.forEach { linkNode ->
                            val uncertainty = qualityFeature?.value(linkNode.link.source) ?: 1.0
                            val factor = (uncertainty - elephantThreshold) / ( 1 - elephantThreshold)
                            val color = lut.sample(factor.toFloat())
                            linkNode.instance.instancedProperties["Color"] = { color }
                        }
                    }

                } else {
                    logger.info("Could not find uncertainty information in the dataset. " +
                            "Make sure your annotation data come from ELEPHANT and that you use ELEPHANT server v0.7.0+.")
                }
            }
        }

        val isVerified = verifiedLinkCheck()
        links.forEach {
            if (isVerified(it.link)) {
                it.instance.instancedProperties["Color"] = { verifiedColor }
            }
        }

        // Repaint all selected edges
        val selection = mastodonData.selectionModel
        val selected = links.filter { selection.isSelected(it.link) }
        selected.forEach { it.instance.instancedProperties["Color"] = { selectedColor } }
        if (linkList == null) {
            manvr3d.selectedLinkNodes.clear()
            manvr3d.selectedLinkNodes.addAll(selected)
        }

        val end = TimeSource.Monotonic.markNow()
        if (updateBuffers) {
            mainLinkInstance?.updateInstanceBuffers()
        }
        logger.debug("Updating link colors took ${end - start}.")
    }

    fun updateSegmentVisibility(currentTP: Int = manvr3d.currentTimepoint) {
        links.forEach {link ->
            // turns the link on if it is within range, otherwise turns it off
            link.value.instance.visible = link.value.tp in currentTP - linkBackwardRange..currentTP + linkForwardRange
        }
        mainLinkInstance?.updateInstanceBuffers()
    }

    fun setSpotVisibility(state: Boolean) {
        mainSpotInstance?.let {
            it.visible = state
            it.updateInstanceBuffers()
        }
    }

    fun setTrackVisibility(state: Boolean)  {
        mainLinkInstance?.let {
            it.visible = state
            it.updateInstanceBuffers()
        }
    }

    /** Send a list of tracked points from sciview to Mastodon, stored in [trackPointList]. */
    fun addTrackToMastodon(trackPointList: MutableList<TrackedPoint>) {
        enqueueUpdate("addTrackToMastodon(points=${trackPointList.size})") {
            val graph = mastodonData.model.graph
            val prevVertex = graph.vertexRef()
            val vertex = graph.vertexRef()
            manvr3d.bdvNotifier?.lockUpdates = true
            // If the list isn't null, it was passed from eyetracking, and we have to treat it accordingly
            // Otherwise we did controller tracking and the points are inside trackPointList and not in list
            var localRadius: Float
            trackPointList.forEachIndexed { index, trackedPoint ->
                logger.debug("Adding track to Mastodon. Iteration $index: $trackedPoint")
                // Calculate the equivalent radius in Mastodon from the cursor's raw radius in sciview scale
                localRadius = manvr3d.sciviewToMastodonScale().max() * trackedPoint.radius

                if (trackedPoint.spot != null) {
                    vertex.refTo(trackedPoint.spot)
                } else {
                    val v = graph.addVertex()
                    // val localPos = if (isWorldSpace) manvr3d.sciviewToMastodonCoords(pos) else pos
                    v.init(trackedPoint.tp, trackedPoint.pos.toDoubleArray(), localRadius.toDouble())
                    // Manually set this point's quality to perfect if we have the feature available
                    manvr3d.qualityFeature?.set(v, 1.0)
                    vertex.refTo(v)
                    logger.debug("added {}", v)
                }
                // start adding edges once the first vertex was added
                if (index > 0) {
                    val e = if (prevVertex.timepoint < vertex.timepoint) {
                        graph.addEdge(prevVertex, vertex)
                    } else {
                        graph.addEdge(vertex, prevVertex)
                    }
                    e.init()
                    logger.debug("added {}", e)
                }
                prevVertex.refTo(vertex)
            }

            graph.releaseRef(prevVertex)
            graph.releaseRef(vertex)
            manvr3d.bdvNotifier?.lockUpdates = false
            // Once we send the new track to Mastodon, we can assume we no longer need the previews and can clear them
            mainLinkInstance?.instances?.removeAll(linkPreviewList.map { it.instance }.toSet())
            linkPreviewList.clear()
            clearSpotSelection()
        }
    }

    fun addEyeTrackToMastodon(
        list: List<Pair<Vector3f, SpineGraphVertex>>?,
        cursorRadius: Float
    ) {
        enqueueUpdate("addEyeTrackToMastodon(points=${list?.size})") {
            val graph = mastodonData.model.graph
            var prevVertex = graph.vertexRef()
            manvr3d.bdvNotifier?.lockUpdates = true
            // If the list isn't null, it was passed from eyetracking, and we have to treat it accordingly
            if (list != null) {
                list.forEachIndexed { index, (pos, vertex) ->
                    val v = graph.addVertex()
                    val r = (manvr3d.sciviewToMastodonScale().max() * cursorRadius).toDouble()
                    v.init(vertex.timepoint, pos.toDoubleArray(), r)
                    logger.debug("added {}", v)
                    // start adding edges once the first vertex was added
                    if (index > 0) {
                        val e = graph.addEdge(v, prevVertex)
                        e.init()
                        logger.debug("added {}", e)
                    }
                    prevVertex = graph.vertexRef().refTo(v)
                }
            }
        }
    }

    /** Send individual spots from sciview to Mastodon or delete them if a spot is already selected,
     * as we use the same VR button for creation and deletion.
     * @param tp the current timepoint
     * @param sciviewPos a sciview position
     * @param deleteBranch a flag that determines whether to delete the whole branch.  */
    fun addOrRemoveSpotsAndEdges (tp: Int, sciviewPos: Vector3f, radius: Float, deleteBranch: Boolean, isWorldSpace: Boolean) {
        enqueueUpdate("addTrackToMastodon(tp=$tp)") {
            manvr3d.bdvNotifier?.lockUpdates = true
            // Check if a spot is selected, and perform deletion if true
            val selectedSpots = mastodonData.selectionModel.selectedVertices
            var selectedEdges = mastodonData.selectionModel.selectedEdges
            // Treat spots first
            if (!selectedSpots.isEmpty()) {
                if (!deleteBranch) {
                    deleteSpots(selectedSpots)
                    manvr3d.recordVREditEvent("Spots deleted",)
                } else {
                    logger.info("Deleting the whole branch...")
                    val spotList = mutableListOf<Spot>()
                    // Perform a recursive forward and backward search for each selected spot
                    // This deletes all branches connected to the selected spot(s)
                    selectedSpots.forEach {
                        spotList.addAll(gatherBranch(it))
                    }
                    mastodonData.model.graph.lock.writeLock().lock()
                    spotList.distinct().forEach {
                        mastodonData.model.graph.remove(it)
                    }
                    mastodonData.model.graph.lock.writeLock().unlock()

                    manvr3d.fileLogger.incrementBranchDeleted()
                    manvr3d.recordVREditEvent("Branch deleted",)
                }
                clearSpotSelection()
                mastodonData.model.graph.notifyGraphChanged()
            } else {
                // Only add a new spot if the edges are empty too (otherwise delete the edges later on)
                if (selectedEdges.isEmpty()) {
                    // If no spot is selected, add a new one
                    val pos = if (isWorldSpace) {
                        manvr3d.sciviewToMastodonCoords(sciviewPos)
                    } else {
                        sciviewPos
                    }
                    val bb = manvr3d.volumeNode.boundingBox
                    if (bb != null) {
                        if (bb.isInside(pos)) {
                            val localRadius = manvr3d.sciviewToMastodonScale().max() * radius
                            val v = mastodonData.model.graph.addVertex()
                            v.init(tp, pos.toDoubleArray(), localRadius.toDouble())
                            // Set the detection quality to true if we have such a feature available
                            manvr3d.qualityFeature?.set(v, 1.0)
                            logger.info("Added new spot at position $pos, radius is $localRadius")
                            logger.debug("we now have ${mastodonData.model.graph.vertices().size} spots in total")
                            manvr3d.fileLogger.incrementSpotAdded()
                            manvr3d.recordVREditEvent("Spot added")
                        } else {
                            logger.warn("Not adding new spot, $pos is outside the volume!")
                            manvr3d.flashVolumeGrid()
                        }
                    } else {
                        logger.warn("Not adding new spot, volume has no bounding box!")
                    }
                }
            }
            // Update the selected edges to see whether there are still some left after deleting the spots
            selectedEdges = mastodonData.selectionModel.selectedEdges
            if (!selectedEdges.isEmpty()) {
                mastodonData.model.setUndoPoint()
                manvr3d.bdvNotifier?.lockUpdates = true
                mastodonData.model.graph.lock.writeLock().lock()
                if (deleteBranch) {
                    // Delete the branches corresponding to the selected edges
                    val selectedSpots = RefCollections.createRefList(mastodonData.model.graph.vertices())
                    selectedEdges.forEach { edge ->
                        if (!selectedSpots.contains(edge.source)) {
                            logger.debug("Adding spots to the list from edge ${edge.internalPoolIndex}")
                            selectedSpots.addAll(gatherBranch(edge.source))
                        }
                    }
                    selectedSpots.forEach {
                        mastodonData.model.graph.remove(it)
                    }
                    manvr3d.fileLogger.incrementBranchDeleted()
                } else {
                    // Otherwise only delete edges and don't touch the spots themselves
                    selectedEdges.forEach { edge ->
                        mastodonData.model.graph.remove(edge)
                    }
                    manvr3d.fileLogger.incrementEdgeDeleted()
                    manvr3d.recordVREditEvent("Edges deleted")
                }
                mastodonData.model.graph.lock.writeLock().unlock()
                manvr3d.bdvNotifier?.lockUpdates = false
                clearLinkSelection()
                mastodonData.model.graph.notifyGraphChanged()
            }

            manvr3d.bdvNotifier?.lockUpdates = false
        }
    }

    /** Recursively traverse every sub-branch connected to a spot. Returns a list of all spots that are connected
     * to the spot. */
    fun gatherBranch(spot: Spot): List<Spot> {
        val spotList = mutableListOf<Spot>()
        val spotRef = mastodonData.model.graph.vertexRef()
        spotRef.refTo(spot)
        // Add the actual spot to the list first
        spotList.add(spotRef)

        fun forwardSearch(s: Spot) {
            s.outgoingEdges().forEach {
                spotList.add(it.target)
                forwardSearch(it.target)
            }
        }

        fun backwardSearch(s: Spot) {
            s.incomingEdges().forEach {
                spotList.add(it.source)
                backwardSearch(it.source)
            }
        }

        forwardSearch(spotRef)
        backwardSearch(spotRef)
        return spotList
    }


    /** Adds a single link instance to the scene for visual feedback during controller based tracking.
     * No data are sent to Mastodon yet, but we keep track of the points in local space in a [trackPointList]. */
    fun addTrackedPoint(
        pos: Vector3f,
        tp: Int,
        radius: Float,
        spot: Spot?,
        preview: Boolean,
        trackPointList: MutableList<TrackedPoint>
    ) {
        val localPos = manvr3d.sciviewToMastodonCoords(pos)
        // Once we tracked the first point, we can start adding link previews
        if (trackPointList.isNotEmpty() && mainLinkInstance != null) {
            val inst = mainLinkInstance!!.addInstance()
            val color = Vector4f(0.65f, 1f, 0.22f, 1f)
            inst.instancedProperties["Color"] = { color }
            inst.parent = linkParentNode
            inst.visible = preview
            setLinkTransform(trackPointList.last().pos, localPos, inst)
            val link = LinkPreview(inst, trackPointList.last().pos, localPos, tp)
            linkPreviewList.add(link)
            logger.debug("Added a new preview link from {} to {}. Visibility is {}", link.from, link.to, preview)
        }
        logger.debug("Adding tracked point to trackPointList now")
        trackPointList.add(TrackedPoint(localPos, tp, radius, spot))
    }

    /** Toggle the preview links that are rendered during controller tracking */
    val toggleLinkPreviews: (state: Boolean) -> Unit = { state ->
        linkPreviewList.forEach {
            it.instance.visible = state
            logger.debug("set instance ${it.instance.name} to $state")
        }
        mainLinkInstance?.updateInstanceBuffers()
    }

    fun hsvToRGBA(hue: Int, saturation: Int, value: Int): Vector4f {
        val h = hue / 360.0f
        val s = saturation / 100.0f
        val v = value / 100.0f

        val rgbInt = Color.HSBtoRGB(h, s, v)
        return rgbInt.unpackRGB()
    }
}

