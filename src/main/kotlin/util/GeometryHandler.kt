package util

import graphics.scenery.*
import graphics.scenery.attribute.material.DefaultMaterial
import graphics.scenery.attribute.material.Material
import graphics.scenery.numerics.Random
import graphics.scenery.primitives.Arrow
import graphics.scenery.primitives.Cylinder
import graphics.scenery.utils.extensions.*
import graphics.scenery.utils.lazyLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.imglib2.display.ColorTable
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
import Manvr3dMain
import org.mastodon.mamut.model.Link
import org.mastodon.mamut.model.Spot
import org.mastodon.spatial.SpatialIndex
import org.mastodon.ui.coloring.GraphColorGenerator
import org.mastodon.views.bdv.overlay.util.JamaEigenvalueDecomposition
import org.scijava.event.EventService
import sc.iview.SciView
import analysis.HedgehogAnalysis.SpineGraphVertex
import spim.fiji.spimdata.interestpoints.InterestPoint
import util.GeometryHandler.ColorMode.LUT
import util.GeometryHandler.ColorMode.SPOT
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
 * @param updateQueue Queue that executues scene updates sequentially in its own thread.
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
    lateinit var lut: ColorTable
    var currentColorMode: ColorMode
    val spotPool: MutableList<InstancedNode.Instance> = ArrayList(10000)
    val linkPool: MutableList<InstancedNode.Instance> = ArrayList(10000)
    var events: EventService? = null

    val sphere = Icosphere(1f, 2)
    val cylinder = Cylinder(0.2f, 1f, 6, true, true)
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

    init {
        events = sv.scijavaContext?.getService(EventService::class.java)
        numTimePoints = mastodonData.maxTimepoint

        setLUT("Fire.lut")
        currentColorMode = ColorMode.LUT

        linkForwardRange = mastodonData.maxTimepoint
        linkBackwardRange = mastodonData.maxTimepoint
    }

    fun setLUT(lutName: String) {
        try {
            lut = sv.getLUT(lutName)
        } catch (e: Exception) {
            logger.error("Could not find LUT $lutName.")
        }
    }

    /** The following types are allowed for track coloring:
     * - [LUT] uses a colormap, defaults to Fire.lut
     * - [SPOT] uses the spot color from the connected spot */
    enum class ColorMode { LUT, SPOT }

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
            jobs.forEach { it.join() }
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

    /** Shows or initializes the main spot instance, publishes it to the scene and populates it with instances from the current time-point. */
    fun showInstancedSpots(
        timepoint: Int,
        colorizer: GraphColorGenerator<Spot, Link>
    ) {
        enqueueUpdate("showInstancedSpots(tp=$timepoint)") {
            currentColorizer = colorizer
            logger.debug("Called showInstancedSpots")
            val tStart = TimeSource.Monotonic.markNow()
            // only create and add the main instance once during initialization
            if (mainSpotInstance == null) {
                sphere.setMaterial(
                    ShaderMaterial.fromFiles(
                        "DeferredInstancedColor.vert",
                        "DeferredInstancedColor.frag"
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
                for (i in 0..<(maxSpotCount * 1.2).toInt()) {
                    inst = mainSpot.addInstance()
                    inst.parent = sphereParentNode
                    spotPool.add(inst)
                }

                sv.addNode(mainSpot, parent = sphereParentNode, activePublish = false)
                mainSpot.updateInstanceBuffers()
                mainSpotInstance = mainSpot
            }

            // Ensure that mainSpotInstance is not null and properly initialized
            val mainSpot =
                mainSpotInstance ?: throw IllegalStateException("InstancedSpot is null, instance was not initialized.")

            // Clear the maps on the start of each timepoint
            instanceToSpotMap.clear()
            spotToInstanceMap.clear()

            val selectedSpotRef = mastodonData.selectionModel.selectedVertices
            visibleSpots = mastodonData.model.spatioTemporalIndex.getSpatialIndex(timepoint)
            sv.blockOnNewNodes = false

            // Pre-allocate memory to prevent recreation of variables inside the loop
            val spotPosition = FloatArray(3)
            var spotRadius: Float
            var inst: InstancedNode.Instance

            logger.debug("we have ${visibleSpots.size()} spots in this Mastodon time point.")
            manvr3d.bdvNotifier?.lockUpdates = true
            val vertexRef = mastodonData.model.graph.vertexRef()
            mastodonData.model.graph.lock.readLock().lock()

            var index = 0
            for (spot in visibleSpots) {
                vertexRef.refTo(spot)
                // reuse a spot instance from the pool if the pool is large enough
                if (index < spotPool.size) {
                    inst = spotPool[index]
                    inst.visible = true
                }
                // otherwise create a new instance and add it to the pool
                else {
                    inst = mainSpot.addInstance()
                    inst.parent = sphereParentNode
                    spotPool.add(inst)
                }
                // get spot covariance and calculate the scaling and rotation from it
                vertexRef.localize(spotPosition)
                spotRadius = getSpotRadius(vertexRef)

                inst.spatial {
                    position = Vector3f(spotPosition)
                    scale = Vector3f(sphereScaleFactor * spotRadius)
                    // TODO add ellipsoid scale & rotation to instances
                }

                inst.setColorFromSpot(vertexRef, currentColorizer)
                // highlight the spots currently selected in BDV
                selectedSpotRef.find { it.internalPoolIndex == vertexRef.internalPoolIndex }?.let {
                    inst.instancedProperties["Color"] = { selectedColor }
                }

                spotToInstanceMap[vertexRef.internalPoolIndex] = inst
                instanceToSpotMap[inst] = vertexRef.internalPoolIndex

                index++
            }
            manvr3d.bdvNotifier?.lockUpdates = false
            mastodonData.model.graph.lock.readLock().unlock()
            // turn all leftover spots from the pool invisible
            var i = index
            while (i < spotPool.size) {
                spotPool[i++].visible = false
            }

            mainSpot.updateInstanceBuffers()

            val tElapsed = TimeSource.Monotonic.markNow() - tStart
            logger.debug("Spot updates took $tElapsed")
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

    /** Extension function that takes a spot and colors the corresponding instance according to the [colorizer]. */
    private fun InstancedNode.Instance.setColorFromSpot(
        s: Spot,
        colorizer: GraphColorGenerator<Spot, Link>,
        randomColors: Boolean = false,
        isPartyMode: Boolean = false
    ) {
        var intColor = colorizer.color(s)
        if (intColor == 0x00000000) {
            intColor = DEFAULT_COLOR
        }
        if (!isPartyMode) {
            if (!randomColors) {
                val col = intColor.unpackRGB()
                this.instancedProperties["Color"] = { col }
            } else {
                val col = Random.random3DVectorFromRange(0f, 1f).stretchColor()
                this.instancedProperties["Color"] = { col.xyzw() }
            }
        } else {
            this.instancedProperties["Color"] = { Random.random3DVectorFromRange(0f, 1f).xyzw() }
        }
    }

    /** Takes a tag set name and a tag name and tries to apply it to all spots.
     * Returns false if either name can't be found. */
    fun applyTagToAllSpots(tagSetName: String, tagName: String): Boolean {
        val tsModel = mastodonData.model.tagSetModel
        val ts = tsModel.tagSetStructure.tagSets.find { it.name == tagSetName } ?: return false
        val tag = ts.tags?.find { it.label() == tagName } ?: return false
        val spots = mastodonData.model.graph.vertices()
        spots.forEach { s ->
            tsModel.vertexTags.set(s, tag)
        }
        return true
    }

    /** Tries to find a spot in the current time point for the given [instance].
     * It does that by filtering through the names of the spots.
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
            logger.info("Couldn't find an instance for $link")
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

    /** Returns a list of all spots within the given [radius] around the [origin] in the current [timepoint]. */
    private fun findSpotsInRange(timepoint: Int, origin: Vector3f, radius: Float): RefList<Spot> {
        val spots = RefCollections.createRefList(mastodonData.model.graph.vertices())
        val spatialIndex = mastodonData.model.spatioTemporalIndex.getSpatialIndex(timepoint)
        if (spatialIndex.size() > 0) {
            val spotSearch = spatialIndex.incrementalNearestNeighborSearch
            val p = InterestPoint(0, origin.toDoubleArray())
            spotSearch.search(p)
            var found: Spot
            while (spotSearch.hasNext()) {
                found = spotSearch.next()
                if (spotSearch.distance < (radius + getSpotRadius(found) ) ) {
                    spots.add(found)
                } else {
                    break
                }
            }
        }
        return spots
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
            clearSelection()
            manvr3d.bdvNotifier?.lockUpdates = false
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

    /** Lambda that performs incremental nearest neighbor search in the current timepoint (Int),
     * based on a position given by the VR controller (Vector3f) and a search radius.
     * The Boolean addOnly specifies whether to only add to the selection. If false, clicking away from a spot will deselect everything.
     * @return a Pair of the first selected spot itself and a boolean if the selection was valid (in the spot radius). */
    val selectClosestSpotsVR: ((pos: Vector3f, tp: Int, radius: Float, addOnly: Boolean) -> Pair<Spot?, Boolean>) =
        { pos, tp, radius, addOnly ->
            val start = TimeSource.Monotonic.markNow()
            val localPos = manvr3d.sciviewToMastodonCoords(pos)
            val localRadius = manvr3d.sciviewToMastodonScale().max() * radius
            val spots = findSpotsInRange(tp, localPos, localRadius)
            // only proceed if we found at least one spot
            if (spots.isNotEmpty()) {
                spots.forEach { spot ->
                    if (mastodonData.selectionModel.isSelected(spot) && !addOnly) {
                        deselectSpot(spot)
                    } else {
                        selectSpot(spot)
                    }
                }
                logger.debug("Selecting spots in range took ${TimeSource.Monotonic.markNow() - start}")
                mainSpotInstance?.updateInstanceBuffers()
                // Return the first spot if we found one
                Pair(spots.firstOrNull(), true)
            } else {
                // Only clear the selection if no drag select behavior is currently active
                if (!addOnly) {
                    clearSelection()
                    mastodonData.model.graph.notifyGraphChanged()
                }
                mainSpotInstance?.updateInstanceBuffers()
                Pair(spots.firstOrNull(), false)
            }
        }

    private fun selectSpot(spot: Spot) {
        findInstanceFromSpot(spot)?.let {
            manvr3d.selectedSpotInstances.add(it)
            it.instancedProperties["Color"] = { selectedColor }
            it.instancedParent.updateInstanceBuffers()
            mastodonData.selectionModel.setSelected(spot, true)
        }
    }

    private fun deselectSpot(spot: Spot) {
        findInstanceFromSpot(spot)?.let {
            manvr3d.selectedSpotInstances.remove(it)
            it.setColorFromSpot(spot, currentColorizer)
            mastodonData.selectionModel.setSelected(spot, false)
        }

    }

    /** Deletes the currently selected Spots from the graph. */
    val deleteSpots: ((spots: RefSet<Spot>) -> Unit) = { spots ->
        enqueueUpdate("deleteSpots(count=${spots.size})") {
            manvr3d.bdvNotifier?.lockUpdates = true
            mastodonData.model.setUndoPoint()
            mastodonData.model.graph.lock.writeLock().lock()
            spots.forEach {
                mastodonData.model.graph.remove(it)
                logger.debug("Deleted spot {}", it)
            }
            mastodonData.model.graph.lock.writeLock().unlock()
            this@GeometryHandler.manvr3d.selectedSpotInstances.clear()
            manvr3d.bdvNotifier?.lockUpdates = false
        }
    }

    /** This lambda is used to handle merge events. If the user clicked on an existing spot during controller tracking,
     * they want to merge into it. The clicked spot will be the selected one,
     * and the to-be-merged spot is (hopefully) right next to it. */
    val mergeSelectedToClosestSpot: (() -> Unit) = fun() {
        if (mastodonData.selectionModel.selectedVertices.size > 0) {
            val graph = mastodonData.model.graph
            val selectedRef = graph.vertexRef()
            val nearestRef = graph.vertexRef()
            selectedRef.refTo(mastodonData.selectionModel.selectedVertices.first())
            val nearest = findNearestSpot(selectedRef)
            if (nearest != null) {
                nearestRef.refTo(nearest)
            } else {
                logger.warn("Nearest spot is null, can't merge!")
                return
            }
            logger.info("Trying to merge spot $nearestRef into $selectedRef")
            // Now that we found the nearest spot, let's merge it into the selected one
            nearestRef?.let {
                manvr3d.bdvNotifier?.lockUpdates = true
                val sourceRef = graph.vertexRef()
                val targetRef = graph.vertexRef()

                val incoming = nearestRef.incomingEdges() + selectedRef.incomingEdges()
                incoming.forEach { edge ->
                    sourceRef.refTo(edge.source)
                    graph.remove(edge)
                    val e = graph.addEdge(sourceRef, selectedRef)
                    e.init()
                    logger.debug("Merge event: added incoming edge {}, deleted old edge {}", e, edge)
                }

                val outgoing = nearestRef.outgoingEdges() + selectedRef.outgoingEdges()
                outgoing.forEach { edge ->
                    targetRef.refTo(edge.target)
                    graph.remove(edge)
                    val e = graph.addEdge(selectedRef, targetRef)
                    e.init()
                    logger.debug("Merge event: added outgoing edge {}, deleted old edge {}", e, edge)
                }
                graph.remove(nearestRef)
                manvr3d.bdvNotifier?.lockUpdates = false
                graph.notifyGraphChanged()
            }
        }
    }

    fun clearSelection() {
        manvr3d.selectedSpotInstances.clear()
        mastodonData.focusModel.focusVertex(null)
        mastodonData.selectionModel.clearSelection()
        mastodonData.highlightModel.clearHighlight()
    }

    /** Takes the given spot instance that was already moved in Sciview and moves it in the BDV window.  */
    fun moveSpotInBDV(instance: InstancedNode.Instance?, distance: Vector3f) {
        val selectedSpot = instance?.let { findSpotFromInstance(it) }
        selectedSpot?.let {
            mastodonData.model.graph.vertexRef().refTo(selectedSpot).move(distance.toFloatArray())
        }
    }

    /** Takes the given spot that was already moved in the BDV window and moves it in Sciview.
     * It also updates the connected edges and it is also called when a vertex is scaled on the BDV side. */
    fun moveAndScaleSpotInSciview(spot: Spot) {
        val selectedInstance = findInstanceFromSpot(spot)
        val spotPosition = FloatArray(3)
        spot.localize(spotPosition)
        selectedInstance?.spatial {
            position = Vector3f(spotPosition)
            scale = Vector3f(sphereScaleFactor * getSpotRadius(spot))
        }
        val edges = spot.incomingEdges() + spot.outgoingEdges()
        val edgeRef = mastodonData.model.graph.edgeRef()
        edges.forEach { edge ->
            edgeRef.refTo(edge)
            findInstanceFromLink(edgeRef)?.let {
                setLinkTransform(edgeRef.source, edgeRef.target, it)
            }
        }
        mastodonData.model.graph.releaseRef(edgeRef)
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

    /** Called when a spot's radius is changed in the sciview window. This changes both the actual spot radius in BDV
     * and its apparent scale in sciview. Setting the [update] flag to false allows deferring the actual Mastodon update.
     * This can be used for drag behaviors in VR that don't require continuous Mastodon updating.*/
    fun changeSpotRadius(instances: List<InstancedNode.Instance>, factor: Float, update: Boolean = true) {
        instances.forEach {
            val spot = findSpotFromInstance(it)
            val covArray = Array(3) { DoubleArray(3) }
            spot?.getCovariance(covArray)
            for (i in covArray.indices) {
                for (j in covArray[i].indices) {
                    covArray[i][j] *= factor.toDouble()
                }
            }
            spot?.setCovariance(covArray)
            if (update) {
                mastodonData.model.setUndoPoint()
                mastodonData.model.graph.notifyGraphChanged()
            }
            it.spatial().scale *= Vector3f(sqrt(factor))
        }
        mainSpotInstance?.updateInstanceBuffers()
    }

    /** Takes a list of Mastodon [Link]s, tries to find their corresponding instances and updates their transforms. */
    fun updateLinkTransforms(edgeIndices: List<Int>) {
        val graph = mastodonData.model.graph
        val edgeRef = graph.edgeRef()
        val sourceRef = graph.vertexRef()
        val targetRef = graph.vertexRef()

        for (edgeIdx in edgeIndices) {
            val edge = graph.edges().find { it.internalPoolIndex == edgeIdx }
            if (edge != null ) {
                sourceRef.refTo(edge.source)
                targetRef.refTo(edge.target)

                links[edgeIdx]?.let {
                    setLinkTransform(sourceRef, targetRef, it.instance)
                }
            } else {
                logger.warn("Couldn't find edge with index $edgeIdx")
            }
        }
        mainLinkInstance?.updateInstanceBuffers()
        graph.releaseRef(sourceRef)
        graph.releaseRef(targetRef)
        graph.releaseRef(edgeRef)
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
        val tStart = TimeSource.Monotonic.markNow()
        mainSpotInstance?.instances?.forEach { s ->
            adjustSpotInstanceScale(s)
        }
        val tElapsed = TimeSource.Monotonic.markNow() - tStart
        logger.debug("Updating spot scale to $sphereScaleFactor, took $tElapsed")
    }

    fun decreaseSphereInstanceScale() {
        sphereScaleFactor -= 0.1f
        if (sphereScaleFactor < 0.1f) sphereScaleFactor = 0.1f
        updateSphereInstanceScales()
        manvr3d.associatedUI?.updatePaneValues()
    }

    fun increaseSphereInstanceScale() {
        sphereScaleFactor += 0.1f
        updateSphereInstanceScales()
        manvr3d.associatedUI?.updatePaneValues()
    }

    fun increaseLinkScale() {
        val oldScale = linkScaleFactor
        linkScaleFactor += 0.2f
        val factor = linkScaleFactor / oldScale
        mainLinkInstance?.instances?.forEach { l -> l.spatial().scale *= Vector3f(factor, 1f, factor) }
        logger.debug("Increasing scale to $linkScaleFactor, by factor $factor")
    }

    fun decreaseLinkScale() {
        val oldScale = linkScaleFactor
        linkScaleFactor -= 0.2f
        val factor = linkScaleFactor / oldScale
        mainLinkInstance?.instances?.forEach { l -> l.spatial().scale *= Vector3f(factor, 1f, factor) }
        logger.debug("Decreasing scale to $linkScaleFactor, by factor $factor")
    }

    /** Shows or initializes the main links instance, publishes it to the scene and populates it with instances from the current Mastodon graph. */
    fun showInstancedLinks(
        colorMode: ColorMode = currentColorMode,
        colorizer: GraphColorGenerator<Spot, Link> = currentColorizer
    ) {
        enqueueUpdate("showInstancedLinks()") {
            val tStart = TimeSource.Monotonic.markNow()

            links.clear()
            if (mainLinkInstance == null) {
                cylinder.setMaterial(
                    ShaderMaterial.fromFiles("DeferredInstancedColor.vert", "DeferredInstancedColor.frag" )
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
//                inst.addAttribute(Material::class.java, cylinder.material())
                    inst.parent = linkParentNode
                    linkPool.add(inst)
                }

                edge.getSource(from)
                edge.getTarget(to)

                setLinkTransform(from, to, inst)
                inst.instancedProperties["Color"] = { Vector4f(1f, 1f, 1f, 1f) }
                inst.name = "${edge.internalPoolIndex}"
                inst.parent = linkParentNode
                // add a new key-value pair to the hash map
                links[edge.internalPoolIndex] = LinkNode(inst, from, to, to.timepoint)

                index++
            }
            graph.releaseRef(from)
            graph.releaseRef(to)

            // turn all leftover links from the pool invisible
            var i = index
            while (i < linkPool.size) {
                linkPool[i++].visible = false
            }

            linkPreviewList.forEach { link ->

                setLinkTransform(link.from, link.to, link.instance)
            }

            logger.debug("${links.size} links in the hashmap, ${linkPool.size} link instances in the pool. " +
                    "Mastodon provides ${mastodonData.model.graph.edges().size} links.")
            val end = TimeSource.Monotonic.markNow()

            logger.debug("Edge traversel took ${end - start}.")
            // first update the link colors without providing a colorizer, because no BDV window has been opened yet
            updateLinkColors(colorizer)

            mainLink.updateInstanceBuffers()

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
            scale.set(linkSize, posTarget.length().toDouble(), linkSize)
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
     * When set to [ColorMode.SPOT], it uses the [colorizer] to get the spot colors. */
    fun updateLinkColors (
        colorizer: GraphColorGenerator<Spot, Link>? = currentColorizer,
        cm: ColorMode = currentColorMode
    ) {
        val start = TimeSource.Monotonic.markNow()
        when (cm) {
            LUT -> {
                links.forEach {link ->
                    val factor = link.value.tp / numTimePoints.toDouble()
                    val color = lut.lookupARGB(0.0, 1.0, factor).unpackRGB()
                    link.value.instance.instancedProperties["Color"] = { color }
                }
            }
            SPOT -> {
                if (colorizer != null) {
                    links.forEach { (edgeIdx, linkNode) ->
                        // Color based on the target spot
                        linkNode.instance.setColorFromSpot(linkNode.to, colorizer)
                    }
                }
            }
        }
        val end = TimeSource.Monotonic.markNow()
        mainLinkInstance?.updateInstanceBuffers()
        logger.debug("Updating link colors took ${end - start}.")
    }

    fun updateSegmentVisibility(currentTP: Int) {
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

    /** Send a list of vertices from sciview to Mastodon.
     * If the boolean is true, the coordinates are in world space and will be converted to local Mastodon space first.
     * The first passed spot indicates that the user wants to start from an existing spot (aka clicked on it for starting the track).
     * The second spot is used to merge into existing spots. */
    fun addTrackToMastodon(
        list: List<Pair<Vector3f, SpineGraphVertex>>?,
        cursorRadius: Float,
        isWorldSpace: Boolean,
        startWithExisting: Spot?,
        mergeSpot: Spot?
    ) {
        enqueueUpdate("addTrackToMastodon(points=${list?.size ?: trackPointList.size})") {
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

            } else {
                // Otherwise we did controller tracking and the points are inside trackPointList and not in list
                var localRadius: Float
                trackPointList.forEachIndexed { index, (pos, tp, pointRadius) ->
                    // Calculate the equivalent radius in Mastodon from the raw radius from the cursor in sciview scale
                    localRadius = manvr3d.sciviewToMastodonScale().max() * pointRadius
                    // If we reached the last spot in the list and a mergeSpot was passed, use it instead of the spot in the list
                    if (index == trackPointList.size - 1 && mergeSpot != null) {
                        graph.addEdge(mergeSpot, prevVertex)
                    } else {
                        val v: Spot
                        if (index == 0 && startWithExisting != null) {
                            v = startWithExisting
                        } else {
                            v = graph.addVertex()
                            // val localPos = if (isWorldSpace) manvr3d.sciviewToMastodonCoords(pos) else pos
                            v.init(tp, pos.toDoubleArray(), localRadius.toDouble())
                            logger.debug("added {}", v)
                        }
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
            graph.releaseRef(prevVertex)
            manvr3d.bdvNotifier?.lockUpdates = false
            // Once we send the new track to Mastodon, we can assume we no longer need the previews and can clear them
            mainLinkInstance?.instances?.removeAll(linkPreviewList.map { it.instance }.toSet())
            linkPreviewList.clear()
            trackPointList.clear()
            clearSelection()
        }
    }

    /** Lambda that is passed to sciview to send individual spots from sciview to Mastodon
     * or delete them if a spot is already selected, as we use the same VR button for creation and deletion.
     * Takes the timepoint and the sciview position and a flag that determines whether to delete the whole branch.  */
    val addOrRemoveSpots: (tp: Int, sciviewPos: Vector3f, radius: Float, deleteBranch: Boolean, isWorldSpace: Boolean) -> Unit =
        { tp, sciviewPos, radius, deleteBranch, isWorldSpace ->
            enqueueUpdate("addTrackToMastodon(tp=$tp)") {
            manvr3d.bdvNotifier?.lockUpdates = true
            // Check if a spot is selected, and perform deletion if true
            val selected = mastodonData.selectionModel.selectedVertices
            if (!selected.isEmpty()) {
                if (!deleteBranch) {
                    deleteSpots.invoke(selected)
                } else {
                    logger.info("Deleting the whole branch...")
                    val spotList = mutableListOf<Spot>()
                    // Perform a recursive forward and backward search for each selected spot
                    // This deletes all branches connected to the selected spot(s)
                    selected.forEach {
                        spotList.addAll(selectBranch(it))
                    }
                    mastodonData.model.graph.lock.writeLock().lock()
                    spotList.distinct().forEach {
                        mastodonData.model.graph.remove(it)
                    }
                    mastodonData.model.graph.lock.writeLock().unlock()
                    clearSelection()
                }
                mastodonData.model.graph.notifyGraphChanged()
            } else {
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
                        logger.info("Added new spot at position $pos, radius is $localRadius")
                        logger.debug("we now have ${mastodonData.model.graph.vertices().size} spots in total")
                    } else {
                        logger.warn("Not adding new spot, $pos is outside the volume!")
                        manvr3d.flashVolumeGrid()
                    }
                } else {
                    logger.warn("Not adding new spot, volume has no bounding box!")
                }
            }
            manvr3d.bdvNotifier?.lockUpdates = false
        }
    }

    /** Recursively traverse every sub-branch connected to a spot. Returns a list of all spots that are connected
     * to the spot. */
    fun selectBranch(spot: Spot): List<Spot> {
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

    data class LinkPreview( val instance: InstancedNode.Instance, val from: Vector3f, val to: Vector3f , val tp: Int)

    /** This list is populated point by point during the controller tracking. Each point contains the position, timepoint and radius. */
    val trackPointList = mutableListOf<Triple<Vector3f, Int, Float>>()

    val linkPreviewList = mutableListOf<LinkPreview>()

    /** Adds a single link instance to the scene for visual feedback during controller based tracking.
     * No data are sent to Mastodon yet, but we keep track of the points in local space in a [trackPointList]. */
    val addTrackedPoint: (pos: Vector3f, tp: Int, rawRadius: Float, preview: Boolean) -> Unit = { pos, tp, radius, preview ->
        val localPos = manvr3d.sciviewToMastodonCoords(pos)
        // Once we tracked the first point, we can start adding link previews
        if (trackPointList.isNotEmpty() && mainLinkInstance != null) {
            val inst = mainLinkInstance!!.addInstance()
            val color = Vector4f(0.65f, 1f, 0.22f, 1f)
//            val localFrom = manvr3d.sciviewToMastodonCoords(from)
            inst.instancedProperties["Color"] = { color }
            inst.parent = linkParentNode
            inst.visible = preview
            setLinkTransform(trackPointList.last().first, localPos, inst)
            val link = LinkPreview(inst, trackPointList.last().first, localPos, tp)
            linkPreviewList.add(link)
            mainLinkInstance?.updateInstanceBuffers()
            mainSpotInstance?.updateInstanceBuffers()
            logger.debug("Added a new preview link from {} to {}. Visibility is {}", link.from, link.to, preview)
        }
        trackPointList.add(Triple(localPos, tp, radius))
    }

    /** Toggle the preview links that are rendered during controller tracking */
    val toggleLinkPreviews: (state: Boolean) -> Unit = { state ->
        linkPreviewList.forEach {
            it.instance.visible = state
            logger.debug("set instance ${it.instance.name} to $state")
        }
        mainLinkInstance?.updateInstanceBuffers()
    }

    val linkSize = 2.0

    // list of all link segments
    var links: ConcurrentHashMap<Int, LinkNode> = ConcurrentHashMap()

    fun hsvToRGBA(hue: Int, saturation: Int, value: Int): Vector4f {
        val h = hue / 360.0f
        val s = saturation / 100.0f
        val v = value / 100.0f

        val rgbInt = Color.HSBtoRGB(h, s, v)
        return rgbInt.unpackRGB()
    }
}

data class LinkNode (val instance: InstancedNode.Instance, val from: Spot, val to: Spot, val tp: Int)
