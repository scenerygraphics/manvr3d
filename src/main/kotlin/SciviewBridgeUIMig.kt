package org.mastodon.mamut

import graphics.scenery.utils.lazyLogger
import net.miginfocom.swing.MigLayout
import util.AdjustableBoundsRangeSlider
import util.GroupLocksHandling
import util.SphereLinkNodes
import java.awt.event.ActionListener
import javax.swing.*
import javax.swing.event.ChangeListener

class SciviewBridgeUIMig(controlledBridge: SciviewBridge, populateThisContainer: JPanel) {
    var controlledBridge: SciviewBridge?
    val windowPanel: JPanel
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    lateinit var intensityContrastSpinner: SpinnerModel
    lateinit var intensityShiftSpinner: SpinnerModel
    lateinit var intensityClampTopSpinner: SpinnerModel
    lateinit var intensityGammaSpinner: SpinnerModel
    lateinit var intensityRangeSlider: AdjustableBoundsRangeSlider
    lateinit var mipmapSpinner: SpinnerNumberModel
    lateinit var visToggleSpots: JButton
    lateinit var visToggleVols: JButton
    lateinit var visToggleTracks: JButton
    lateinit var linkRangeBackwards: SpinnerModel
    lateinit var linkRangeForwards: SpinnerModel
    lateinit var spotScaleFactor: SpinnerModel
    lateinit var autoIntensityBtn: JToggleButton
    lateinit var lockGroupHandler: GroupLocksHandling
    lateinit var linkColorSelector: JComboBox<String>
    lateinit var volumeColorSelector: JComboBox<String>
    lateinit var toggleVR: JButton
    lateinit var eyeTrackingToggle: JCheckBox

