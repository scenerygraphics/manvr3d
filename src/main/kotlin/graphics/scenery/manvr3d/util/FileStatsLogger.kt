package graphics.scenery.manvr3d.util

import graphics.scenery.manvr3d.vr.CellTrackingBase
import graphics.scenery.utils.lazyLogger
import org.mastodon.mamut.ProjectModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    private val logPath = "manvr3d_session_${now()}.log"
    private val logFile: File = File(logPath)

    private var sessionStartTime = TimeSource.Monotonic.markNow()
    private var vrSessionStartTime = TimeSource.Monotonic.markNow()
    private var initNumVertices = 0
    private var initNumEdges = 0
    private var numTimepointsPredicted = 0
    private var numUndos = 0

    private var numTracksRecorded = 0
    private var lensOnTime = 0.seconds
    private var trackingStartTime = TimeSource.Monotonic.markNow()
    private var lensStartTime = TimeSource.Monotonic.markNow()

    /** Initialize the manvr3d session recording and create a log file if [isEnabled] is true. */
    fun beginManvr3dSession(mastodon: ProjectModel) {
        if (isEnabled) {
            logFile.createNewFile()
            logger.info("Created session log at $logPath.")
        }
        sessionStartTime = TimeSource.Monotonic.markNow()
        initNumEdges = mastodon.model.graph.edges().size
        initNumVertices = mastodon.model.graph.vertices().size
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
        append("Volume lensing was enabled for: ${lensOnTime.toString(DurationUnit.SECONDS)}" +
                "\nTracks recorded in VR: $numTracksRecorded")
    }

    /** End the manvr3d session and log session length plus stats on undo and prediction counts and
     * how many new vertices and edges were added to the Mastodon file. */
    fun endManvr3dSession(mastodon: ProjectModel) {
        val sessionLength = TimeSource.Monotonic.markNow() - sessionStartTime
        val numNewVertices = mastodon.model.graph.vertices().size - initNumVertices
        val numNewEdges = mastodon.model.graph.edges().size - initNumEdges

        append(
            """
                
                ==== Stopped manvr3d session ====
                Closing the session at: ${now()}.
                Total session length was: ${sessionLength.toString(DurationUnit.SECONDS)}.
                Undo actions performed: $numUndos
                Mastodon file now has ${mastodon.model.graph.vertices().size} vertices and ${mastodon.model.graph.edges().size} edges.
                New vertices added: $numNewVertices
                New edges added:    $numNewEdges
                Number of timepoints predicted with ELEPHANT: $numTimepointsPredicted
            """.trimIndent()
        )
    }

    /** Increments the Mastodon undo counter. */
    fun incrementUndo() {
        numUndos++
    }

    /** Increments the ELEPHANT timepoint prediction counter. */
    fun incrementPrediction() {
        numTimepointsPredicted++
    }

    /** Capture the start time of the controller tracking interaction. */
    fun beginControllerTracking() {
        trackingStartTime = TimeSource.Monotonic.markNow()
    }

    /** End the controller tracking interaction recording and log the duration and track statistics to file if [isEnabled] is true. */
    fun endControllerTracking(trackPointList: MutableList<CellTrackingBase.TrackedPoint>) {
        numTracksRecorded++

        val duration = TimeSource.Monotonic.markNow() - trackingStartTime
        val minTp = trackPointList.minOf { it.tp }
        val maxTp = trackPointList.maxOf { it.tp }
        val speed = trackPointList.size.toDouble() / duration.toDouble(DurationUnit.SECONDS)
        append(
            "Controller track with ${trackPointList.size} spots finished in " +
                    duration.toString(DurationUnit.SECONDS, 2) +
                    ", timepoint range: $minTp - $maxTp, average speed: " +
                    "${if (trackPointList.size > 1) String.format("%.2f", speed) else "-"} tp/s."
        )
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

    fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
}
