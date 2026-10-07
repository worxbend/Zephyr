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
- [Ubuntu Yaru](https://github.com/ubuntu/yaru): Ubuntu's community-maintained desktop theme is a visual reference, not a new runtime dependency.
- [UBports human interface guidelines](https://docs.ubports.com/en/latest/humanguide/): Suru emphasizes precise structure, visual rhythm, and progressively exposed information as space increases.
- [UBports system palette](https://docs.ubports.com/en/latest/humanguide/design-concepts/system-palette.html): semantic positive, negative, and neutral actions; orange for branding and emphasis.
- [Lomiri convergence](https://docs.ubports.com/en/latest/humanguide/other-design-considerations/convergence.html): adapt presentation to available space while preserving access to the same functionality and input paths.

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
- `ZephyrHeaderButton` and `ZephyrSymbol`: flat, labeled, keyboard-operable symbolic actions with tooltips. Original vector drawings replace letter tiles in navigation without adding an icon dependency.
- `ZephyrScrollPane`: a visible, theme-aware desktop scrollbar with its own gutter, used by Overview and Settings.
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

Run the project `check` task after integration. Verify contrast for light and dark text/container pairs, selection and disabled semantics, keyboard focus, settings at large text sizes, and constrained layouts. Keep component fixtures hermetic. For whole-app visual verification, use isolated preferences and a read-only mount of the SDKMAN installation: visual inspection must not grant write access to real toolchains. Use a private display if desktop input targeting is unreliable or the user is interacting with another instance.

The design does not claim native system-accent synchronization, automatic high-contrast integration, or full phone support. Those require separate platform work and validation. The desktop window manager still owns the native title bar and window controls.

### Executed validation

- The integrated `check` task passed: 196 tests, no failures, errors, or skips.
- `ZephyrVisualDesignTest` contains eleven tests covering light/dark contrast (including suggested actions, neutral text, and selection-outline color pairs), actual rendered scrollbar-thumb contrast, 360-pixel settings at 200% application text scale, selection, disabled activation, Tab/Shift-Tab/Enter/Space behavior, labeled header actions, and scroll reachability at large text sizes.
- Six real Compose PNG captures are generated under `shared/build/reports/visual-design/`: light/dark shared controls at 360 and 1200 pixels, and light/dark settings at 360 pixels with 200% application text scale. Fixtures are labeled TEST and use memory-only state.
- Captures check frame stability, not golden-image equivalence. Component tests do not establish whole-application accessibility, real SDKMAN integration, or every route at every size.
- `git diff --check` passed. Backend, ViewModel, dependencies, and persistent settings implementations were not changed.

### Whole-application screenshot iteration

The production `MainKt` application was launched, not a replacement mock UI. An initial desktop capture exposed excessive outlines, letter-based navigation, a crowded header, and a three-tile dashboard wrapping into two rows. The refresh replaces those with a centered header, original symbolic controls, neutral Adwaita-like surfaces, grouped preferences, and weighted dashboard columns. A second rendering pass identified inconsistent pill-shaped quick actions and poor scroll discoverability; these were replaced with consistent suggested/secondary buttons and visible scrollbars.

Independent review then identified insufficient contrast in translucent scrollbar thumbs and the search-selection border. Both now use opaque semantic foreground colors. A pixel-sampling regression test verifies the rendered idle scrollbar exceeds 3:1 in both themes; selection-outline pairs are included in the contrast checks. The application was rebuilt and relevant captures regenerated after these fixes.

Final screenshots live in `desktopApp/build/reports/ui-review/`, with an `index.html` gallery. They cover light/dark Overview and Settings, installed JDKs, search with keyboard selection, narrow navigation, and 200% text with actual scrolling. Final whole-app verification used a private Xvfb display, the compiled production entry point, isolated Java preferences/home, and Bubblewrap read-only host mounts with networking disabled. A scratch-only AWT input bridge exercised the actual controls; it is not part of the application. Offline/unknown-availability messages are expected in this sandbox, not fabricated online data.

The production window has an existing 800×600 minimum, left unchanged. Whole-app narrow captures are 800 pixels wide; the 360-pixel captures remain component fixtures, not a claim of full-phone support. Captures were inspected visually, not compared against golden baselines. This verifies the sampled routes and interactions, not every route, assistive-technology integration, or live mutation workflow.