    private fun populatePane() {
        val bridge = this.controlledBridge ?: throw IllegalStateException("The passed bridge cannot be null.")

        windowPanel.layout = MigLayout("insets 15", "[grow,leading] [grow]", "")

        // Lock Group Handling and Mastodon
        lockGroupHandler = GroupLocksHandling(bridge, bridge.mastodon)
        windowPanel.add(lockGroupHandler.createAndActivate()!!, "growx")

        val openBdvBtn = JButton("Open synced Mastodon BDV").apply {
            addActionListener { bridge.openSyncedBDV() }
        }
        windowPanel.add(openBdvBtn, "growx, wrap")

        // Intensity controls
        windowPanel.add(
            JLabel("Volume pixel values 'v' are processed linearly, normalized, gamma, scaled back:"),
            "span, growx"
        )
        windowPanel.add(JLabel("   pow( min(contrast*v + shift, not_above)/not_above, gamma ) *not_above"), "span")

        intensityContrastSpinner = addLabeledSpinner(
            "Apply on Volume this contrast factor:",
            SpinnerNumberModel(1.0, -100.0, 100.0, 0.5)
        ) { value -> bridge.intensity.contrast = value.toFloat() }

        intensityShiftSpinner = addLabeledSpinner(
            "Apply on Volume this shifting bias:",
            SpinnerNumberModel(0.0, -65535.0, 65535.0, 50.0)
        ) { value -> bridge.intensity.shift = value.toFloat() }

        intensityGammaSpinner = addLabeledSpinner(
            "Apply on Volume this gamma level:",
            SpinnerNumberModel(1.0, 0.1, 3.0, 0.1)
        ) { value -> bridge.intensity.gamma = value.toFloat() }

        intensityClampTopSpinner = addLabeledSpinner(
            "Clamp all voxels so that their values are not above:",
            SpinnerNumberModel(700.0, 0.0, 65535.0, 50.0)
        ) { value -> bridge.intensity.clampTop = value.toFloat() }

        // MIPMAP Level
        mipmapSpinner = addLabeledSpinner("Choose Mipmap Level", SpinnerNumberModel(0, 0, 6, 1)) { level ->
            controlledBridge?.setMipmapLevel(level.toInt())
        }
        controlledBridge?.let {
            setMaxMipmapLevel(it.spimSource.numMipmapLevels - 1)
        }

        // Range Slider
        intensityRangeSlider = AdjustableBoundsRangeSlider.createAndPlaceHere(
            windowPanel,
            bridge.intensity.rangeMin.toInt(),
            bridge.intensity.rangeMax.toInt(),
            0,
            10000
        )
        intensityRangeSlider.addChangeListener(rangeSliderListener)

        // Link range spinners
        linkRangeBackwards = addLabeledSpinner(
            "Link window range backwards",
            SpinnerNumberModel(bridge.mastodon.maxTimepoint, 0, bridge.mastodon.maxTimepoint, 1)
        ) { value ->
            bridge.sphereLinkNodes.linkBackwardRange = value.toInt()
            bridge.sphereLinkNodes.updateLinkVisibility(bridge.lastUpdatedSciviewTP)
        }

        linkRangeForwards = addLabeledSpinner(
            "Link window range forwards",
            SpinnerNumberModel(bridge.mastodon.maxTimepoint, 0, bridge.mastodon.maxTimepoint, 1)
        ) { value ->
            bridge.sphereLinkNodes.linkForwardRange = value.toInt()
            bridge.sphereLinkNodes.updateLinkVisibility(bridge.lastUpdatedSciviewTP)
        }

        spotScaleFactor = addLabeledSpinner(
            "Spot scale factor",
            SpinnerNumberModel(1f, 0.1f, 10f, 0.1f)
        ) { value ->
            bridge.sphereLinkNodes.sphereScaleFactor = value.toFloat()
            bridge.sphereLinkNodes.updateSphereScales()
        }

        // Adding dropdowns for link LUTs and volume colors
        val colorSelectorPanel = JPanel(MigLayout("fillx, insets 0", "[right][grow, fill]"))

        // Link colors dropdown
        colorSelectorPanel.add(JLabel("Link colors:"), "gapright 10")
        val linkColorChoices = mutableListOf("By Spot")
        val availableLUTs = bridge.sciviewWin.getAvailableLUTs() as MutableList<String>
        for (i in availableLUTs.indices) {
            availableLUTs[i] = availableLUTs[i].removeSuffix(".lut")
        }
        linkColorChoices.addAll(availableLUTs)

        linkColorSelector = JComboBox(linkColorChoices.toTypedArray())
        linkColorSelector.setSelectedItem("Fire")
        linkColorSelector.addActionListener(chooseLinkColormap)
        colorSelectorPanel.add(linkColorSelector, "wrap")

        // Volume colors dropdown
        colorSelectorPanel.add(JLabel("Volume colors:"), "gapright 10")
        volumeColorSelector = JComboBox(availableLUTs.toTypedArray())
        volumeColorSelector.setSelectedItem("Grays")
        volumeColorSelector.addActionListener(chooseVolumeColormap)
        colorSelectorPanel.add(volumeColorSelector)

        // Add the color selector panel to the main panel
        windowPanel.add(colorSelectorPanel, "span, growx, wrap")

        // Visualization Toggles
        visToggleSpots = JButton("Toggle spots").apply { addActionListener(toggleSpotsVisibility) }
        visToggleVols = JButton("Toggle volume").apply { addActionListener(toggleVolumeVisibility) }
        visToggleTracks = JButton("Toggle tracks").apply { addActionListener(toggleTrackVisibility) }
        autoIntensityBtn = JToggleButton("Auto Intensity", bridge.isVolumeAutoAdjust).apply {
            addActionListener(autoAdjustIntensity)
        }

        val visButtons = JPanel(MigLayout("fillx, insets 0", "[grow]")).apply {
            add(autoIntensityBtn, "growx")
            add(visToggleSpots, "growx")
            add(visToggleVols, "growx")
            add(visToggleTracks, "growx")
        }
        windowPanel.add(visButtons, "span, growx")

        // Eye Tracking
        toggleVR = JButton("Start VR").apply { addActionListener {
            if (toggleVR.text == "Start VR") {
                bridge.launchVR(eyeTrackingToggle.isSelected)
                toggleVR.text = "Stop VR"
            } else {
                bridge.stopVR()
                toggleVR.text = "Start VR"
            }
        } }
        eyeTrackingToggle = JCheckBox("Launch with Eye Tracking")
        eyeTrackingToggle.setSelected(true)
        windowPanel.add(JPanel(MigLayout("fillx, insets 0")).apply {
            add(toggleVR, "growx")
            add(eyeTrackingToggle, "dock east, gapleft 8px")
        }, "span, growx")

        // Close Button
        val closeBtn = JButton("Close").apply { addActionListener { bridge.stopAndDetachUI() } }
        windowPanel.add(closeBtn, "span, right")
    }


