package org.mastodon.mamut.util

import graphics.scenery.DefaultNode
import graphics.scenery.Group
import graphics.scenery.Mesh
import graphics.scenery.Node
import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.primitives.Cylinder
import org.joml.Quaternionf
import org.joml.Vector3f

class DataAxes: Mesh() {

    init {
        //add the data axes
        val AXES_LINE_WIDTHS = 0.01f
        val AXES_LINE_LENGTHS = 0.1f

        this.name = "Data Axes"

        var c = Cylinder(AXES_LINE_WIDTHS / 2.0f, AXES_LINE_LENGTHS, 12)
        c.name = "Data x axis"
        c.material().diffuse = Vector3f(1f, 0f, 0f)
        val halfPI = Math.PI.toFloat() / 2.0f
        c.spatial().rotation = Quaternionf().rotateLocalZ(-halfPI)
        this.addChild(c)

        c = Cylinder(AXES_LINE_WIDTHS / 2.0f, AXES_LINE_LENGTHS, 12)
        c.name = "Data y axis"
        c.material().diffuse = Vector3f(0f, 1f, 0f)
        c.spatial().rotation = Quaternionf().rotateLocalZ(Math.PI.toFloat())
        this.addChild(c)

        c = Cylinder(AXES_LINE_WIDTHS / 2.0f, AXES_LINE_LENGTHS, 12)
        c.name = "Data z axis"
        c.material().diffuse = Vector3f(0f, 0f, 1f)
        c.spatial().rotation = Quaternionf().rotateLocalX(-halfPI)
        this.addChild(c)
    }
}