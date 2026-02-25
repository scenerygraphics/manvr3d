package org.mastodon.mamut.plugins

import graphics.scenery.SceneryBase
import graphics.scenery.utils.lazyLogger
import net.imagej.ImageJ
import org.mastodon.mamut.launcher.MastodonLauncher

object StartMastodon {
    private val logger by lazyLogger()
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            //gives sciview/scenery a chance to run at all...
            SceneryBase.xinitThreads()

            //without this, the GUI harvesting of plugins will not happen
            val ij = ImageJ()
            ij.ui().showUI()

            //cannot use this one as it opens its own context...
            //new MastodonLauncherCommand().run();
            //
            //...thus copy&paste the run() above.. this time with the shared context
            val launcher = MastodonLauncher(ij.context)
            launcher.isLocationByPlatform = true
            launcher.setLocationRelativeTo(null)
            launcher.isVisible = true
        } catch (e: Exception) {
            e.printStackTrace()
            logger.error("Got this exception: ${e.message}")
        }
    }
}
