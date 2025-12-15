# Mastodon-sciview Bridge (take2)
This is a reincarnation of [an earlier project `mastodon-sciview`](https://github.com/mastodon-sc/mastodon-sciview/) by [`xulman`](https://github.com/xulman) and [`RuoshanLan`](https://github.com/ruoshanlan).
It aims to bridge [Mastodon](https://github.com/mastodon-sc) with interactive 3D visualization in [sciview (and scenery)](https://github.com/scenerygraphics/sciview)
and extends it with [eye tracking](https://link.springer.com/chapter/10.1007/978-3-030-66415-2_18)-based cell tracking and other VR tracking/editing/exploration modalities.

The repository was started during the [scenery and sciview hackathon](https://imagesc.zulipchat.com/#narrow/stream/391996-Zzz.3A-.5B2023-06.5D-scenery.2Bsciview-hackathon-dresden)
in Dresden (Germany) in June 2023, where most of the code was contributed by [`xulman`](https://github.com/xulman). Samuel Pantze ([`smlpt`](https://github.com/smlpt/)) is the current maintainer
and extends the bridge with eye tracking, real-time data synchronization and VR interaction functionality.


> Attention: this project is under active development and some features currently rely on feature branches of both scenery and sciview. Some features might be hidden in feature branches.
> If in doubt, ask Samuel on [Zulip](https://imagesc.zulipchat.com/#narrow/channel/327470-Mastodon/topic/sciview.20bridge/with/507278423).

# How to compile
This project is now a **gradle build system project** with the official current content on the `master` branch.
It is a gradle project because scenery and sciview are gradle projects, and thus it was the most natural choice when developing or contributing to this project.

## Development
Since this regime is intended for development of this project and potentially of adding relevant functions in the sciview, which shall
be immediately accessible in this project, the gradle settings of this project is instructed to look for local sciview.
Therefore, the following layout is expected:

```shell
├── mastodon-sciview-take2
│   ├── build
│   ├── build.gradle.kts
│   ├── gradle
│   ├── gradlew
│   ├── gradlew.bat
│   ├── settings.gradle.kts
│   └── src
├── sciview
│   └── ...
└── scenery # (<- optional, for latest features)
    └── ...
```

(Put simply, both this and sciview repositories are next to each other, and also scenery if the latest features are desired.)

## Running

The easiest way to start currently (during active development) is to start Mastodon from Intellij IDEA by running the
[StartMastodon](../src/test/kotlin/org/mastodon/mamut/StartMastodon.kt) file. You can then create or open any project and
launch sciview from the menu via `Window -> New sciview`.

For quick testing you can also use the [StartSciviewBridgeDirectly.kt](../src/test/kotlin/org/mastodon/mamut/StartSciviewBridgeDirectly.kt) class  and point its file path at an existing Mastodon file, which will then be opened immediately.