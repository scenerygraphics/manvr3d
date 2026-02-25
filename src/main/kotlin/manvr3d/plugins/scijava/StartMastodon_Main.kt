package manvr3d.plugins.scijava

import graphics.scenery.SceneryBase
import net.imagej.ImageJ
import org.mastodon.mamut.launcher.MastodonLauncher


fun main() {
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
}
