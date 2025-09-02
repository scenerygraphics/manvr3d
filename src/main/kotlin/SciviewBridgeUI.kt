package org.mastodon.mamut

import graphics.scenery.utils.lazyLogger
import util.AdjustableBoundsRangeSlider
import util.GroupLocksHandling
import util.SphereLinkNodes
import java.awt.*
import java.awt.event.ActionListener
import java.util.function.Consumer
import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener

class SciviewBridgeUI(controlledBridge: SciviewBridge, populateThisContainer: Container) {
    var controlledBridge: SciviewBridge?
    val controlsWindowPanel: Container
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    //int SOURCE_ID = 0;
    //int SOURCE_USED_RES_LEVEL = 0;
    lateinit var INTENSITY_CONTRAST: SpinnerModel
    lateinit var INTENSITY_SHIFT: SpinnerModel
    lateinit var INTENSITY_CLAMP_AT_TOP: SpinnerModel
    lateinit var INTENSITY_GAMMA: SpinnerModel
    lateinit var INTENSITY_RANGE_MINMAX_CTRL_GUI_ELEM: AdjustableBoundsRangeSlider
    lateinit var MIPMAP_LEVEL: SpinnerNumberModel
    //
    lateinit var visToggleSpots: JButton
    lateinit var visToggleVols: JButton
    lateinit var visToggleTracks: JButton
    lateinit var linkRangeBackwards: SpinnerModel
    lateinit var linkRangeForwards: SpinnerModel
    lateinit var autoIntensityBtn: JToggleButton
    lateinit var lockGroupHandler: GroupLocksHandling
    lateinit var linkColorSelector: JComboBox<String>
    lateinit var volumeColorSelector: JComboBox<String>
    lateinit var startEyeTracking: JButton
    lateinit var stopEyeTracking: JButton

