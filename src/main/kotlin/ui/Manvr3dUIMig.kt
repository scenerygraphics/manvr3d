package org.mastodon.mamut.ui

import graphics.scenery.utils.lazyLogger
import net.miginfocom.swing.MigLayout
import Manvr3dMain
import ui.AdjustableBoundsRangeSlider
import util.GroupLocksHandling
import util.SphereLinkNodes
import java.awt.event.ActionListener
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.SpinnerModel
import javax.swing.SpinnerNumberModel
import javax.swing.event.ChangeListener

class Manvr3dUIMig(manvr3dContext: Manvr3dMain, populateThisContainer: JPanel) {
    var manvr3dContext: Manvr3dMain?
    val windowPanel: JPanel
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

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
    lateinit var vrResolutionScale: SpinnerModel

    private fun populatePane() {
        val manvr3d = this.manvr3dContext ?: throw IllegalStateException("The passed manvr3d instance cannot be null.")

        windowPanel.layout = MigLayout("insets 15", "[grow,leading] [grow]", "")

        // Lock Group Handling and Mastodon
        lockGroupHandler = GroupLocksHandling(manvr3d, manvr3d.mastodon)
        windowPanel.add(lockGroupHandler.createAndActivate()!!, "growx")

        val openBdvBtn = JButton("Open synced Mastodon BDV").apply {
            addActionListener { manvr3d.openSyncedBDV() }
        }
        windowPanel.add(openBdvBtn, "growx, wrap")

        // MIPMAP Level
        mipmapSpinner = addLabeledSpinner("Choose Mipmap Level", SpinnerNumberModel(0, 0, 6, 1)) { level ->
            this@Manvr3dUIMig.manvr3dContext?.setMipmapLevel(level.toInt())
        }
        this@Manvr3dUIMig.manvr3dContext?.let {
            setMaxMipmapLevel(it.spimSource.numMipmapLevels - 1)
        }

        // Range Slider
        intensityRangeSlider = AdjustableBoundsRangeSlider.Companion.createAndPlaceHere(
            windowPanel,
            manvr3d.intensity.rangeMin.toInt(),
            manvr3d.intensity.rangeMax.toInt(),
            0,
            10000
        )
        intensityRangeSlider.addChangeListener(rangeSliderListener)

        // Link range spinners
        linkRangeBackwards = addLabeledSpinner(
            "Link window range backwards",
            SpinnerNumberModel(manvr3d.mastodon.maxTimepoint, 0, manvr3d.mastodon.maxTimepoint, 1)
        ) { value ->
            manvr3d.sphereLinkNodes.linkBackwardRange = value.toInt()
            manvr3d.sphereLinkNodes.updateLinkVisibility(manvr3d.lastUpdatedSciviewTP)
        }

        linkRangeForwards = addLabeledSpinner(
            "Link window range forwards",
            SpinnerNumberModel(manvr3d.mastodon.maxTimepoint, 0, manvr3d.mastodon.maxTimepoint, 1)
        ) { value ->
            manvr3d.sphereLinkNodes.linkForwardRange = value.toInt()
            manvr3d.sphereLinkNodes.updateLinkVisibility(manvr3d.lastUpdatedSciviewTP)
        }

        spotScaleFactor = addLabeledSpinner(
            "Spot scale factor",
            SpinnerNumberModel(1f, 0.1f, 10f, 0.1f)
        ) { value ->
            manvr3d.sphereLinkNodes.sphereScaleFactor = value.toFloat()
            manvr3d.redrawSciviewSpots()
        }

        vrResolutionScale = addLabeledSpinner(
            "VR Resolution scale",
            SpinnerNumberModel(0.75f, 0.1f, 2f, 0.1f)
        ) { value ->
            manvr3d.setVrResolutionScale(value.toFloat())
        }

        // Adding dropdowns for link LUTs and volume colors
        val colorSelectorPanel = JPanel(MigLayout("fillx, insets 0", "[right][grow, fill]"))

        // Link colors dropdown
        colorSelectorPanel.add(JLabel("Link colors:"), "gapright 10")
        val linkColorChoices = mutableListOf("By Spot")
        val availableLUTs = manvr3d.sciviewWin.getAvailableLUTs() as MutableList<String>
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
        autoIntensityBtn = JToggleButton("Auto Intensity", manvr3d.isVolumeAutoAdjust).apply {
            addActionListener(autoAdjustIntensity)
        }

        val visButtons = JPanel(MigLayout("fillx, insets 0", "[grow]")).apply {
            add(autoIntensityBtn, "growx")
            add(visToggleSpots, "growx")
            add(visToggleVols, "growx")
            add(visToggleTracks, "growx")
        }
        windowPanel.add(visButtons, "span, growx")

        // Launch VR session
        toggleVR = JButton("Start VR").apply {
            addActionListener {
                if (!manvr3d.isVRactive) {
                    val launched = manvr3d.launchVR(eyeTrackingToggle.isSelected)
                    if (launched) {
                        toggleVR.text = "Stop VR"
                    }
                } else {
                    manvr3d.stopVR()
                    toggleVR.text = "Start VR"
                }
            }
        }
        eyeTrackingToggle = JCheckBox("Launch with Eye Tracking")
        eyeTrackingToggle.setSelected(false)
        windowPanel.add(JPanel(MigLayout("fillx, insets 0")).apply {
            add(toggleVR, "growx")
            add(eyeTrackingToggle, "dock east, gapleft 8px")
        }, "span, growx")

        // Close Button
        val closeBtn = JButton("Close").apply { addActionListener { manvr3d.stopAndDetachUI() } }
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
        manvr3dContext.intensity.rangeMin = intensityRangeSlider.value.toFloat()
        manvr3dContext.intensity.rangeMax = intensityRangeSlider.upperValue.toFloat()
        manvr3dContext.volumeNode.minDisplayRange = manvr3dContext.intensity.rangeMin
        manvr3dContext.volumeNode.maxDisplayRange = manvr3dContext.intensity.rangeMax
    }

