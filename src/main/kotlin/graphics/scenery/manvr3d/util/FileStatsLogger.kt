package graphics.scenery.manvr3d.util

import graphics.scenery.attribute.spatial.Spatial
import graphics.scenery.manvr3d.vr.CellTrackingBase
import graphics.scenery.utils.lazyLogger
import graphics.scenery.utils.stdDev
import org.joml.Vector3f
import org.mastodon.mamut.ProjectModel
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.createDirectory
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource


/**
 * A file-based statistics logger for manvr3d sessions. Records main session and VR session times,
 * timings for controller tracks, and time spent with the lensing tool enabled.
 * It also tracks the number of tracks recorded with controllers, the number of undo actions and ELEPHANT timepoint predictions.
 * Only writes to file if [isEnabled] is true.
 * */
class FileStatsLogger(val isEnabled: Boolean) {
    private val logger by lazyLogger()
    private val sessionStartString = now()
    private lateinit var logDir: File
    private val logPath = "${sessionStartString}.log"
    private lateinit var logFile: File

    private var sessionStartTime = TimeSource.Monotonic.markNow()
    private var vrSessionStartTime = TimeSource.Monotonic.markNow()
    private var initNumVertices = 0
    private var initNumEdges = 0
    private var numTimepointsPredicted = 0
    private var numUndos = 0
    private var numSpotAddEvents = 0
    private var numSpotDeleteEvents = 0
    private var numEdgeDeleteEvents = 0
    private var numBranchDeleteEvents = 0
    private var numMoveEvents = 0
    private var numMergeEvents = 0

    private var numTracksRecorded = 0
    private var lensOnTime = 0.seconds
    private var trackingStartTime = TimeSource.Monotonic.markNow()
    private var lastTrackIntervalTime = TimeSource.Monotonic.markNow()
    private var lensStartTime = TimeSource.Monotonic.markNow()

    /** Data class storing different metadata about volume, HMD, cursor and tracking interval time. */
    data class TrackStatistics(
        val eventName: String,
        val eventTime: LocalDateTime,
        val volumeSize: Float,
        val volumeDistance: Float,
        val volumePosition: Vector3f,
        val hmdPosition: Vector3f,
        val cursorPosition: Vector3f,
        val cursorDistance: Float,
        val duration: Duration
    )

    /** Tracking metadata stored during a controller tracking action. */
    private val trackStats = mutableListOf<TrackStatistics>()
    /**  */
    private val globalEditStats = mutableListOf<TrackStatistics>()

    /** Initialize the manvr3d session recording and create a log file if [isEnabled] is true. */
    fun beginManvr3dSession(mastodon: ProjectModel) {
        if (isEnabled) {
            logDir = File("manvr3d_logs", "manvr3d_session_$sessionStartString")
            logDir.mkdirs()
            logFile = File(logDir, logPath)
            logFile.createNewFile()
            logger.info("Created session log at $logPath.")
        }
        sessionStartTime = TimeSource.Monotonic.markNow()
        initNumEdges = mastodon.model.graph.edges().size
        initNumVertices = mastodon.model.graph.vertices().size
        globalEditStats.clear()
        append(
            """
            MANVR3D SESSION LOG
            Start: ${now()}
            
            ==== Opened Mastodon file ${mastodon.projectName} ====
            The opened Mastodon file has $initNumVertices vertices and $initNumEdges edges.
            """.trimIndent()
        )
    }

    /** Append text to the log file if [isEnabled] is true. */
    fun append(text: String) {
        if (isEnabled) {
            logFile.appendText(text + "\n")
        }
    }

    /** Capture start time of the VR session. */
    fun beginVrSession() {
        vrSessionStartTime = TimeSource.Monotonic.markNow()
        append("\n==== Started VR session ====")
    }

    /** Stop the VR session recording and log session duration, the time the lens tool was enabled, and the number
     * of tracks created with controllers. */
    fun endVrSession() {
        val duration = TimeSource.Monotonic.markNow() - vrSessionStartTime
        append("\n==== Stopped VR session ====")
        append("Stopped VR session. VR was active for ${duration.toString(DurationUnit.SECONDS)}.")
        append("Tracks recorded in VR: $numTracksRecorded")
        append("""
            Number of spots added manually: $numSpotAddEvents
            Number of spot deletion events: $numSpotDeleteEvents
            Number of edge deletion events: $numEdgeDeleteEvents
            Number of branch deletion events: $numBranchDeleteEvents
            Number of spot move events: $numMoveEvents
            Number of spot merge events: $numMergeEvents
        """.trimIndent())
    }