    // -------------------------------------------------------------------------------------------
    private fun populatePane() {
        val bridge = this.controlledBridge ?: throw IllegalStateException("The passed bridge cannot be null.")

        controlsWindowPanel.setLayout(GridBagLayout())
        val c = GridBagConstraints()
        c.anchor = GridBagConstraints.LINE_START
        c.fill = GridBagConstraints.HORIZONTAL
        val mastodonRowPlaceHolder = JPanel()
        mastodonRowPlaceHolder.setLayout(GridBagLayout())
        val mc = GridBagConstraints()
        mc.anchor = GridBagConstraints.LINE_START
        mc.fill = GridBagConstraints.HORIZONTAL
        mc.insets = Insets(0, 0, 0, 0)
        mc.weightx = 0.2
        mc.gridx = 0
        lockGroupHandler = GroupLocksHandling(bridge, bridge.mastodon)
        mastodonRowPlaceHolder.add(lockGroupHandler.createAndActivate()!!, mc)
        mc.weightx = 0.6
        mc.gridx = 1
        val openBdvBtn = JButton("Open synced Mastodon BDV")
        openBdvBtn.addActionListener { bridge.openSyncedBDV() }
        mastodonRowPlaceHolder.add(openBdvBtn, mc)
        //
        c.gridy = 0
        c.gridwidth = 2
        c.gridx = 0
        c.weightx = 0.1
        c.insets = Insets(4, sideSpace, 8, sideSpace - 2)
        controlsWindowPanel.add(mastodonRowPlaceHolder, c)
        c.gridy++
        c.insets = Insets(2, sideSpace, 2, sideSpace)
        controlsWindowPanel.add(
            JLabel("Volume pixel values 'v' are processed linearly, normalized, gamma, scaled back:"),
            c
        )
        c.gridwidth = 1
        c.gridy++
        insertNote("   pow( min(contrast*v +shift, not_above)/not_above, gamma ) *not_above", c)
        c.gridy++
        c.gridx = 0
        insertLabel("Apply on Volume this contrast factor:", c)
        //
        c.gridx = 1
        INTENSITY_CONTRAST = SpinnerNumberModel(1.0, -100.0, 100.0, 0.5)
        insertSpinner(INTENSITY_CONTRAST, { f: Float -> bridge.intensity.contrast = f }, c)
        c.gridy++
        c.gridx = 0
        insertLabel("Apply on Volume this shifting bias:", c)
        //
        c.gridx = 1
        INTENSITY_SHIFT = SpinnerNumberModel(0.0, -65535.0, 65535.0, 50.0)
        insertSpinner(INTENSITY_SHIFT, { f: Float -> bridge.intensity.shift = f }, c)
        c.gridy++
        c.gridx = 0
        insertLabel("Apply on Volume this gamma level:", c)
        //
        c.gridx = 1
        INTENSITY_GAMMA = SpinnerNumberModel(1.0, 0.1, 3.0, 0.1)
        insertSpinner(INTENSITY_GAMMA, { f: Float -> bridge.intensity.gamma = f }, c)
        c.gridy++
        c.gridx = 0
        insertLabel("Clamp all voxels so that their values are not above:", c)
        //
        c.gridx = 1
        INTENSITY_CLAMP_AT_TOP = SpinnerNumberModel(700.0, 0.0, 65535.0, 50.0)
        insertSpinner(
            INTENSITY_CLAMP_AT_TOP,
            { f: Float -> bridge.intensity.clampTop = f },
            c
        )

        // MIPMAP levels
        c.gridy++
        c.gridx = 0
        insertLabel("Choose Mipmap Level", c)
        c.gridx = 1
        MIPMAP_LEVEL = SpinnerNumberModel(0, 0, 6, 1)
        insertSpinner(MIPMAP_LEVEL, { level: Float ->
            controlledBridge?.let {
                // update the UI spinner to allow spinning up to the mipmap level found in the volume
                // subtract 1 to go from range 0 to max
                setMaxMipmapLevel(it.sac.spimSource.numMipmapLevels - 1)
                it.setMipmapLevel(level.toInt())
            }
        }, c)

        // -------------- separator --------------
        c.gridy++
        insertSeparator(c)
        c.gridy++
        insertNote("Shortcuts to the standard sciview view controls, incl. Volume intensity mapping", c)
        c.gridy++
        val sliderBarPlaceHolder = JPanel()
        c.gridx = 0
        c.gridwidth = 2
        controlsWindowPanel.add(sliderBarPlaceHolder, c)
        //
        INTENSITY_RANGE_MINMAX_CTRL_GUI_ELEM = AdjustableBoundsRangeSlider.createAndPlaceHere(
            sliderBarPlaceHolder,
            bridge.intensity.rangeMin.toInt(),
            bridge.intensity.rangeMax.toInt(),
            0,
            10000
        )
        INTENSITY_RANGE_MINMAX_CTRL_GUI_ELEM.addChangeListener(rangeSliderListener)

        // links window range
        c.gridy++
        c.gridwidth = 2
        c.gridx = 0
        insertLabel("Link window range backwards", c)
        c.gridx = 1
        linkRangeBackwards = SpinnerNumberModel(bridge.mastodon.maxTimepoint, 0, bridge.mastodon.maxTimepoint, 1)
        insertSpinner(
            linkRangeBackwards,
            {
                f: Float -> bridge.sphereLinkNodes.linkBackwardRange = f.toInt()
                bridge.sphereLinkNodes.updateLinkVisibility(bridge.lastUpdatedSciviewTP)
            },
            c)

        c.gridy++
        c.gridx = 0
        insertLabel("Link window range forwards:", c)
        c.gridx = 1
        linkRangeForwards = SpinnerNumberModel(bridge.mastodon.maxTimepoint, 0, bridge.mastodon.maxTimepoint, 1)
        insertSpinner(
            linkRangeForwards,
            {
                f: Float -> bridge.sphereLinkNodes.linkForwardRange = f.toInt()
                bridge.sphereLinkNodes.updateLinkVisibility(bridge.lastUpdatedSciviewTP)
            },
            c
        )


        // ----------- color parameters --------------
        c.gridy++
        c.gridwidth = 4
        val colorPlaceholder = JPanel()
        c.gridx = 0
        controlsWindowPanel.add(colorPlaceholder, c)
        colorPlaceholder.setLayout(GridBagLayout())
        val coloringRow = GridBagConstraints()
        coloringRow.fill = GridBagConstraints.HORIZONTAL
        coloringRow.gridx = 0
        colorPlaceholder.add(Label("Link colors: "), coloringRow)
        coloringRow.gridx = 1
        // add the first choice of the list manually
        val linkColorChoices = mutableListOf("By Spot")
        // get the rest of the LUTs from sciview and clean up their names
        val availableLUTs = bridge.sciviewWin.getAvailableLUTs() as MutableList<String>
        for (i in availableLUTs.indices) {
            availableLUTs[i] = availableLUTs[i].removeSuffix(".lut")
        }
        linkColorChoices.addAll(availableLUTs)

        // create dropdown menu for link LUTs
        linkColorSelector = JComboBox(linkColorChoices.toTypedArray())
        linkColorSelector.setSelectedItem("Fire")
        colorPlaceholder.add(linkColorSelector, coloringRow)
        linkColorSelector.addActionListener(chooseLinkColormap)

        // Volume colors
        coloringRow.gridx = 2
        coloringRow.insets = Insets(0, 10, 0, 0)
        colorPlaceholder.add(Label("Volume colors: "), coloringRow)
        coloringRow.gridx = 3
        volumeColorSelector = JComboBox(availableLUTs.toTypedArray())
        volumeColorSelector.setSelectedItem("Grays")
        colorPlaceholder.add(volumeColorSelector, coloringRow)
        volumeColorSelector.addActionListener(chooseVolumeColormap)

        // the four toggle buttons
        c.gridy++
        visToggleSpots = JButton("Toggle spots")
        visToggleSpots.addActionListener(toggleSpotsVisibility)
        visToggleVols = JButton("Toggle volume")
        visToggleVols.addActionListener(toggleVolumeVisibility)
        visToggleTracks = JButton("Toggle tracks")
        visToggleTracks.addActionListener(toggleTrackVisivility)

        autoIntensityBtn = JToggleButton("Auto Intensity", bridge.isVolumeAutoAdjust)
        autoIntensityBtn.addActionListener(autoAdjustIntensity)
        //
        val visButtonsPlaceholder = JPanel()
        controlsWindowPanel.add(visButtonsPlaceholder, c)
        //
        visButtonsPlaceholder.setLayout(GridBagLayout())
        val visButtonRow = GridBagConstraints()
        visButtonRow.fill = GridBagConstraints.HORIZONTAL
        visButtonRow.anchor = GridBagConstraints.WEST
        visButtonRow.weightx = 0.4
        visButtonRow.gridx = 0
//        bc.insets = Insets(0, 20, 0, 0)
        visButtonsPlaceholder.add(autoIntensityBtn, visButtonRow)
        visButtonRow.gridx = 1
        visButtonRow.insets = Insets(0, 20, 0, 0)
        visButtonsPlaceholder.add(visToggleSpots, visButtonRow)
        visButtonRow.gridx = 2
        visButtonRow.insets = Insets(0, 20, 0, 0)
        visButtonsPlaceholder.add(visToggleVols, visButtonRow)
        visButtonRow.gridx = 3
        visButtonRow.insets = Insets(0, 20, 0, 0)
        visButtonsPlaceholder.add(visToggleTracks, visButtonRow)

        // ---------- Eye Tracking Buttons ---------------
        c.gridy++

        startEyeTracking = JButton("Start Eye Tracking")
        startEyeTracking.addActionListener { bridge.launchVR() }
        stopEyeTracking = JButton("Stop Eye Tracking")
        stopEyeTracking.addActionListener { bridge.stopVR() }

        val trackingBtnPlaceholder = JPanel()
        controlsWindowPanel.add(trackingBtnPlaceholder, c)
        val trackButtonRow = GridBagConstraints()
        trackButtonRow.fill = GridBagConstraints.HORIZONTAL
        trackButtonRow.anchor = GridBagConstraints.WEST
        trackButtonRow.weightx = 0.2
        trackButtonRow.gridx = 0
        trackingBtnPlaceholder.add(startEyeTracking, trackButtonRow)
        trackButtonRow.gridx = 1
        trackButtonRow.insets = Insets(0, 20, 0, 0)
        trackingBtnPlaceholder.add(stopEyeTracking, trackButtonRow)

        // -------------- close button row --------------
        c.gridy++
        c.gridx = 1
        c.gridwidth = 1
        val closeBtn = JButton("Close")
        closeBtn.addActionListener { bridge.stopAndDetachUI() }
        c.insets = Insets(0, 0, 15, 15)
        controlsWindowPanel.add(closeBtn, c)
    }