    val chooseLinkColormap = ActionListener { _ ->
        when (linkColorSelector.selectedItem) {
            "By Spot" -> {
                manvr3dContext.sphereLinkNodes.currentColorMode = SphereLinkNodes.ColorMode.SPOT
                logger.info("Coloring links by spot color")
            }

            else -> {
                manvr3dContext.sphereLinkNodes.currentColorMode = SphereLinkNodes.ColorMode.LUT
                manvr3dContext.sphereLinkNodes.setLUT("${linkColorSelector.selectedItem}.lut")
                logger.info("Coloring links with LUT ${linkColorSelector.selectedItem}")
            }
        }
        manvr3dContext.sphereLinkNodes.updateLinkColors(
            manvr3dContext.recentColorizer ?: manvr3dContext.noTSColorizer
        )
    }

    val chooseVolumeColormap = ActionListener {
        manvr3dContext.sciviewWin.setColormap(manvr3dContext.volumeNode, "${volumeColorSelector.selectedItem}.lut")
        logger.info("Coloring volume with LUT ${volumeColorSelector.selectedItem}")
    }

    val toggleSpotsVisibility = ActionListener {
        val spots = manvr3dContext.volumeNode.getChildrenByName("SpotInstance").first()
        val newState = !spots.visible
        spots.visible = newState
    }
    val toggleVolumeVisibility = ActionListener {
        val newState = !manvr3dContext.volumeNode.visible
        manvr3dContext.setVolumeOnlyVisibility(newState)
    }
    val toggleTrackVisibility = ActionListener {
        val links = manvr3dContext.volumeNode.getChildrenByName("LinkInstance").first()
        val newState = !links.visible
        links.visible = newState
    }

    val autoAdjustIntensity = ActionListener {
        manvr3dContext.autoAdjustIntensity()
    }

    fun updatePaneValues() {
        val manvr3d = this.manvr3dContext ?: throw IllegalStateException("Manvr3d context is null.")
        val updVolAutoBackup = manvr3d.updateVolAutomatically
        //temporarily disable because setting the controls trigger their listeners
        //that trigger (not all of them) the expensive volume updating
        manvr3d.updateVolAutomatically = false

        spotScaleFactor.value = manvr3d.sphereLinkNodes.sphereScaleFactor
        val upperValBackup = manvr3d.intensity.rangeMax

        intensityRangeSlider.rangeSlider.value = manvr3d.intensity.rangeMin.toInt()
        //NB: this triggers a "value changed listener" which updates _both_ the value and upperValue,
        //    which resets the value with the new one (so no change in the end) but clears upperValue
        //    to the value the dialog was left with (forgets the new upperValue effectively)
        manvr3d.intensity.rangeMax = upperValBackup
        intensityRangeSlider.rangeSlider.upperValue = manvr3d.intensity.rangeMax.toInt()
        autoIntensityBtn.isSelected = manvr3d.isVolumeAutoAdjust
        manvr3d.updateVolAutomatically = updVolAutoBackup
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
        this@Manvr3dUIMig.manvr3dContext = null

    }

    init {
        this.manvr3dContext = manvr3dContext
        windowPanel = populateThisContainer
        populatePane()
    }
}