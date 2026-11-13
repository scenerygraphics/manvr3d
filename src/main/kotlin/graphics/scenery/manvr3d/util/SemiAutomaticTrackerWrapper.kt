package graphics.scenery.manvr3d.util

import graphics.scenery.manvr3d.Manvr3dMain
import graphics.scenery.utils.lazyLogger
import net.imagej.ops.OpService
import net.imagej.ops.special.computer.Computers
import org.mastodon.mamut.ProjectModel
import org.mastodon.tracking.mamut.trackmate.semiauto.SemiAutomaticTracker
import org.mastodon.tracking.mamut.trackmate.semiauto.SemiAutomaticTrackerKeys
import org.scijava.log.LogLevel
import org.scijava.log.LogService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SemiAutomaticTrackerWrapper(
    val manvr3d: Manvr3dMain,
    val mastodon: ProjectModel,
    numTimepoints: Int = 10
) {

    val logger by lazyLogger()

    var numTimepoints: Int = numTimepoints
        set(value) {
            field = value
            settings.put(SemiAutomaticTrackerKeys.KEY_N_TIMEPOINTS, numTimepoints)
        }

    private val settings: MutableMap<String, Any>

    private var tracker: SemiAutomaticTracker

    init {

        val opsService = mastodon.context.getService(OpService::class.java)
            ?: throw IllegalStateException("No OpService")

        val trackerLog = mastodon.context.getService(LogService::class.java)
            .subLogger("SemiAutomaticTracker", LogLevel.WARN)

        settings = SemiAutomaticTrackerKeys.getDefaultDetectorSettingsMap().apply {
            put(SemiAutomaticTrackerKeys.KEY_N_TIMEPOINTS, numTimepoints)
            put(SemiAutomaticTrackerKeys.KEY_FORWARD_IN_TIME, true)
        }

        val groupHandle = mastodon.groupManager.createGroupHandle()
        val navigation = groupHandle.getModel(mastodon.NAVIGATION)

        val selected = mastodon.selectionModel.selectedVertices

        tracker = Computers.binary(
            opsService,
            SemiAutomaticTracker::class.java,
            mastodon.model,
            selected,
            settings,
            mastodon.sharedBdvData,
            navigation,
            mastodon.selectionModel,
            mastodon.focusModel,
            trackerLog
        ) as SemiAutomaticTracker
    }

    fun track(forward: Boolean = true): Duration {
        logger.debug("Tracking ${if (forward) "forward" else "backward"}")
        val fwdKey = SemiAutomaticTrackerKeys.KEY_FORWARD_IN_TIME
        if (settings[fwdKey] != forward) {
            settings[fwdKey] = forward
        }
        val selected = mastodon.selectionModel.selectedVertices
        if (selected.isEmpty()) { logger.warn("No spots selected"); return 0.seconds }

        manvr3d.bdvNotifier?.lockUpdates = true
        val start = TimeSource.Monotonic.markNow()
        tracker.compute(selected, settings, mastodon.model)
        manvr3d.bdvNotifier?.lockUpdates = false
        return TimeSource.Monotonic.markNow() - start
    }

}