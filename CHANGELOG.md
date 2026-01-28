# Changelog

## [0.3] - 2026-01-29

### Added

- Add radius tool with adjustable size to select cells, and scale the cells up or down ([#26](https://github.com/scenerygraphics/manvr3d/pull/26))
- Add recursive branch deletion by holding the delete button for 0.5s ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Add multiselection with radius tool, selection painting by dragging is possible ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Add async graph updates through worker queue ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Add redo button ([#26](https://github.com/scenerygraphics/manvr3d/pull/26))
- Add buttons to toggle spot/link/volume visibility ([#26](https://github.com/scenerygraphics/manvr3d/pull/26))
- Add button to merge overlapping spots with connections ([#26](https://github.com/scenerygraphics/manvr3d/pull/26), [#27](https://github.com/scenerygraphics/manvr3d/pull/27))
- Add button to merge selected spots ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))
- Add button mapping manager that automatically applies different button layouts based on HMD brand ([#633](https://github.com/scenerygraphics/sciview/pull/633))
- Add experimental annotation-by-gaze-clustering algorithm for eye tracking hardware ([#633](https://github.com/scenerygraphics/sciview/pull/633))
- Add buttons to delete all spots in a timepoint or the whole graph ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))
- Add button to reset dataset transforms and view ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))
- Add resolution scaling spinner to UI ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))
- Enabling VR automatically scales the sciview window to match the HMD resolution ([#622](https://github.com/scenerygraphics/sciview/pull/622))

### Removed

- Remove volume processing spinners from the UI ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))

### Changed

- Unify start/stop VR button in UI ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Prevent VR startup without HMD connected ([#26](https://github.com/scenerygraphics/manvr3d/pull/26))
- Change default state of eye tracking UI toggle to "off" ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))
- Automatically fall back to default VR when no eye tracking hardware was found ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))

### Fixed

- Always include whole time range when performing nearest-neighbor linking ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Fix timepoint wrapping between TP 0 and max TP ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Fix track merging behavior ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Fix polarity of tracks sent to Mastodon ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Fix spot radius representation in sciview by calculating the geometric mean for each spot ([#25](https://github.com/scenerygraphics/manvr3d/pull/25))
- Fix invisible spot selection after deletion ([#26](https://github.com/scenerygraphics/manvr3d/pull/26))
- Fix scene setup after reverting from VR to default sciview ([#633](https://github.com/scenerygraphics/sciview/pull/633))
- Greatly reduce CPU load when many instances are present ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))
- Fix race condition when selecting spots by mouse in sciview ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))
- Fix adjacent link updates while moving a spot in VR ([#27](https://github.com/scenerygraphics/manvr3d/pull/27))

## [0.2] - 2025-04-30

_First packaged release._

### Added

- Add links from Mastodon inside sciview ([#13](https://github.com/scenerygraphics/manvr3d/pull/13))
- Use instanced geometry for faster rendering ([#13](https://github.com/scenerygraphics/manvr3d/pull/13))
- Add instance selection behavior through clicking ([#14](https://github.com/scenerygraphics/manvr3d/pull/14))
- Add spot moving and scaling ([#14](https://github.com/scenerygraphics/manvr3d/pull/14))
- Initial support for eye tracking ([#16](https://github.com/scenerygraphics/manvr3d/pull/16))
- Add mipmap selection in UI ([#16](https://github.com/scenerygraphics/manvr3d/pull/16))
- Add cell tracking with VR controller, including manual select/move/delete/add behavior ([#18](https://github.com/scenerygraphics/manvr3d/pull/18))

### Removed

- Remove three-color handling of volumes in favor of LUT colors ([#12](https://github.com/scenerygraphics/manvr3d/pull/12))

### Changed

- Change volume loading to use source and converter types ([#13](https://github.com/scenerygraphics/manvr3d/pull/13))
- Streamline keyboard handler registrations ([#13](https://github.com/scenerygraphics/manvr3d/pull/13))
- Exhhange Mastodon API with ELEPHANT API ([#15](https://github.com/scenerygraphics/manvr3d/pull/15))
- Use Miglayout instead of Gridbag layout for GUI ([#17](https://github.com/scenerygraphics/manvr3d/pull/17))

## [0.1]

_First prototype version of a sciview-Mastodon bridge._