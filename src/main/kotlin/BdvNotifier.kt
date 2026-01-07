package org.mastodon.mamut

import bdv.viewer.TimePointListener
import bdv.viewer.TransformListener
import graphics.scenery.utils.lazyLogger
import net.imglib2.realtransform.AffineTransform3D
import org.mastodon.graph.GraphChangeListener
import org.mastodon.graph.GraphListener
import org.mastodon.mamut.model.Link
import org.mastodon.mamut.model.Spot
import org.mastodon.mamut.views.bdv.MamutViewBdv
import org.mastodon.model.FocusListener
import org.mastodon.spatial.VertexPositionListener
import org.mastodon.ui.coloring.ColoringModel.ColoringChangedListener
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

/**
 * Event handler loop that listens to possible updates from view changes or timepoint, vertex or graph events.
 * A shortcut reference (to the underlying Mastodon data that the BDV window
 * operates over) must be provided.
 *
 * @param updateTimepointProcessor  handler of the scene rebuilding event
 * @param updateViewProcessor  handler of the scene viewing-angle event
 * @param updateVertexProcessor handler of individual spot updates
 * @param updateGraphProcessor handler that rebuilds the whole graph
 * @param mastodon  the underlying Mastodon data
 * @param bdvWindow  BDV window that operates on the underlying Mastodon data
 */
