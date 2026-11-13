package graphics.scenery.manvr3d.behaviours

import graphics.scenery.manvr3d.vr.CellTrackingBase
import org.scijava.ui.behaviour.DragBehaviour
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** This behavior selects/deselects spots and edges on click,
 * and performs stroke-like selection drawing on drag events. */
class DragSelectBehavior(val context: CellTrackingBase): DragBehaviour {
    var lastUpdate = TimeSource.Monotonic.markNow()
    var startTime = TimeSource.Monotonic.markNow()
    var wasDragged = false
    // Checks whether we are interacting with a menu -> if true, we don't perform the behavior
    private var isActive = false

    private val cursor = context.cursor
    private val mapper = context.buttonMapper.mapper

    override fun init(x: Int, y: Int) {
        // Only perform drag actions when not interacting with the menu
        isActive = !cursor.showPointer && !context.controllerTrackingActive
        if (!isActive) return
        lastUpdate = TimeSource.Monotonic.markNow()
        cursor.setColor(context.cursorSelectColor)
        startTime = TimeSource.Monotonic.markNow()
        wasDragged = false
    }
    override fun drag(x: Int, y: Int) {
        if (!isActive) return
        // Only perform the selection method twenty times a second and when the button was pressed for more than 200ms
        if (TimeSource.Monotonic.markNow() - lastUpdate > 50.milliseconds &&
            TimeSource.Monotonic.markNow() - startTime > 200.milliseconds) {
            val p = cursor.getPosition()
            context.geometryHandler.selectClosestSpotsVR(p, context.volume.currentTimepoint, cursor.radius, true, cursor.visualScale)
            context.geometryHandler.selectClosestEdgesVR(p, cursor.radius * cursor.visualScale, true)
            lastUpdate = TimeSource.Monotonic.markNow()
            wasDragged = true
        }
    }
    override fun end(x: Int, y: Int) {
        if (!isActive) return
        // If this wasn't a drag event but a click event, perform the selection now
        val p = cursor.getPosition()
        if (!wasDragged) {
            context.geometryHandler.selectClosestSpotsVR(p, context.volume.currentTimepoint, cursor.radius, false, cursor.visualScale)
            context.geometryHandler.selectClosestEdgesVR(p, cursor.radius * cursor.visualScale, false)
        }
        context.manvr3d.recordVREditEvent("${if (wasDragged) "Drag" else "Click"} selection",
            TimeSource.Monotonic.markNow() - startTime)
        cursor.resetColor()

        if (context.manvr3d.selectedLinkNodes.isNotEmpty() || context.manvr3d.selectedSpotInstances.isNotEmpty()) {
            mapper.updateLabel(
                context.buttonMapper.ADD_DELETE_RESET,
                "Del", context.buttonMapper.selectColor)
        } else {
            mapper.updateLabel(
                context.buttonMapper.ADD_DELETE_RESET,
                "Add", context.buttonMapper.defaultColor)
        }
    }
}