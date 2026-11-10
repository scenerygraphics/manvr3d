package graphics.scenery.manvr3d.vr

import graphics.scenery.manvr3d.vr.CellTrackingBase.PlaybackDirection
import graphics.scenery.utils.lazyLogger
import org.scijava.ui.behaviour.DragBehaviour
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** This behaviour is called every time the user performs a click with controller-based tracking.
 * Also supports drag-based tracking. */
class ControllerTrackBehaviour(val context: CellTrackingBase) : DragBehaviour {

    private val logger by lazyLogger()

    private val cursor = context.cursor
    private val geometryHandler = context.geometryHandler
    private val buttonMapper = context.buttonMapper
    private val volume = context.volume
    private val playDirection = context.playDirection

    var initTime = TimeSource.Monotonic.markNow()
    var lastTrackTime = TimeSource.Monotonic.markNow()
    // Tracks this behavior state only, not the overall controller tracking state
    var isActive = false

    override fun init(p0: Int, p1: Int) {
        initTime = TimeSource.Monotonic.markNow()

        // We don't track things while interacting with a menu
        if (cursor.showPointer) return
        isActive = true

        // First, ensure the tracking flag is active
        if (!context.controllerTrackingActive) {
            context.controllerTrackingActive = true
            context.trackPointList.clear()
            cursor.setColor(context.cursorTrackingColor)
            // Also ensure nothing is selected
            geometryHandler.clearSpotSelection()
            geometryHandler.clearLinkSelection()
            buttonMapper.let {
                it.mapper.updateLabel(it.ADD_DELETE_RESET, "Stop", it.trackingColor)
            }

            // we don't want animation, because we track step by step
            context.playing = false
            // Capture the start time, so we can write statistics on tracking duration
            context.fileLogger.beginControllerTracking()
        }
        trackTimepoint()
    }

    override fun drag(p0: Int, p1: Int) {
        if (!isActive) return
        val timeSinceInit = TimeSource.Monotonic.markNow() - initTime
        val timeSinceTrack = TimeSource.Monotonic.markNow() - lastTrackTime
        if (timeSinceInit > 0.6.seconds && timeSinceTrack > 1.seconds / context.volumesPerSecond.toDouble() ) {
            trackTimepoint()
        }
    }

    override fun end(p0: Int, p1: Int) {
        isActive = false
    }

    fun trackTimepoint() {
        lastTrackTime = TimeSource.Monotonic.markNow()
        val p = cursor.getPosition()
        // did the user click on an existing cell?
        val (selectedSpot, isValidSelection) =
            geometryHandler.selectClosestSpotsVR(p, volume.currentTimepoint,
                cursor.radius * cursor.visualScale, false)

        context.fileLogger.incrementControllerTracking(p, context.hmd.getPosition(), volume.spatial())

        logger.debug("Tracked a new spot at position $p")
        logger.debug("Selected spot is $selectedSpot")
        // Create a placeholder link during tracking for immediate feedback
        geometryHandler.addTrackedPoint(
            p, volume.currentTimepoint, cursor.radius, selectedSpot, context.enableTrackingPreview, context.trackPointList
        )

        // check whether we approach the first or last timepoint, depending on playback direction
        if ((volume.currentTimepoint > 0 && playDirection == PlaybackDirection.Backward) ||
            (volume.currentTimepoint < volume.timepointCount - 1 && playDirection == PlaybackDirection.Forward)) {
            val direction = if (playDirection == PlaybackDirection.Forward) 1 else -1
            volume.goToTimepoint(volume.currentTimepoint + direction)
            // If the user clicked a cell, and it is *not* the first in the track, we assume it is a merge event and end the tracking,
            // but only if the selected spot already has an edge. We continue tracking/linking when the selected spot has no connections
            if (isValidSelection && context.trackPointList.size > 1 && selectedSpot!!.edges().size() > 0) {
                context.endControllerTracking()
                isActive = false
            }
        } else {
            context.sciview.camera?.showMessage(
                "Reached the ${if (playDirection == PlaybackDirection.Forward) "last" else "first"} time point!",
                centered = true, distance = 2f, size = 0.2f
            )
            // Let's head back to the last/first timepoint for starting a new track fast-like
            if (playDirection == PlaybackDirection.Forward) {
                volume.goToFirstTimepoint()
            } else {
                volume.goToLastTimepoint()
            }
            context.endControllerTracking()
            isActive = false
        }

        // This will also redraw all geometry using Mastodon as source
        context.notifyObservers(volume.currentTimepoint)
    }
}