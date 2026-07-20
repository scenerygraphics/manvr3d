package graphics.scenery.manvr3d.ui

import graphics.scenery.utils.lazyLogger
import net.miginfocom.swing.MigLayout
import graphics.scenery.manvr3d.Manvr3dMain
import graphics.scenery.manvr3d.util.GroupLocksHandling
import graphics.scenery.manvr3d.util.GeometryHandler
import graphics.scenery.utils.extensions.toAwt
import graphics.scenery.volumes.Colormap
import java.awt.Color
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

class Manvr3dWindowLayout(manvr3dContext: Manvr3dMain, populateThisContainer: JPanel) {
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
    lateinit var linkScaleFactor: SpinnerModel
    lateinit var autoIntensityBtn: JToggleButton
    lateinit var lockGroupHandler: GroupLocksHandling
    lateinit var linkColorSelector: JComboBox<String>
    lateinit var volumeColorSelector: JComboBox<String>
    lateinit var toggleVR: JButton
    lateinit var eyeTrackingToggle: JCheckBox
    lateinit var vrResolutionScale: SpinnerModel
    lateinit var uncertaintyToggle: JCheckBox
    var invertLutToggle: JCheckBox? = null

    private val uncertaintyLowSwatch = JPanel().apply {
        preferredSize = java.awt.Dimension(12, 12)
        border = javax.swing.BorderFactory.createLineBorder(Color.GRAY, 1)
    }
    private val uncertaintyMidSwatch = JPanel().apply {
        preferredSize = java.awt.Dimension(12, 12)
        border = javax.swing.BorderFactory.createLineBorder(Color.GRAY, 1)
    }
    private val uncertaintyHighSwatch = JPanel().apply {
        preferredSize = java.awt.Dimension(12, 12)
        border = javax.swing.BorderFactory.createLineBorder(Color.GRAY, 1)
    }

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
        mipmapSpinner = addLabeledSpinner("Choose Mipmap Level",
            SpinnerNumberModel(manvr3d.initMipmapLevel, 0, 6, 1)) { level ->
            manvr3d.setMipmapLevel(level.toInt())
        }

        setMaxMipmapLevel(manvr3d.spimSource.numMipmapLevels - 1)

        // Range Slider
        intensityRangeSlider = AdjustableBoundsRangeSlider.createAndPlaceHere(
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
            manvr3d.geometryHandler.linkBackwardRange = value.toInt()
            manvr3d.geometryHandler.updateSegmentVisibility(manvr3d.currentTimepoint)
        }

        linkRangeForwards = addLabeledSpinner(
            "Link window range forwards",
            SpinnerNumberModel(manvr3d.mastodon.maxTimepoint, 0, manvr3d.mastodon.maxTimepoint, 1)
        ) { value ->
            manvr3d.geometryHandler.linkForwardRange = value.toInt()
            manvr3d.geometryHandler.updateSegmentVisibility(manvr3d.currentTimepoint)
        }

        spotScaleFactor = addLabeledSpinner(
            "Spot Scale Factor",
            SpinnerNumberModel(1f, 0.1f, 10f, 0.1f)
        ) { value ->
            manvr3d.geometryHandler.sphereScaleFactor = value.toFloat()
            if (manvr3d.isVRactive) {
                manvr3d.vrTracking.cursor.visualScale = value.toFloat()
            }
            manvr3d.redrawSciviewSpots()
        }

        linkScaleFactor = addLabeledSpinner(
            "Link Scale Factor",
            SpinnerNumberModel(1f, 0.1f, 10f, 0.2f)
        ) { value ->
            manvr3d.geometryHandler.linkScaleFactor = value.toFloat()
            manvr3d.geometryHandler.showInstancedLinks()
        }

        vrResolutionScale = addLabeledSpinner(
            "VR Window Resolution scale",
            SpinnerNumberModel(0.75f, 0.1f, 2f, 0.1f)
        ) { value ->
            manvr3d.setVrResolutionScale(value.toFloat())
        }

        // Adding dropdowns for link LUTs and volume colors
        val linkColorChoices = mutableListOf("By Spot")
        val availableLUTs = Colormap.list().toMutableList()
        linkColorChoices.addAll(availableLUTs)

        // Link colors dropdown
        linkColorSelector = JComboBox(linkColorChoices.toTypedArray())
        linkColorSelector.addActionListener(chooseLinkColormap)
        linkColorSelector.setSelectedItem("plasma")
        windowPanel.add(JPanel(MigLayout("fillx, insets 0", "[right][grow, fill]")).apply {
            add(JLabel("Link & Uncertainty colors:"), "gapright 10")
            add(linkColorSelector, "wrap")
        }, "span, growx")

        uncertaintyToggle = JCheckBox("Show ELEPHANT Uncertainty")
        uncertaintyToggle.isSelected = false
        uncertaintyToggle.addActionListener(toggleUncertainty)

        invertLutToggle = JCheckBox("Invert LUT")
        invertLutToggle?.isSelected = false
        invertLutToggle?.addActionListener(toggleLutInversion)

