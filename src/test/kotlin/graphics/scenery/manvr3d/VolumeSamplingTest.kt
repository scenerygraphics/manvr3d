package graphics.scenery.manvr3d

import bvv.core.VolumeViewerOptions
import graphics.scenery.Camera
import graphics.scenery.DetachedHeadCamera
import graphics.scenery.Icosphere
import graphics.scenery.Light
import graphics.scenery.PointLight
import graphics.scenery.SceneryBase
import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.primitives.Cylinder
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.extensions.minus
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.joml.Vector3f
import java.nio.file.Paths
import java.text.DecimalFormat
import kotlin.concurrent.thread

class VolumeSamplingTest : SceneryBase("VolumeSamplingTest") {

    lateinit var volume: Volume

    override fun init() {
        renderer = hub.add(
            SceneryElement.Renderer,
            Renderer.Companion.createRenderer(hub, applicationName, scene, windowWidth, windowHeight)
        )

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.0f, 5.0f)
            }
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        Light.Companion.createLightTetrahedron<PointLight>(spread = 4.0f, radius = 15.0f, intensity = 3f)
            .forEach { scene.addChild(it) }

        volume = Volume.Companion.fromXML(
            Paths.get(javaClass.getResource("sphere_dataset.xml")?.toURI()!!).toString(),
            hub,
            VolumeViewerOptions()
        )
        volume.spatial().scale = Vector3f(50.0f)
        volume.transferFunction = TransferFunction.Companion.ramp(0.001f, .9f, 0.3f)
        volume.goToTimepoint(20)
        scene.addChild(volume)

        logger.info("sampling the center: ${volume.sample(Vector3f(0.5f, 0.5f, 0.5f))}")
        val p1 = Icosphere(0.1f, 2)
        p1.spatial().position = Vector3f(0.2f, -.5f, -5f)
        p1.material().diffuse = Vector3f(0.3f, 0.3f, 1f)
        scene.addChild(p1)

        val p2 = Icosphere(0.1f, 2)
        p2.spatial().position = Vector3f(-.3f, 0.5f, 5f)
        p2.material().diffuse = Vector3f(0.3f, 1f, 0.3f)
        scene.addChild(p2)

        val connector = Cylinder.Companion.betweenPoints(p1.spatial().position, p2.spatial().position)
        connector.material().diffuse = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(connector)

        thread {
            while (!scene.initialized) {
                Thread.sleep(200)
            }

            val intersection = volume.spatial()
                .intersectAABB(p1.spatial().position, (p2.spatial().position - p1.spatial().position).normalize())
            if (intersection is MaybeIntersects.Intersection) {

                val scale = volume.localScale()
                val localEntry = (intersection.relativeEntry)
                val localExit = (intersection.relativeExit)
                val nf = DecimalFormat("0.0000")
                logger.info("local entry: $localEntry, local exit: $localExit")
                val (samples, samplePos) = volume.sampleRayGridTraversal(localEntry, localExit) ?: (null to null)
                logger.info("samples are $samples")
                logger.info("samplePos are $samplePos")
            }
        }

    }

    override fun inputSetup() {
        setupCameraModeSwitching()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VolumeSamplingTest().main()
        }
    }

}