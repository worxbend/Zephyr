# Zephyr visual system

## Direction and scope

Zephyr remains a Compose Desktop application. Its visual language draws from modern GTK/libadwaita, Ubuntu/Yaru, and Lomiri/Suru. This is a design translation, not native GTK integration or a replacement of the SDKMAN/application architecture.

The design uses a quiet window background, raised rounded content surfaces, clear page titles, an adaptive navigation sidebar, grouped settings, and restrained warm accents. Monospace belongs to commands and logs, not general interface chrome. Avoid terminal frames, neon gradients, decorative telemetry, and redundant badges.

Existing actions, transaction review, protection checks, shortcuts, appearance preferences, and reduced-motion behavior remain the behavioral contract. Narrow layouts must keep actions available, not silently hide them.

## Research basis

The following official references informed the design:

- [GNOME header bars](https://developer.gnome.org/hig/patterns/containers/header-bars.html): limit controls, group related actions, use start/center/end alignment, prefer flat header buttons, and provide tooltips.
- [GNOME sidebars](https://developer.gnome.org/hig/patterns/nav/sidebars.html): navigation sidebars are appropriate when there are more destinations than a view switcher can comfortably hold.
- [GNOME boxed lists](https://developer.gnome.org/hig/patterns/containers/boxed-lists.html): group settings semantically, distinguish row titles from subtitles, and keep row controls focused.
- [GNOME scaling and adaptiveness](https://developer.gnome.org/hig/guidelines/adaptive.html): start from constrained sizes, use breakpoints and maximum content widths, and preserve functionality across sizes.
- [Libadwaita styles and appearance](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/styles-and-appearance.html): use semantic colors, support light and dark appearances, and treat custom-rendered controls as an explicit accessibility responsibility.
- [Ubuntu color palette](https://design.ubuntu.com/brand/colour-palette): orange, white, and warm gray form the Ubuntu palette; aubergine is a restrained supporting accent.
- [UBports human interface guidelines](https://docs.ubports.com/en/latest/humanguide/): Suru emphasizes precise structure, visual rhythm, and progressively exposed information as space increases.
- [UBports system palette](https://docs.ubports.com/en/latest/humanguide/design-concepts/system-palette.html): semantic positive, negative, and neutral actions; orange for branding and emphasis.

Ubuntu brand colors are inspiration, not an accessibility exemption. Foreground/background pairs use contrast-adjusted accent variants rather than assuming white text on raw Ubuntu orange is readable at every size. No Ubuntu or GNOME affiliation is implied.

## Tokens

`ZephyrTheme.kt` owns palettes, typography, shapes, density metrics, and motion preference.

- Primary: contrast-adjusted Ubuntu-inspired orange for actions, focus, and selection.
- Secondary: aubergine for secondary emphasis, not competing primary actions.
- Window: warm off-white in light mode, charcoal in dark mode.
- Surfaces: white/raised charcoal, with subtle boundaries rather than heavy outlines.
- Feedback: `LocalZephyrColors` provides separate success, warning, and info roles for each appearance. Its `onSuccess`, `onWarning`, and `onInfo` colors pair with their corresponding containers.
- Error: Material color-scheme error roles.
- Typography: system sans-serif, bold headings, readable subtitles; existing text scaling remains supported.
- Shapes: small controls, medium cards, larger dialogs. Rounded does not mean every element becomes a pill.
- Motion: brief, user-driven color feedback. Reduced motion switches immediately and uses static progress semantics.

Component files must not introduce literal brand or status colors. Use the theme roles, including disabled, selected, focused, success, warning, error, and informational states.

## Shared components

- `ZephyrPanel` and `ZephyrClickablePanel`: cards and grouped containers; interactive cards expose hover, press, and keyboard-focus feedback.
- `PageTitle` and `ZephyrSectionHeading`: page and section hierarchy.
- `ZephyrNavigationItem`: accessible selection and visible focus.
- `ZephyrToolbarButton`: shared enabled/disabled/focus feedback.
- `SearchField`: visible focus, readable placeholder, accessible clear action.
- `ZephyrSegmentedControl`: semantic single selection, wrapping options, keyboard focus.
- `ZephyrSettingsRow`: side-by-side or stacked label/control layout according to available space and text size.
- `Badge` and `StatusDot`: text/symbol cues as well as color.
- `ZephyrProgressIndicator`: compact progress and regular linear progress, with reduced-motion fallback.
- `CodeBlock`: selectable, monospace command/output surface.
- `ZephyrKeycap`: subdued shortcut hint.
- Notifications: constrained-width raised surfaces, semantic severity, wrapping action placement.

Keep shared component APIs small. Do not create a parallel general-purpose UI framework or move business logic into visual components.

## Validation expectations

Run the project `check` task after integration. Verify contrast for light and dark text/container pairs, selection and disabled semantics, keyboard focus, settings at large text sizes, and constrained layouts. Capture real Compose renders using hermetic test fixtures; screenshots must not invoke a user's SDKMAN installation or mutate their preferences.

The design does not claim native system-accent synchronization, automatic high-contrast integration, or full phone support. Those require separate platform work and validation. The desktop window manager still owns the native title bar and window controls.

### Executed validation

- The integrated `check` task passed: 193 tests, no failures, errors, or skips.
- `ZephyrVisualDesignTest` adds eight tests covering light/dark feedback contrast, 360-pixel settings at 200% application text scale, selection, disabled activation, and Tab/Shift-Tab/Enter/Space behavior.
- Six real Compose PNG captures are generated under `shared/build/reports/visual-design/`: light/dark shared controls at 360 and 1200 pixels, and light/dark settings at 360 pixels with 200% application text scale. Fixtures are labeled TEST and use memory-only state.
- Captures check frame stability, not golden-image equivalence. Component tests do not establish whole-application accessibility, real SDKMAN integration, or every route at every size.
- `git diff --check` passed. Backend, ViewModel, dependencies, and persistent settings implementations were not changed.
