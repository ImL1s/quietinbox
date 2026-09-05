# ADR-0002: Material 3 Expressive from the 1.5.0 alpha line

Date: 2026-09-06 · Status: accepted (revisit at the first 1.5.0 stable)

## Context

The stable `androidx.compose.material3:material3:1.4.0` shipped by Compose BOM 2026.08.00 does not
contain the Expressive component set: `LoadingIndicator`, `ButtonGroup`, `FloatingToolbar`,
`FloatingActionButtonMenu`, `LinearWavyProgressIndicator` are absent from its class list and
`MaterialExpressiveTheme` is `internal`. Verified by inspecting `material3-1.4.0.aar` (`MaterialThemeKt`
exposes `MaterialExpressiveTheme` with internal visibility; no `LoadingIndicatorKt`).

## Decision

Override material3, material3-window-size-class and material3-adaptive-navigation-suite to
`1.5.0-alpha27` while keeping ui/foundation on the stable 1.12.0 from the BOM (the alpha depends on
1.12.0-beta01, so 1.12.0 satisfies it).

## Consequences

- Expressive components used: `MaterialExpressiveTheme` + `MotionScheme.expressive()`,
  `LoadingIndicator`, `LinearWavyProgressIndicator`, `HorizontalFloatingToolbar`,
  `MediumFlexibleTopAppBar` / `LargeFlexibleTopAppBar`, `MaterialShapes` clips.
- `ShortNavigationBar` / `WideNavigationRail` from this alpha rendered only one item on device;
  the stable `NavigationBar` / `NavigationRail` are used instead. Re-test on the next alpha.
- Alpha APIs can change; all call sites opt in explicitly via
  `-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi`.
