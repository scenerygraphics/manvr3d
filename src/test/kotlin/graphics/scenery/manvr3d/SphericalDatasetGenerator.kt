package graphics.scenery.manvr3d

import net.imagej.ImageJ
import net.imglib2.Cursor
import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.img.imageplus.ImagePlusImg
import net.imglib2.img.imageplus.ImagePlusImgFactory
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import kotlin.math.pow
import kotlin.math.sqrt

/** This class creates a synthetic dataset of a sphere with linear falloff
 *
 * */
class SphericalDatasetGenerator {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // Open an ImageJ window
            val ij = ImageJ()

            // Run the example
            SphericalDatasetGenerator().run(ij)

            // Display the ImageJ window
            ij.ui().showUI()
        }
    }

    fun run(ij: ImageJ) {
        // Define dimensions
        val width = 100L
        val height = 100L
        val depth = 100L
        val frames = 50L

        // Create 4D image (3D + time) using ImagePlusImgFactory
        val img: ImagePlusImg<FloatType, *> = ImagePlusImgFactory(FloatType()).create(width, height, depth, frames)

        // Define sphere parameters
        val radius = 20.0
        val centerX = width / 2.0
        val centerY = height / 2.0
        val centerZ = depth / 2.0
        val moveSpeed = 1.0 // Speed of movement per frame

        // Generate the dataset
        for (t in 0 until frames) {
            val offsetX = t * moveSpeed - 20
            val frame: RandomAccessibleInterval<FloatType> = Views.hyperSlice(img, 3, t)

            val cursor: Cursor<FloatType> = Views.iterable(frame).cursor()

            while (cursor.hasNext()) {
                cursor.fwd()
                val x = cursor.getDoublePosition(0)
                val y = cursor.getDoublePosition(1)
                val z = cursor.getDoublePosition(2)

                val distance = sqrt(
                    (x - centerX - offsetX).pow(2) +
                            (y - centerY).pow(2) +
                            (z - centerZ).pow(2)
                )

                val intensity = when {
                    distance <= radius -> (radius - distance) / radius
                    else -> 0.0
                }

                cursor.get().setReal(intensity.toFloat())
            }
        }

        val imp = ImageJFunctions.wrap(img, "3D+time Spherical Dataset")
        imp.setDimensions(1, depth.toInt(), frames.toInt())
        imp.show()

        println("3D+time dataset created with dimensions: ${Intervals.dimensionsAsLongArray(img).contentToString()}")
    }
}