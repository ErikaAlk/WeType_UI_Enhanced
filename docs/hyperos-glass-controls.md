# HyperOS glass controls

## Scope and sources

The Use system liquid glass switch owns glass, frost and highlight parameters.
With it off, no glass API is called and the XiaoAI `keyboard/frosted` background is
preserved. Enabling it fills absent blur radii with 60 / 16 px and absent bloom values
with the module starter. Existing non-null parameters are preserved.
XiaoAI IME 0.2.910 does not provide a glass preset for this keyboard. The values
shown after enabling customization are an explicit module starting configuration,
not XiaoAI defaults and not a reproduction of the Figma screenshot.

Reference semantics: [Figma effect settings](https://help.figma.com/hc/en-us/articles/360041488473-Apply-effects-to-layers).
Figma defines depth as inward edge extent, dispersion as chromatic splitting,
and splay as projected light spread. Similar names do not imply matching numeric
scales or rendering formulas.

Native evidence is from the connected phone's `/system/lib64/libhwui.so`:
SHA-256 `5d22d01296e300204df2d386d72ae7dba026e429da2597713ac4d6c56b2611ad`.
Addresses below are ELF virtual addresses in this build, not runtime pointers.
The binary and full proprietary shader are not included in the repository.
These are private ROM APIs; future ROMs may change their layout.

## Mapping (zero-based Java array indices)

| Control | `setMiGlass(float[])` | `setGlassBloom(float[])` | Conversion / meaning |
| --- | --- | --- | --- |
| Light angle | 25, 26; elevation 27 | 2, 3; elevation 4 | Azimuth `atan2(x, y)` in degrees; changing it rotates xy and preserves their length and z. Native code normalizes xyz. |
| Light intensity | 28 | 5 | UI percent / 100. The opposite light is a separate raw field (29 / 6), preserved. |
| Refraction | 32 | None | IOR, not Figma's 0–100 refraction value. The shader uses `refract(..., 1 / IOR)`. |
| Depth | 19 | 0 | `shapeEdgePx`: width of the inward edge in physical pixels. |
| Dispersion | None identified | None identified | No independent RGB refraction offsets in the inspected glass shader. Disabled in the UI. Bloom and shadow dispersion are not chromatic dispersion. |
| Splay | 30 | 7 | Light cone range. Raw fraction multiplied by PI by the renderer; UI degrees / 180. |
| Optical thickness (advanced) | 21 | None | `shapeThicknessPx`, independently changes refraction displacement. This is not Figma Depth. |
| Edge curve (advanced) | 20 | 1 | Normal profile exponent; renderer uses its reciprocal. |

`setMiGlassBlurRadius(small, large)` is the glass pipeline's pair of integer blur
radii, in physical pixels. Both are exposed under Frost. Neither is converted
from a Figma value. The existing 40 dp XiaoAI frosted radius belongs to a different
API (`setMiBackgroundBlurRadius`) and is not changed by these two sliders.

`setMiViewMaterialType(0)` selects the blur path; `1` selects glass. Enabling the
liquid glass switch enables the 42-value glass array, blur radii, bloom and type 1.
Disabling that switch removes all four overrides. The raw editor retains the
three parameter arrays; renderer selection belongs to the switch. Old independent
blur/bloom settings remain dormant when the glass array is absent.

## Native trace

- `nSetMiGlass` at `0x984d88`: exactly 42 Java floats are copied unchanged into
  the first 168 bytes of `MiGlassFilterParam`.
- Uniform upload beginning at `0xa46e98`: offsets `0x48,0x4c,0x54,0x58` become
  alpha, edge width, thickness and reflection offset.
- At `0xa46edc`: offset `0x80` becomes the IOR component of
  `uRefractIORStrengthLight_clrpow`.
- At `0xa46ffc`: offsets `0x64,0x68,0x6c` form the normalized light direction.
- At `0xa47470`: offset `0x78` is multiplied by PI for the light range.
- At `0xa47528`: offsets `0x70,0x74` become the two light intensities.
- `nSetGlassBloom` at `0x9849cc`: at least 12 Java floats; optional slots 12–15
  are copied if present. The native struct has a leading float before Java
  element 0. Consequently native byte offsets must be divided by four **and
  decremented by one** to get the Java index.
- Bloom uniform upload at `0xa3fd18` uses native offset 4 for edge width;
  `0xa3fe30` uses offsets 12,16,20 for direction; `0xa3ffa0` uses offset 24 for
  intensity; `0xa40008` uses offset 32 for the light range.
- Type at native render state offset `0x3bc` is dispatched at `0xa2f9ec`.
  Type 1 selects material implementation 5/6. Implementation 5's draw method
  `0xa44b28` calls glass shader setup `0xa465dc` at `0xa44dc8`.

## Editing and validation

The slider ranges are conservative editor bounds, not claimed native API limits:
angle 0–359 degrees, intensity 0–100%, IOR 1–2.5, edge 1–100 px, splay 1–180
degrees, thickness 1–200 px, edge exponent 0.1–4, blur 0–400 px.
The original interface editor remains folded below the sliders for parameters
that have no simple Figma counterpart and for old custom arrays. Slider edits preserve
all other entries, including optional bloom extensions. Previously saved values
outside a slider range remain unchanged unless that slider is moved.

Preference storage remains the existing indexed scalar format. Reset glass parameters selects the module preset and keeps liquid glass enabled.
The switch restores XiaoAI defaults when disabled by recreating the dedicated
material view without optional native overrides. Preview refresh waits 120 ms after the last edit to avoid
repeated native view creation and fallback-tint flashes while dragging.

Unit tests cover native slot mapping, unit conversion, azimuth rotation, preserved
unknown fields, independent optional overrides, renderer selection, default reset
and preference round trips. Build/sign/install are separate from visual acceptance,
which is performed by the user.

## Brightness and preview regression correction

`adjustColor` in the ROM shader adds `brightness * alpha` to RGB. Both array
slot 6 (foreground brightness) and slot 34 (background brightness) therefore
have neutral value **0**, while saturation slots 5 and 33 have neutral value 1.
The previous module starter mistakenly used 1 for all four fields. Reading
preferences migrates only that exact old starter, preserving other overrides and
any intentionally different raw configuration.

A local raster test compiled the complete extracted ROM glass shader with Skia,
using an opaque two-dimensional color gradient and an interior SDF sample. The
old brightness offsets produced 100% pure-white pixels. Corrected offsets produced
0% pure-white pixels, RGB range 0–254 and standard deviation 65.53. This confirms
the shader cause without claiming Android compositor or device visual acceptance.

The settings preview now uses a native ImageView backdrop followed by the native
material panel in the same FrameLayout, with same-window blur sampling. The live
keyboard still samples behind the IME window. Pass-window admission uses the
ViewRoot's Context.getBasePackageName, matching the framework, because a themed
module Context can report a different identity from its host window.

## Optical outline and light semantics

The scalar slot mapping is unchanged. Glass `processLighting` calls `hsvv`, a
luminance-dependent brightening operation; it is not Figma's reflected highlight.
The glass UI now labels these controls Edge brightening and Brightening angle.
The separate bloom controls keep their own names.

Native `0xa44d14` calls outline-radius extraction `0xa42820`, then stores its
result at render-state offset `0x4b8` (glass parameter offset `0xa8`). Extraction
supports native round rectangles and paths recognizable as round rectangles;
an arbitrary G2 curve falls through to radius zero. The shader normal cache is
only radius + 3 pixels wide, and its normal.xy fades to zero at radius - 1.
A zero radius therefore destroys edge refraction; its distance channel can also
classify the entire clamped interior as edge, producing global brightening.

The glass draw stores only the first radius returned by outline extraction, and
its shader mirrors one normal cache into all four corners. Passing the largest
radius therefore incorrectly assigns the hardware bottom radius to the custom
top corners. The keyboard uses the configured top radii and WindowInsets hardware
bottom radii; only the settings preview has square bottom corners.

The current implementation uses one complete optical child, with no internal
region clipping. Its one native radius follows the configured top corners. The
parent still clips the visible outline using the module's four continuous corner
radii, including hardware bottom corners. The native optical model therefore does
not independently follow the hardware bottom radius or reproduce the exact G2
normal field. The preview moves its rounded optical bottom below its square clip.
Edge depth is capped to the top radius's available normal cache.

The earlier partitioned implementation used separately clipped top/bottom material
passes to supply different radii. Device acceptance revealed a visible center
seam. Disjoint output clips do not guarantee continuous native backdrop sampling
or light/color calculations between independent passes. That implementation and
its region planner have been removed rather than masked with overlapping output.
Zero-sized or extremely small top corners still cannot provide a substantial
refraction band with this system model.

A local Skia test compiled both the extracted ROM normal-cache shader and the
glass shader, using a gradient backdrop and a clamped corner cache. With radius
0, IOR 1 to 1.5 changed the edge region by mean 0.026/255, while changing light
strength changed the center by 41.20/255. With radius 64, the same IOR change
changed the edge by 2.94/255 and the center light delta was exactly 0. These tests
exclude the corner mask to isolate refraction and illumination; final corner
appearance and Android compositor behavior remain device acceptance items.

## Unified controls and saved background tint

Glass, frost and bloom sliders use the same SliderPreferenceItem as the other
module preferences: a title/value row followed by a full-width slider. Dividers
separate the switch, slider block, raw controls and the other appearance options.
The three explanatory paragraphs and the separate frost, advanced and bloom
switches have been removed. Highlight slider labels are prefixed to distinguish
them from glass edge-brightening controls.

In liquid-glass mode, the saved light/dark background color drives the native
shader's final RGB mix, including the saved alpha. Native `0xa46f50` uploads
`uGlassColor` from offset `0x2c` (Java slots 11–14). `0xa475fc` uploads the final
mix factor from offset `0x90` (slot 36) as `uSatBri_burn_unshade.w`. Runtime copies
receive straight RGB in slots 11–13 and saved alpha in slot 36; slot 14 is zero
to avoid the shader's separate multiplicative tint. Other raw values and the
stored arrays are preserved. No XiaoAI blend colors are added in this mode.

Both preview and keyboard pass their current mode's saved color to the shared
material helper. Color participates in the style cache and is reapplied on optical geometry
updates. With liquid glass disabled, the XiaoAI light/dark blends and
shadow/highlight configuration remain unchanged.

## Selected preset and disclosure arrows

The module preset uses glass edge depth 50 px, thickness 160 px, edge brightening
50%, IOR 2.0 and blur radii 60 / 16 px. Bloom uses depth 30 px, light Y 0.99999994
and intensity 10%; the remaining supplied array values are retained exactly.
The UI fallback and native material share these defaults. Existing custom arrays
are not replaced during loading. The historical white-preset migration remains
pinned to its original array so it cannot rewrite newly selected values.

Raw interface values uses the same 10 by 16 dp right arrow and action color as
ArrowPreference. Its disclosure arrow animates from 0 to 90 degrees over 200 ms
and tracks the actual editor visibility, including forced expansion for invalid
input. Reset glass parameters uses ArrowPreference directly.

## Screen-edge alpha coverage

The native glass shader multiplies output alpha by an inner-edge AA factor:
`uNmlZAAThreshold = smooth5_vertical(3 / edgeDepth, 0.5)`. Aligning its optical
bounds exactly to the display leaves partially transparent edge pixels even
though the carrier has no layout margin. The live keyboard now extends every
optical surface three physical pixels left, right and down. The outer continuous
outline, keyboard layout bounds and the top edge stay fixed.
The local settings preview keeps its existing bounds. The outset comes from the
ROM shader's AA width, not device density or a measured screen coordinate.

An isolated raster check used the extracted normal-field and glass shaders,
a constant opaque background, and the native AA threshold. Straight left, right
and bottom edge alpha changed from 20/255 to 255/255 with the three-pixel outset;
top-edge alpha remained unchanged. A white corner mask isolated straight-edge AA;
this does not constitute Android compositor or physical-device visual validation.

## Single-surface seam correction

The compositor now receives exactly one glass child covering the entire keyboard.
No top/bottom clipBounds or half-panel wrappers remain. Changing hardware bottom
radii cannot change the optical surface geometry; changing the configured top
radius updates its native outline and available normal depth. The left/right/
bottom three-pixel coverage correction is retained. Geometry regression tests
cover these constraints, preview extension, and extreme top radius values.

A separate ROM path was investigated: blur type 2/3 with material type 1 selects
implementation 6 (`0xa2f9fc`, factory `0xa4575c`, draw `0xa44f58`). It uses a
full-shape normal texture and the shader at `0x52282`, rather than the mirrored
corner shader at `0x94004`. Its alpha formula fades over
`min(edgeDepth, 25 * (1 - tintAlpha * 0.5))` in shaped-distance units, so it is not
a drop-in geometry change preserving the current rendering. The seam correction
keeps the established renderer. Device appearance remains user acceptance.