        windowPanel.add(JPanel(MigLayout("fillx, insets 0")).apply {
            add(uncertaintyToggle, "gapright 10")
            add(JLabel("Low:"), )
            add(uncertaintyLowSwatch, "w 12, h 12, gapright 5")
            add(JLabel("Mid:"), )
            add(uncertaintyMidSwatch, "w 12, h 12, gapright 5")
            add(JLabel("High:"))
            add(uncertaintyHighSwatch, "w 12, h 12, gapright 5")
            add(invertLutToggle!!, "dock east, gapleft 10")
        }, "span, growx")

        // Volume colors dropdown
        volumeColorSelector = JComboBox(availableLUTs.toTypedArray())
        volumeColorSelector.addActionListener(chooseVolumeColormap)
        volumeColorSelector.setSelectedItem("viridis")
        windowPanel.add(JPanel(MigLayout("fillx, insets 0", "[right][grow, fill]")).apply {
            add(JLabel("Volume colors:"), "gapright 10")
            add(volumeColorSelector, "wrap")
        }, "span, growx")

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

        windowPanel.size = windowPanel.preferredSize
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
        logger.debug("Setting max mipmap level to $level")
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
                manvr3dContext.geometryHandler.currentColorMode = GeometryHandler.ColorMode.SPOT
                logger.info("Coloring links by spot color")
            }

            else -> {
                linkColorSelector.selectedItem?.let {
                    manvr3dContext.geometryHandler.currentColorMode = if (manvr3dContext.showUncertainty) {
                        GeometryHandler.ColorMode.UNCERTAINTY
                    } else {
                        GeometryHandler.ColorMode.LUT
                    }
                    manvr3dContext.geometryHandler.setLUT(it.toString())
                    logger.info("Coloring links with LUT $it")
                }
            }
        }
        updateLutSwatches()
        manvr3dContext.rebuildGeometry()
    }

    val chooseVolumeColormap = ActionListener {
        volumeColorSelector.selectedItem?.let {
            val cm = Colormap.get(it.toString())
            manvr3dContext.volumeNode.colormap = cm
            logger.info("Coloring volume with LUT ${volumeColorSelector.selectedItem}")
        }
    }

    val toggleSpotsVisibility = ActionListener {
        val spots = manvr3dContext.volumeNode.getChildrenByName("SpotInstance").first()
        val newState = !spots.visible
        spots.visible = newState
        manvr3dContext.isSpotVisible = newState
    }
    val toggleVolumeVisibility = ActionListener {
        val newState = !manvr3dContext.volumeNode.visible
        manvr3dContext.setVolumeOnlyVisibility(newState)
    }
    val toggleTrackVisibility = ActionListener {
        val links = manvr3dContext.volumeNode.getChildrenByName("LinkInstance").first()
        val newState = !links.visible
        links.visible = newState
        manvr3dContext.isTrackVisible = newState
    }

    val toggleUncertainty = ActionListener {
        manvr3dContext.showUncertainty = uncertaintyToggle.isSelected
        if (uncertaintyToggle.isSelected) {
            manvr3dContext.geometryHandler.currentColorMode = GeometryHandler.ColorMode.UNCERTAINTY
        } else {
            manvr3dContext.geometryHandler.currentColorMode = GeometryHandler.ColorMode.LUT
        }
        manvr3dContext.rebuildGeometry()
    }

    val toggleLutInversion = ActionListener {
        logger.debug("Setting LUT inversion to ${invertLutToggle?.isSelected}")
        manvr3dContext.invertLut = invertLutToggle?.isSelected ?: false
        if (uncertaintyToggle.isSelected) {
            manvr3dContext.rebuildGeometry()
        } else {
            manvr3dContext.geometryHandler.updateLinkColors()
        }
        updateLutSwatches()
    }

    val autoAdjustIntensity = ActionListener {
        manvr3dContext.autoAdjustIntensity()
    }

    private fun updateLutSwatches() {
        val isInverted = invertLutToggle?.isSelected ?: false
        val selectedLUT = linkColorSelector.selectedItem?.toString()
        if (selectedLUT == "By Spot") return

        selectedLUT?.let {
            val cm = Colormap.get(it)
            val lowColor = if (isInverted) cm.sample(1.0f) else cm.sample(0.0f)
            val midColor = cm.sample(0.5f)
            val highColor = if (isInverted) cm.sample(0.0f) else cm.sample(1.0f)

            uncertaintyLowSwatch.background = lowColor.toAwt()
            uncertaintyMidSwatch.background = midColor.toAwt()
            uncertaintyHighSwatch.background = highColor.toAwt()
            uncertaintyLowSwatch.repaint()
            uncertaintyHighSwatch.repaint()
        }
    }

    fun updatePaneValues() {
        val manvr3d = this.manvr3dContext ?: throw IllegalStateException("Manvr3d context is null.")
        val updVolAutoBackup = manvr3d.updateVolAutomatically
        //temporarily disable because setting the controls trigger their listeners
        //that trigger (not all of them) the expensive volume updating
        manvr3d.updateVolAutomatically = false

        spotScaleFactor.value = manvr3d.geometryHandler.sphereScaleFactor
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
        this@Manvr3dWindowLayout.manvr3dContext = null

    }

    init {
        this.manvr3dContext = manvr3dContext
        windowPanel = populateThisContainer
        populatePane()
    }
}