    /** End the manvr3d session and log session length plus stats on undo and prediction counts and
     * how many new vertices and edges were added to the Mastodon file. */
    fun endManvr3dSession(mastodon: ProjectModel, predictionDurations: List<Duration>, predictionCounts: List<Int>) {
        if (!isEnabled) return
        val sessionLength = TimeSource.Monotonic.markNow() - sessionStartTime
        val numNewVertices = mastodon.model.graph.vertices().size - initNumVertices
        val numNewEdges = mastodon.model.graph.edges().size - initNumEdges

        // Statistics for ELEPHANT predictions
        val durationsDouble = predictionDurations.map { it.toDouble(DurationUnit.SECONDS) }
        val durationAvg = durationsDouble.average()
        val durationStd = sqrt(durationsDouble.map { (it - durationAvg).pow(2) }.average())

        val countAvg = predictionCounts.average()
        val countStd = sqrt(predictionCounts.map { (it - countAvg).pow(2) }.average())

        val editStatsPath = if (globalEditStats.isNotEmpty()) {
             writeStatsToCSV(globalEditStats, "${sessionStartString}_edit_events.csv")
        } else ""

        val elephantBlock = if (numTimepointsPredicted > 0) {
            """
                
            ==== ELEPHANT ====
            Number of timepoints predicted: $numTimepointsPredicted
            Average (+std.) prediction time: ${String.format("%.2f", durationAvg)} ± ${String.format("%.2f", durationStd)} s/tp
            Average (+std.) spot count: ${String.format("%.2f", countAvg)} ± ${String.format("%.2f", countStd)} spots/tp
            """.trimIndent()
        } else ""

        val editBlock = if (globalEditStats.isNotEmpty()) {
            "\nEdit event log written to $editStatsPath."
        } else ""

        append(
            """
                
            ==== Stopped manvr3d session ====
            Closing the session at: ${now()}.
            Total session length was: ${sessionLength.toString(DurationUnit.SECONDS)}.
            Undo actions performed: $numUndos
            Mastodon file now has ${mastodon.model.graph.vertices().size} vertices and ${mastodon.model.graph.edges().size} edges.
            New vertices added: $numNewVertices
            New edges added:    $numNewEdges
            """.trimIndent()
        )
        append(editBlock)
        append(elephantBlock)
    }

    /** Increments the Mastodon undo counter. */
    fun incrementUndo() {
        numUndos++
    }

    /** Increments the ELEPHANT timepoint prediction counter. */
    fun incrementPrediction() {
        numTimepointsPredicted++
    }

    /** Increments the counter of how many spots were added manually. */
    fun incrementSpotAdded() {
        numSpotAddEvents++
    }

    /** Increments the counter of how many spot deletion events were triggered.
     * Deleting multiple spots at once counts as one event. */
    fun incrementSpotDeleted() {
        numSpotDeleteEvents++
    }

    /** Increments the counter of how many edge deletion events were triggered. */
    fun incrementEdgeDeleted() {
        numEdgeDeleteEvents++
    }

    /** Increments the counter of how many branch deletion events were triggered. */
    fun incrementBranchDeleted() {
        numBranchDeleteEvents++
    }

    /** Increments the counter of how many times a spot move event was triggered. */
    fun incrementSpotMoved() {
        numMoveEvents++
    }

    /** Increments the counter of how many times a merge event was triggered. */
    fun incrementMerge() {
        numMergeEvents++
    }

    /** Append a statistics entry to a given list. Computes the distance between volume and HMD and stores it. */
    private fun appendStat(
        list: MutableList<TrackStatistics>,
        eventName: String,
        cursorPosition: Vector3f,
        hmdPosition: Vector3f,
        volumeSpatial: Spatial,
        intervalTime: Duration
    ) {
        val volumeSizeFloat = volumeSpatial.scale.x
        val volumeDistance = hmdPosition.distance(volumeSpatial.position)
        val cursorDistance = hmdPosition.distance(cursorPosition)
        list.add(TrackStatistics(
                eventName, LocalDateTime.now(), volumeSizeFloat, volumeDistance,
            volumeSpatial.position, hmdPosition, cursorPosition, cursorDistance, intervalTime
        ))
    }