    /** Sets the maximum mipmap level found in the volume node as the spinner's max value. */
    fun setMaxMipmapLevel(level: Int) {
        MIPMAP_LEVEL.maximum = level
    }

    val sideSpace = 15
    val noteSpace = Insets(2, sideSpace, 8, 2 * sideSpace)

    fun insertNote(noteText: String?, c: GridBagConstraints) {
        val prevGridW = c.gridwidth
        val prevInsets = c.insets
        c.gridwidth = 2
        c.insets = noteSpace
        c.weightx = 0.1
        c.gridx = 0
        controlsWindowPanel.add(JLabel(noteText), c)
        c.gridwidth = prevGridW
        c.insets = prevInsets
    }

    fun insertLabel(labelText: String?, c: GridBagConstraints) {
        c.weightx = 0.5
        controlsWindowPanel.add(JLabel(labelText), c)
    }

    val spinnerMinDim = Dimension(40, 20)
    val spinnerMaxDim = Dimension(200, 20)
    fun insertSpinner(
        model: SpinnerModel,
        updaterOnEvents: Consumer<Float>,
        c: GridBagConstraints
    ) {
        c.anchor = GridBagConstraints.LINE_END
        insertRColumnItem(JSpinner(model), c)
        c.anchor = GridBagConstraints.LINE_START
        val l = OwnerAwareSpinnerChangeListener(updaterOnEvents, model)
        model.addChangeListener(l)
        spinnerModelsWithListeners.add(l)
    }

