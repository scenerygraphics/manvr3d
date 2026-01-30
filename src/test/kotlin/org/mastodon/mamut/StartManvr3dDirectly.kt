package org.mastodon.mamut

import graphics.scenery.utils.lazyLogger
import mpicbg.spim.data.SpimDataException
import org.mastodon.mamut.io.ProjectLoader
import org.scijava.Context
import sc.iview.SciView
import sc.iview.SciView.Companion.create
import java.io.IOException
import javax.swing.WindowConstants

object StartManvr3dDirectly {
    private val logger by lazyLogger()

    @Throws(IOException::class, SpimDataException::class)
    fun giveMeMastodonOfThisProject(scijavaCtx: Context?, projectPath: String?): ProjectModel {
        //ImageJ ij = new ImageJ();
        //ij.ui().showUI();

        val projectModel = ProjectLoader.open(projectPath, scijavaCtx)

        //this makes the main Mastodon window visible
        val win = MainWindow(projectModel)
        win.isVisible = true
        win.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)

        return projectModel
    }

    @Throws(Exception::class)
    fun createSciview(): SciView {
        return create()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // --------------->>  <<---------------
            //point this to your testing project, or grab example project with:
            //git clone https://github.com/mastodon-sc/mastodon-example-data.git
            val projectPath = "C:/CASUS/datasets/mastodon-example-data/tgmm-mini/tgmm-mini.mastodon"
//            val projectPath = "C:/CASUS/datasets/Fluo_C3DL_MDA231/Fluo-C3DL-MDA231-01.mastodon"
            // --------------->>  <<---------------
            val sv = createSciview()
            val mastodon = giveMeMastodonOfThisProject(sv.scijavaContext, projectPath)
            val manvr3dContext = Manvr3dMain(mastodon, targetSciviewWindow = sv)
            manvr3dContext.createAndShowControllingUI()
            mastodon.projectClosedListeners().add(CloseListener {
                logger.debug("Mastodon project was closed, cleaning up in sciview:")
                manvr3dContext.close() //calls also manvr3dMain.detachControllingUI();
            })
        } catch (e: Exception) {
            throw e
        }
    }
}
