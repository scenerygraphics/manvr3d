package plugins.scijava

import graphics.scenery.utils.lazyLogger
import org.mastodon.mamut.CloseListener
import org.mastodon.mamut.ProjectModel
import org.mastodon.mamut.Manvr3dMain
import org.scijava.command.Command
import org.scijava.command.CommandService
import org.scijava.command.DynamicCommand
import org.scijava.plugin.Parameter
import org.scijava.plugin.Plugin
import sc.iview.SciViewService

@Plugin(type = Command::class, name = "Mastodon to Sciview")
class MastodonSidePlugin : DynamicCommand() {
    @Parameter
    private lateinit var mastodon: ProjectModel

    @Parameter(label = "Try to reuse existing sciview window:")
    var tryToReuseExistingSciviewWindow = true

    @Parameter(label = "Also open the controlling window right away:")
    var openManvr3dUI = true

    @Parameter(label = "Choose image data channel:", choices = [], initializer = "volumeParams")
    var useThisChannel = "default first channel"
    lateinit var channelNames: MutableList<String>

    fun volumeParams() {
        val sources = mastodon.sharedBdvData.sources.size
        channelNames = ArrayList(sources)
        mastodon.sharedBdvData.sources.forEach{ channelNames.add(it.spimSource.name) }
        getInfo()
            .getMutableInput("useThisChannel", String::class.java).choices = channelNames
    }

    override fun run() {
        //figure out the selected source and show another plugin with res levels options
        var chosenChannel = 0
        while (chosenChannel < channelNames.size
            && useThisChannel != channelNames[chosenChannel]
        ) ++chosenChannel
        //NB: theoretically the channel should be always found...
        if (chosenChannel == channelNames.size) return  // !?

        //query res levels for this channel
        context.getService(CommandService::class.java).run(
            MastodonSidePluginResLevels::class.java, true,
            "mastodon", mastodon,
            "channelIdx", chosenChannel,
            "tryToReuseExistingSciviewWindow", tryToReuseExistingSciviewWindow,
            "openManvr3dUI", this@MastodonSidePlugin.openManvr3dUI
        )
    }

    // =============================================================================
    @Plugin(type = Command::class, name = "Mastodon to Sciview: Levels")
    class MastodonSidePluginResLevels : DynamicCommand() {
        private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

        @Parameter
        private lateinit var sciViewService: SciViewService

        @Parameter
        private lateinit var mastodon: ProjectModel

        @Parameter(persist = false)
        private var channelIdx = 0

        @Parameter(persist = false)
        var tryToReuseExistingSciviewWindow = true

        @Parameter(persist = false)
        var openManvr3dUI = true

        @Parameter(label = "Choose resolution level:", choices = [], initializer = "levelParams")
        var useThisResolutionDownscale = "[1,1,1]"
        lateinit var levelNames: MutableList<String>

        fun levelParams() {
            val chSource = mastodon.sharedBdvData.sources[channelIdx].spimSource
            val levels = chSource.numMipmapLevels
            levelNames = ArrayList(levels)
            val baseLevelDims = LongArray(3)
            val curLevelDims = LongArray(3)
            chSource.getSource(0, 0).dimensions(baseLevelDims)
            levelNames.add("[1,1,1]")
            for (level in 1 until levels) {
                chSource.getSource(0, level).dimensions(curLevelDims)
                levelNames.add("[" + baseLevelDims[0] / curLevelDims[0] + "," + baseLevelDims[1] / curLevelDims[1] + "," + baseLevelDims[2] / curLevelDims[2] + "]")
            }
            info.getMutableInput("useThisResolutionDownscale", String::class.java).choices = levelNames
        }

        override fun run() {
            //figure out the selected res. level
            var chosenLevel = 0
            while (chosenLevel < levelNames.size
                && useThisResolutionDownscale != levelNames[chosenLevel]
            ) ++chosenLevel
            //NB: theoretically the channel should be always found...
            if (chosenLevel == levelNames.size) return  // !?
            logger.info(
                "Selected volume from channel $channelIdx of ${chosenLevel + 1}-best resolution level..."
            )
            try {
                if (!tryToReuseExistingSciviewWindow) sciViewService.createSciView()
                val sv = sciViewService.getOrCreateActiveSciView()
                val manvr3d = Manvr3dMain(mastodon, channelIdx, chosenLevel, sv)
                if (openManvr3dUI) manvr3d.createAndShowControllingUI()
                mastodon.projectClosedListeners().add(CloseListener {
                    logger.debug("Mastodon project was closed, cleaning up in sciview:")
                    manvr3d.close() //calls also manvr3dMain.detachControllingUI();
                })
            } catch (e: Exception) {
                logger.error("MastodonSciview plugin error: " + e.message)
                e.printStackTrace()
            }
        }
    }
}