    fun insertRColumnItem(item: JComponent, c: GridBagConstraints) {
        item.minimumSize = spinnerMinDim
        item.preferredSize = spinnerMinDim
        item.maximumSize = spinnerMaxDim
        c.weightx = 0.3
        controlsWindowPanel.add(item, c)
    }

    fun insertCheckBox(cbox: JCheckBox, c: GridBagConstraints) {
        val prevFill = c.fill
        val prevGridW = c.gridwidth
        checkBoxesWithListeners.add(cbox)
        c.fill = GridBagConstraints.HORIZONTAL
        c.gridwidth = 2
        c.gridx = 0
        c.weightx = 0.1
        controlsWindowPanel.add(cbox, c)
        c.fill = prevFill
        c.gridwidth = prevGridW
    }

    val sepSpace = Insets(8, sideSpace, 8, sideSpace)
    fun insertSeparator(c: GridBagConstraints) {
        val prevFill = c.fill
        val prevGridW = c.gridwidth
        val prevInsets = c.insets
        c.fill = GridBagConstraints.HORIZONTAL
        c.gridwidth = 2
        c.weightx = 0.1
        c.insets = sepSpace
        c.gridx = 0
        controlsWindowPanel.add(JSeparator(JSeparator.HORIZONTAL), c)
        c.fill = prevFill
        c.gridwidth = prevGridW
        c.insets = prevInsets
    }

    inner class OwnerAwareSpinnerChangeListener //
        (
        val pushChangeToHere: Consumer<Float>,
        val observedSource: SpinnerModel
    ) : ChangeListener {
        //
        override fun stateChanged(changeEvent: ChangeEvent) {
            val bridge = this@SciviewBridgeUI.controlledBridge ?: throw IllegalStateException("Bridge is null.")
            val s = changeEvent.source as SpinnerNumberModel
            pushChangeToHere.accept(s.number.toFloat())
            if (bridge.updateVolAutomatically) bridge.updateSciviewTPfromBDV()
        }
    }

    val spinnerModelsWithListeners: MutableList<OwnerAwareSpinnerChangeListener> = ArrayList(10)
    val checkBoxesWithListeners: MutableList<JCheckBox> = ArrayList(10)

    val chooseLinkColormap = ActionListener { _ ->
        when (linkColorSelector.selectedItem) {
            "By Spot" -> {
                controlledBridge.sphereLinkNodes.currentColorMode = SphereLinkNodes.ColorMode.SPOT
                logger.info("Coloring links by spot color")
            }
            else -> {
                controlledBridge.sphereLinkNodes.currentColorMode = SphereLinkNodes.ColorMode.LUT
                controlledBridge.sphereLinkNodes.setLUT("${linkColorSelector.selectedItem}.lut")
                logger.info("Coloring links with LUT ${linkColorSelector.selectedItem}")
            }
        }
        controlledBridge.sphereLinkNodes.updateLinkColors(controlledBridge.recentColorizer ?: controlledBridge.noTSColorizer)
    }