    /** Capture the start time of the controller tracking interaction. */
    fun beginControllerTracking() {
        trackingStartTime = TimeSource.Monotonic.markNow()
        lastTrackIntervalTime = TimeSource.Monotonic.markNow()
        trackStats.clear()
    }

    /** Capture intermediate states during controller tracking. */
    fun incrementControllerTracking(cursorPosition: Vector3f, hmdPosition: Vector3f, volumeSpatial: Spatial) {
        val now = TimeSource.Monotonic.markNow()
        val elapsed = now - lastTrackIntervalTime
        lastTrackIntervalTime = now
        appendStat(trackStats, "Track spot added", cursorPosition, hmdPosition, volumeSpatial, elapsed)
        append("Tracked cell at $cursorPosition in ${elapsed.toString(DurationUnit.SECONDS, 3)}.")
    }

    /** End the controller tracking interaction recording and log the duration and track statistics to file if [isEnabled] is true. */
    fun endControllerTracking(trackPointList: MutableList<CellTrackingBase.TrackedPoint>) {
        numTracksRecorded++

        val trackDuration = TimeSource.Monotonic.markNow() - trackingStartTime
        val minTp = trackPointList.minOf { it.tp }
        val maxTp = trackPointList.maxOf { it.tp }
        // Remove implausible durations from the speed calculation,
        // as is the case with the first annotated cell in the track
        val speeds = trackStats.filter { it.duration > 2.milliseconds }
            .map { 1f / it.duration.toDouble(DurationUnit.SECONDS).toFloat() }
        val avgSpeed = speeds.average()
        val stdSpeed = speeds.stdDev()
        append(
            "Controller track with ${trackPointList.size} spots finished in " +
                    trackDuration.toString(DurationUnit.SECONDS, 2) +
                    ", timepoint range: $minTp - $maxTp, average speed: " +
                    (if (trackPointList.size > 1) String.format("%.2f", avgSpeed) + " ± "  else "-") +
                    "${if (trackPointList.size > 1) String.format("%.2f", stdSpeed) else ""} tp/s."
        )

        if (trackPointList.size > 1 && isEnabled) {
            val trackPath = writeStatsToCSV(trackStats, "${sessionStartString}_track_$numTracksRecorded.csv")
            append("Dumped track file to $trackPath.")
        }
    }

    /** Write the contents of a statistics list to CSV, such as [trackStats] or [globalEditStats]. */
    private fun writeStatsToCSV(stats: MutableList<TrackStatistics>, fileName: String): String {
        logger.debug("Writing CSV stats for $fileName ...")
        val file = File(logDir, fileName)
        file.bufferedWriter().use { out ->
            out.write("EventName;EventTime;VolumeSize;VolumeDistance;VolumePosition;HMDPosition;CursorPosition;CursorDistance;Duration\n")
            stats.forEach {
                out.write("${it.eventName};${dateTimeToString(it.eventTime)};${it.volumeSize};" +
                        "${it.volumeDistance};${it.volumePosition};${it.hmdPosition};${it.cursorPosition};" +
                        "${it.cursorDistance};${it.duration.toDouble(DurationUnit.SECONDS)}\n")
            }
        }
        logger.info("Wrote stats file at $logDir/${fileName}.csv.")
        return file.path
    }

    /** Record a new edit event and store it in [globalEditStats] as [TrackStatistics] entry.
     *
     * Uses [TrackStatistics.duration]=0s. */
    fun recordEvent(
        eventName: String,
        cursorPosition: Vector3f,
        hmdPosition: Vector3f,
        volumeSpatial: Spatial,
        duration: Duration = 0.seconds
    ) {
        appendStat(globalEditStats, eventName, cursorPosition, hmdPosition, volumeSpatial, duration)
    }

    /** Capture the time the lensing tool was enabled. */
    fun beginLensing() {
        lensStartTime = TimeSource.Monotonic.markNow()
    }

    /** Capture the end of the lensing tool interaction and add it to the cumulative duration. */
    fun endLensing() {
        lensOnTime += TimeSource.Monotonic.markNow() - lensStartTime
        append("Lensing was enabled for ${lensOnTime.toString(DurationUnit.SECONDS)}.")
    }

    private fun dateTimeToString(dt: LocalDateTime): String {
        return dt.format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        )
    }

    private fun now(): String = dateTimeToString(LocalDateTime.now())
}