    fun addLabeledSpinner(labelText: String, spinnerModel: SpinnerNumberModel, onChange: (Number) -> Unit): SpinnerNumberModel {
        val label = JLabel(labelText)
        val spinner = JSpinner(spinnerModel)

        spinner.addChangeListener { onChange(spinner.value as Number) }

        // Adding the label and spinner to the panel
        windowPanel.add(label)
        windowPanel.add(spinner, "w 150, right, wrap")
        return spinnerModel
    }

    /** Sets the maximum mipmap level found in the volume node as the spinner's max value. */
    fun setMaxMipmapLevel(level: Int) {
        mipmapSpinner.maximum = level
    }

    val rangeSliderListener = ChangeListener {
        controlledBridge.intensity.rangeMin = intensityRangeSlider.value.toFloat()
        controlledBridge.intensity.rangeMax = intensityRangeSlider.upperValue.toFloat()
        controlledBridge.volumeNode.minDisplayRange = controlledBridge.intensity.rangeMin
        controlledBridge.volumeNode.maxDisplayRange = controlledBridge.intensity.rangeMax
    }

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
    val toggleTrackVisibility = ActionListener {
        val links = controlledBridge.volumeNode.getChildrenByName("LinkInstance").first()
        val newState = !links.visible
        links.visible = newState
    }

    val autoAdjustIntensity = ActionListener {
        controlledBridge.autoAdjustIntensity()
    }

    fun updatePaneValues() {
        val bridge = this.controlledBridge ?: throw IllegalStateException("Bridge is null.")
        val updVolAutoBackup = bridge.updateVolAutomatically
        //temporarily disable because setting the controls trigger their listeners
        //that trigger (not all of them) the expensive volume updating

        bridge.updateVolAutomatically = false
        intensityContrastSpinner.value = bridge.intensity.contrast
        intensityShiftSpinner.value = bridge.intensity.shift
        intensityClampTopSpinner.value = bridge.intensity.clampTop
        intensityGammaSpinner.value = bridge.intensity.gamma
        spotScaleFactor.value = bridge.sphereLinkNodes.sphereScaleFactor
        val upperValBackup = bridge.intensity.rangeMax

        intensityRangeSlider.rangeSlider.value = bridge.intensity.rangeMin.toInt()
        //NB: this triggers a "value changed listener" which updates _both_ the value and upperValue,
        //    which resets the value with the new one (so no change in the end) but clears upperValue
        //    to the value the dialog was left with (forgets the new upperValue effectively)
        bridge.intensity.rangeMax = upperValBackup
        intensityRangeSlider.rangeSlider.upperValue = bridge.intensity.rangeMax.toInt()
        autoIntensityBtn.isSelected = bridge.isVolumeAutoAdjust
        bridge.updateVolAutomatically = updVolAutoBackup
    }

    fun deactivateAndForget() {
        //listeners tear-down here
        lockGroupHandler.deactivate()
        // Remove listeners for link colors and volume colors
        linkColorSelector.removeActionListener(chooseLinkColormap)
        volumeColorSelector.removeActionListener(chooseVolumeColormap)

        intensityRangeSlider.removeChangeListener(rangeSliderListener)
        visToggleSpots.removeActionListener(toggleSpotsVisibility)
        visToggleVols.removeActionListener(toggleVolumeVisibility)
        autoIntensityBtn.removeActionListener(autoAdjustIntensity)
        controlledBridge = null

    }

    init {
        this.controlledBridge = controlledBridge
        windowPanel = populateThisContainer
        populatePane()
    }
}
