package util

import graphics.scenery.utils.lazyLogger
import org.mastodon.app.ui.GroupLocksPanel
import org.mastodon.grouping.GroupHandle
import org.mastodon.mamut.ProjectModel
import org.mastodon.mamut.Manvr3dMain
import org.mastodon.mamut.model.Link
import org.mastodon.mamut.model.Spot
import org.mastodon.model.NavigationListener
import org.mastodon.model.TimepointListener
import org.mastodon.pool.PoolCollectionWrapper
import org.scijava.AbstractContextual
import org.scijava.event.EventHandler
import org.scijava.event.SciJavaEvent
import sc.iview.event.NodeActivatedEvent

class GroupLocksHandling(//controls sciview via this bridge obj
    private val manvr3d: Manvr3dMain, mastodon: ProjectModel
) {
    val logger by lazyLogger()
    //controls Mastodon
    private val projectModel: ProjectModel = mastodon
    //shortcut to inside of Mastodon
    private val vertices: PoolCollectionWrapper<Spot> = projectModel.model.graph.vertices()
    private val navigationRequestsHandler: NavigationRequestsHandler = NavigationRequestsHandler()
    private val sciviewFocusHandler: SciviewEventListener = SciviewEventListener()
    private lateinit var myGroupHandle: GroupHandle
    private var isActive = false

    fun createAndActivate(): GroupLocksPanel? {
        if (isActive) return null
        isActive = true
        manvr3d.eventService?.subscribe(sciviewFocusHandler)
        myGroupHandle = projectModel.groupManager.createGroupHandle()
        myGroupHandle.getModel(projectModel.NAVIGATION).listeners().add(navigationRequestsHandler)
        myGroupHandle.getModel(projectModel.TIMEPOINT).listeners().add(navigationRequestsHandler)
        return GroupLocksPanel(myGroupHandle)
    }

    fun deactivate() {
        if (!isActive) return
        isActive = false
        myGroupHandle.getModel(projectModel.NAVIGATION).listeners().remove(navigationRequestsHandler)
        myGroupHandle.getModel(projectModel.TIMEPOINT).listeners().remove(navigationRequestsHandler)
        projectModel.groupManager.removeGroupHandle(myGroupHandle)

        //can fail, so we better do it as the last action here
        val subs = manvr3d.eventService?.getSubscribers(
            SciJavaEvent::class.java
        )
        subs?.remove<Any?>(sciviewFocusHandler)
    }

    internal inner class NavigationRequestsHandler : NavigationListener<Spot, Link>, TimepointListener {
        override fun navigateToVertex(vertex: Spot) {
//            if (isActive) focusSciviewToNode(vertex.label)
        }

        override fun navigateToEdge(edge: Link) {
//            if (isActive) focusSciviewToNode(edge.source.label)
        }

        override fun timepointChanged() {
            logger.debug("timepoint changed to ${myGroupHandle.getModel(projectModel.TIMEPOINT).timepoint}")
            manvr3d.showTimepoint(myGroupHandle.getModel(projectModel.TIMEPOINT).timepoint)
        }
    }

    internal inner class SciviewEventListener : AbstractContextual() {
        @EventHandler
        fun onEvent(event: NodeActivatedEvent) {
            if (event.node == null) return
            if (isActive) focusMastodonToSpot(event.node.name)
        }
    }

    fun focusMastodonToSpot(name: String) {
        val res = vertices.stream().filter { s: Spot -> name.matches(s.label.toRegex()) }.findFirst()
        if (res.isPresent) {
            //TODO: this is triggering also our handler (see above navigateToVertex()) !
            myGroupHandle.getModel(projectModel.NAVIGATION).notifyNavigateToVertex(res.get())
        }
    }
}
