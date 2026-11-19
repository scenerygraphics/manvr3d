package graphics.scenery.manvr3d.util

import graphics.scenery.Mesh
import graphics.scenery.primitives.Cylinder
import org.joml.Quaternionf
import org.joml.Vector3f

class DataAxes(
    origin: Vector3f = Vector3f(0f),
    size: Float = 0.1f
): Mesh() {

    init {
        //add the data axes
        val axisWidth = size * 0.1f
        val axisLength = size

        this.spatial().position = origin

        this.name = "Data Axes"

        var c = Cylinder(axisWidth / 2.0f, axisLength, 12)
        c.name = "Data x axis"
        c.material().diffuse = Vector3f(1f, 0f, 0f)
        val halfPI = Math.PI.toFloat() / 2.0f
        c.spatial().rotation = Quaternionf().rotateLocalZ(-halfPI)
        this.addChild(c)

        c = Cylinder(axisWidth / 2.0f, axisLength, 12)
        c.name = "Data y axis"
        c.material().diffuse = Vector3f(0f, 1f, 0f)
        c.spatial().rotation = Quaternionf().rotateLocalZ(Math.PI.toFloat())
        this.addChild(c)

        c = Cylinder(axisWidth / 2.0f, axisLength, 12)
        c.name = "Data z axis"
        c.material().diffuse = Vector3f(0f, 0f, 1f)
        c.spatial().rotation = Quaternionf().rotateLocalX(-halfPI)
        this.addChild(c)
    }
}
