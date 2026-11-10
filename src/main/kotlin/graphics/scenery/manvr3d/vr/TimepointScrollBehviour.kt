package graphics.scenery.manvr3d.vr

import graphics.scenery.manvr3d.vr.CellTrackingBase.PlaybackDirection
import org.scijava.ui.behaviour.DragBehaviour
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TimepointScrollBehavior(val context: CellTrackingBase, val stickDirection: PlaybackDirection) : DragBehaviour {
    var initTime = TimeSource.Monotonic.markNow()

    override fun init(x: Int, y: Int) {
        initTime = TimeSource.Monotonic.markNow()
        scrollTimepoint()
    }

    override fun drag(x: Int, y: Int) {
        if ((TimeSource.Monotonic.markNow() - initTime) > 0.6.seconds) {
            scrollTimepoint()
        }
    }

    override fun end(x: Int, y: Int) {}

    fun scrollTimepoint() {
        if (context.controllerTrackingActive) {
            return
        }
        if (stickDirection == PlaybackDirection.Forward) {
            context.skipToNext = true
        } else {
            context.skipToPrevious = true
        }
    }
}