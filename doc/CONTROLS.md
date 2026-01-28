# The mangling of pixels before they are displayed in sciview

> The controls in this document are outdated. Please refer to the controls documentation in the main [Readme](../README.md) instead.

> Note: part of the volume transformations described here are not supported anymore,
> because I need to figure out how update the data in real-time even for larger datasets.

What originally was a technical necessity, the full copy of the relevant pixel data (that is, pixels
from the chosen resolution for the currently displayed time point) into one re-used memory
allows us to modify the data (pixels) before they are displayed.
This enabled us to implement *gamma correction* and other value transformations.

An illustrative screenshot of the [Mastodon freely available example project with pixel data](https://github.com/mastodon-sc/mastodon-example-data)
could look like this:
![A screenshot of the Mastodon public demo data shown with this project](example_data.png)

> Note: volume coloring used to be a feature, but was turned off for the time being and converted to a backlog feature due to its increased memory constrains.

## Gamma correction, and more...
First we need to understand what happens to pixel values on their way to the screen:

- The original pixel values are linearly stretched, i.e. multiplied by "contrast factor".
- The values are then shifted by adding a constant "shifting bias".
- The values are then clipped to an interval <0 ; "not_above">,
  so basically any value above this "not_above" threshold is set to the threshold value.
- If gamma value is different than 1.0, then
  - because of the nature of the [gamma correction](https://en.wikipedia.org/wiki/Gamma_correction) computing,
    the pixel values need to normalized into an interval <0 ; 1>, and
  - the values are then raised to the power "gamma level" (which leaves them in the interval <0 ; 1>), and
  - are stretched back to the original full interval < 0 ; "not_above">.

In this way, the pixel values are guaranteed to be between 0 and the "not_above" threshold,
occupying a certain low-band from the full displayable capacity <0 ; 65535>.

This information could be useful for the shortcuts middle section of the Controls panel,
in particular for the "Volume intensity mapping" range slider. The job of this slider is to
define how the pixels will be mapped onto the screen:

- All pixel values below the lower/left side of the slider will be mapped to black.
- All pixel values above the higher/right side of the slider will be mapped to white.
- Pixel values in between will be proportionally mapped to the shades of gray (from black till white).

It seems natural then to set the lower/left side of the slider to 0, and the higher/right part
of it to the "not_above" threshold. However, useful pixel intensities are usually in an even
narrower band and thus this slider is here to fine tune.

---

Btw, to adjust the sliding range (which is noted on the right with the two numbers above each other)
of the slider itself, press `Ctrl` and place mouse pointer to the right half of the slider, then press
left mouse button and keep holding it while moving the mouse pointer left-right to adjust the higher/right
side bound of the slider. Using left half of the slider area will control the lower/left bound of the slider.