class BdvNotifier(
    updateTimepointProcessor: Runnable,
    updateViewProcessor: Runnable,
    updateVertexProcessor: (Spot?) -> Unit,
    updateGraphProcessor: Runnable,
    mastodon: ProjectModel,
    bdvWindow: MamutViewBdv,
    // Don't trigger updates while a vertex is being moved from the sciview side
    var lockUpdates: Boolean = false
) {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))
    var movedSpot: Spot? = null

    init {
        //create a listener for it (which will _immediately_ collect updates from BDV)
        val bdvUpdateListener = BdvEventsWatcher(bdvWindow, mastodon)

        //create a thread that would be watching over the listener and would take only
        //the most recent data if no updates came from BDV for a little while
        //(this is _delayed_ handling of the data, skipping over any intermediate changes)
        val cumulatingEventsHandlerThread = BdvEventsCatcherThread(
            bdvUpdateListener, 10,
            updateTimepointProcessor, updateViewProcessor, updateVertexProcessor, updateGraphProcessor
        )

        //register the BDV listener and start the thread
        bdvWindow.viewerPanelMamut.renderTransformListeners().add(bdvUpdateListener)
        bdvWindow.viewerPanelMamut.timePointListeners().add(bdvUpdateListener)
        bdvWindow.viewerPanelMamut.addPropertyChangeListener(bdvUpdateListener)
        bdvWindow.coloringModel.listeners().add(bdvUpdateListener)
        mastodon.focusModel.listeners().add(bdvUpdateListener)
        mastodon.model.graph.addVertexPositionListener(bdvUpdateListener)
        mastodon.model.graph.addGraphChangeListener(bdvUpdateListener)
        cumulatingEventsHandlerThread.start()
        bdvWindow.onClose {
            logger.debug("Cleaning up while BDV window is closing.")
            bdvWindow.viewerPanelMamut.renderTransformListeners().remove(bdvUpdateListener)
            bdvWindow.viewerPanelMamut.timePointListeners().remove(bdvUpdateListener)
            bdvWindow.viewerPanelMamut.removePropertyChangeListener(bdvUpdateListener)
            bdvWindow.coloringModel.listeners().remove(bdvUpdateListener)
            mastodon.focusModel.listeners().remove(bdvUpdateListener)
            mastodon.model.graph.removeGraphChangeListener(bdvUpdateListener)
            mastodon.model.graph.removeVertexPositionListener(bdvUpdateListener)
            cumulatingEventsHandlerThread.stopTheWatching()
        }
    }

    /**
     * This class only registers timestamp of the most recently occurred relevant BDV/Mastodon event, it recognized
     * two types of events: events requiring scene camera repositioning, and events requiring scene content rebuild. */
    internal inner class BdvEventsWatcher(val thisBDV: MamutViewBdv, val mastodon: ProjectModel) :
        TransformListener<AffineTransform3D?>,
        TimePointListener, GraphChangeListener, VertexPositionListener<Spot>, PropertyChangeListener, FocusListener,
        ColoringChangedListener, GraphListener<Spot, Link> {
        override fun graphChanged() {
            logger.debug("Called graphChanged")
            timeStampOfLastEvent = System.currentTimeMillis()
            isLastGraphEventValid = true
            mastodon.model.setUndoPoint()
        }
        override fun vertexPositionChanged(vertex: Spot) {
            logger.debug("called vertexChanged")
            vertexChanged(vertex)
        }

        override fun transformChanged(affineTransform3D: AffineTransform3D?) {
            logger.debug("called transformChanged")
            viewChanged()
        }

        override fun timePointChanged(timePointIndex: Int) {
            logger.debug("called timePointChanged")
            contentChanged()
        }

        override fun focusChanged() {
            logger.debug("called focusChanged")
            contentChanged()
        }

        override fun propertyChange(propertyChangeEvent: PropertyChangeEvent) {
            logger.debug("called propertyChange")
            contentChanged()
        }

        override fun coloringChanged() {
            logger.debug("called coloringChanged")
            contentChanged()
        }

        fun contentChanged() {
            timeStampOfLastEvent = System.currentTimeMillis()
            isLastContentEventValid = true
            mastodon.model.setUndoPoint()
        }

        fun viewChanged() {
            timeStampOfLastEvent = System.currentTimeMillis()
            isLastViewEventValid = true
        }

        fun vertexChanged(vertex: Spot) {
            timeStampOfLastEvent = System.currentTimeMillis()
            isLastVertexEventValid = true
            movedSpot = vertex
        }

        override fun graphRebuilt() {
            // TODO("Not yet implemented")
        }

        override fun edgeRemoved(e: Link?) {
            // TODO("Not yet implemented")
        }

        override fun edgeAdded(e: Link?) {
            // TODO("Not yet implemented")
        }

        override fun vertexRemoved(v: Spot?) {
            // TODO("Not yet implemented")
        }

        override fun vertexAdded(v: Spot?) {
            // TODO("Not yet implemented")
        }


        var isLastContentEventValid = false
        var isLastVertexEventValid = false
        var isLastViewEventValid = false
        var isLastGraphEventValid = false
        var timeStampOfLastEvent: Long = 0
    }

    companion object {
        private val SERVICE_NAME = "Mastodon BDV events watcher"
    }



    /**
     * this class iteratively inspects (via a busy-wait loop cycle in its own
     * separate thread) the associated BdvEventsWatcher if there is a recent
     * (and not out-dated) event(s) pending, and if so, it calls the respective
     * eventHandler(s)
     */
    internal inner class BdvEventsCatcherThread(
        val eventsSource: BdvEventsWatcher,
        val updateInterval: Long,
        val timepointProcessor: Runnable,
        val viewEventProcessor: Runnable,
        val vertexEventProcessor: (Spot?) -> Unit,
        val graphEventProcessor: Runnable
    ) : Thread(SERVICE_NAME) {
        var keepWatching = true
        fun stopTheWatching() {
            keepWatching = false
        }

        override fun run() {
            logger.debug("$SERVICE_NAME started")
            try {
                while (keepWatching) {
                    if ((eventsSource.isLastContentEventValid || eventsSource.isLastVertexEventValid || eventsSource.isLastGraphEventValid
                        || eventsSource.isLastViewEventValid &&
                        System.currentTimeMillis() - eventsSource.timeStampOfLastEvent > updateInterval)
                        && !lockUpdates
                    ) {
                        if (eventsSource.isLastContentEventValid) {
                            logger.debug("$SERVICE_NAME: content event and silence detected -> processing it now")
                            eventsSource.isLastContentEventValid = false
                            timepointProcessor.run()
                        }
                        if (eventsSource.isLastViewEventValid) {
                            logger.debug("$SERVICE_NAME: view event and silence detected -> processing it now")
                            eventsSource.isLastViewEventValid = false
                            viewEventProcessor.run()
                        }
                        if (eventsSource.isLastVertexEventValid) {
                            logger.debug("$SERVICE_NAME: vertex event and silence detected -> processing it now")
                            eventsSource.isLastVertexEventValid = false
                            vertexEventProcessor.invoke(movedSpot)
                        }
                        if (eventsSource.isLastGraphEventValid) {
                            logger.debug("$SERVICE_NAME: graph event and silence detected -> processing it now")
                            eventsSource.isLastGraphEventValid = false
                            graphEventProcessor.run()
                        }
                    } else sleep(updateInterval / 10)
                }
            } catch (e: InterruptedException) {
                throw e
            }
            logger.debug("$SERVICE_NAME stopped")
        }
    }
}
