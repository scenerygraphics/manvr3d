package graphics.scenery.manvr3d.util

import graphics.scenery.utils.lazyLogger
import org.mastodon.graph.GraphChangeListener
import org.mastodon.graph.GraphListener
import org.mastodon.mamut.ProjectModel
import org.mastodon.mamut.model.Link
import org.mastodon.mamut.model.Spot
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.text.set
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Listen to graph changes during the prediction and move the timepoint along with the currently predicted timepoint.
 * Captures prediction timings, stores them in [predictionDurations] and logs them to [fileLogger]. */
class ElephantListener(
    val mastodon: ProjectModel,
    val fileLogger: FileStatsLogger,
    val numTimepoints: Int,
    val goToTimepoint: (Int) -> Unit
) : GraphListener<Spot, Link>, GraphChangeListener {

    val logger by lazyLogger()
    /** Whether the listener actively listens. */
    var isActive = AtomicBoolean(false)
    /** Whether a prediction event predicts all timepoints or only a single one. */
    var predictAll = false
    /** Time the last prediction event was launched. Used for logging and statistics. */
    private var eventLaunchTime = TimeSource.Monotonic.markNow()
    /** The current timepoint being predicted. */
    private var predictedTimepoint = 0
    /** The number of spots predicted during an event. */
    private var predictedSpotCount = 0
    /** Whether the listener is currently attached to the Mastodon graph. */
    var isAttached = false
        private set
    /** Whether the listener reacts to vertex updates in the Mastodon graph. This is usually true for the first
     * predicted spot to capture its timepoint, and is then disabled for subsequent spot additions during a timepoint prediction event. */
    var listenForSpots = false
        private set
    /** List of all prediction durations  during a session. Used for aggregate statistics.*/
    val predictionDurations = mutableListOf<Duration>()
    /** List of spot amounts predicted per event during a session. Used for aggregate statistics. */
    val predictedSpotCounts = mutableListOf<Int>()

    /** Attaches the ElephantListener as a graph listener and graph change listener to the Mastodon graph. */
    fun attach() {
        if (!isAttached) {
            mastodon.model.graph.addGraphChangeListener(this@ElephantListener)
            mastodon.model.graph.addGraphListener(this@ElephantListener)
            isAttached = true
        }
    }

    /** Detaches the ElephantListener from the Mastodon graph. */
    fun detach() {
        mastodon.model.graph.removeGraphChangeListener(this@ElephantListener)
        mastodon.model.graph.removeGraphListener(this@ElephantListener)
        isAttached = false
    }

    override fun graphChanged() {
        // Simple debounce to prevent this callback  from triggering several times
        if (isActive.get() && (TimeSource.Monotonic.markNow() - eventLaunchTime) > 0.02.seconds) {
            fileLogger.incrementPrediction()

            val predictionDuration = TimeSource.Monotonic.markNow() - eventLaunchTime
            predictionDurations.add(predictionDuration)
            val logString = "ELEPHANT predicted $predictedSpotCount spots in timepoint $predictedTimepoint in " +
                    "${String.format("%.2f", predictionDuration.toDouble(DurationUnit.SECONDS))} s."
            fileLogger.append(logString)
            predictedSpotCounts.add(predictedSpotCount)
            logger.debug(logString)

            if (predictAll) {
                // Reset launch time for next prediction round
                eventLaunchTime = TimeSource.Monotonic.markNow()
                goToTimepoint(predictedTimepoint)
                // PredictAll sweeps through all timepoints, so we can disable the listener once we reach the end
                if (predictedTimepoint == numTimepoints - 1) {
                    isActive.set(false)
                } else {
                    // graphChanged is triggered once per TP, but vertexAdded is triggered with every vertex.
                    // If we keep predicting TPs, we want to keep listening to timepoint changes too.
                    listenForSpots = true
                }
            } else {
                // If only a single TP was predicted, we can deactivate the listener right away.
                isActive.set(false)
            }
        }
    }

    /** Indicate that a prediction event was just launched. Updates the [eventLaunchTime],
     * sets the listener state ([isActive]) to true and starts listening for incoming vertices
     * to capture the currently predicted timepoint. */
    fun eventLaunched() {
        eventLaunchTime = TimeSource.Monotonic.markNow()
        isActive.set(true)
        listenForSpots = true
        predictedSpotCount = 0
    }

    override fun vertexAdded(vertex: Spot?) {
        if (isActive.get()) {
            // We can stop listening once we updated the predicted timepoint
            if (listenForSpots) {
                vertex?.let {
                    predictedTimepoint = vertex.timepoint
                }
                listenForSpots = false
            }
            predictedSpotCount++
        }
    }

    override fun graphRebuilt() {}
    override fun vertexRemoved(vertex: Spot?) {}
    override fun edgeAdded(edge: Link?) {}
    override fun edgeRemoved(edge: Link?) {}
}