    val chooseVolumeColormap = ActionListener {
        controlledBridge.sciviewWin.setColormap(controlledBridge.volumeNode, "${volumeColorSelector.selectedItem}.lut")
        logger.info("Coloring volume with LUT ${volumeColorSelector.selectedItem}")
    }

    val toggleSpotsVisibility = ActionListener {
        val spots = controlledBridge.volumeNode.getChildrenByName("SpotInstance").first()
        val newState = !spots.visible
        spots.visible = newState
    }
    val toggleVolumeVisibility = ActionListener {
        val spots = controlledBridge.volumeNode.getChildrenByName("SpotInstance").first()
        val spotVis = spots.visible
        val links = controlledBridge.volumeNode.getChildrenByName("LinkInstance").first()
        val linksVis = links.visible
        val newState = !controlledBridge.volumeNode.visible
        controlledBridge.setVisibilityOfVolume(newState)
        spots.visible = spotVis
        links.visible = linksVis
    }
    val toggleTrackVisivility = ActionListener {
        val links = controlledBridge.volumeNode.getChildrenByName("LinkInstance").first()
        val newState = !links.visible
        links.visible = newState
    }

    val autoAdjustIntensity = ActionListener {
        controlledBridge.autoAdjustIntensity()
    }

    /**
     * Disable all listeners to make sure that, even if this UI window would ever
     * be re-displayed, its controls could not control anything (and would throw
     * NPEs if the controls would actually be used).
     */
    fun deactivateAndForget() {
        //listeners tear-down here
        lockGroupHandler.deactivate()
        spinnerModelsWithListeners.forEach { c: OwnerAwareSpinnerChangeListener ->
            c.observedSource.removeChangeListener(
                c
            )
        }
        INTENSITY_RANGE_MINMAX_CTRL_GUI_ELEM.removeChangeListener(rangeSliderListener)
        visToggleSpots.removeActionListener(toggleSpotsVisibility)
        visToggleVols.removeActionListener(toggleVolumeVisibility)
        autoIntensityBtn.removeActionListener(autoAdjustIntensity)
        controlledBridge = null
    }

    fun updatePaneValues() {
        val bridge = this.controlledBridge ?: throw IllegalStateException("Bridge is null.")
        val updVolAutoBackup = bridge.updateVolAutomatically
        //temporarily disable because setting the controls trigger their listeners
        //that trigger (not all of them) the expensive volume updating

        bridge.updateVolAutomatically = false
        INTENSITY_CONTRAST.value = bridge.intensity.contrast
        INTENSITY_SHIFT.value = bridge.intensity.shift
        INTENSITY_CLAMP_AT_TOP.value = bridge.intensity.clampTop
        INTENSITY_GAMMA.value = bridge.intensity.gamma
        val upperValBackup = bridge.intensity.rangeMax

        INTENSITY_RANGE_MINMAX_CTRL_GUI_ELEM
            .rangeSlider
            .value = bridge.intensity.rangeMin.toInt()
        //NB: this triggers a "value changed listener" which updates _both_ the value and upperValue,
        //    which resets the value with the new one (so no change in the end) but clears upperValue
        //    to the value the dialog was left with (forgets the new upperValue effectively)
        bridge.intensity.rangeMax = upperValBackup
        INTENSITY_RANGE_MINMAX_CTRL_GUI_ELEM
            .rangeSlider
            .upperValue = bridge.intensity.rangeMax.toInt()

        autoIntensityBtn.isSelected = bridge.isVolumeAutoAdjust
        bridge.updateVolAutomatically = updVolAutoBackup
    }


    val rangeSliderListener = ChangeListener {
        controlledBridge.intensity.rangeMin = INTENSITY_RANGE_MINMAX_CTRL_GUI_ELEM.value.toFloat()
        controlledBridge.intensity.rangeMax = INTENSITY_RANGE_MINMAX_CTRL_GUI_ELEM.upperValue.toFloat()
        controlledBridge.volumeNode.minDisplayRange = controlledBridge.intensity.rangeMin
        controlledBridge.volumeNode.maxDisplayRange = controlledBridge.intensity.rangeMax
    }



    init {
        this.controlledBridge = controlledBridge
        controlsWindowPanel = populateThisContainer
        populatePane()
        updatePaneValues()
    }

    companion object {
        const val updVolMsgA = "Automatically"
        const val updVolMsgM = "Manually"
    }
}