> English: [../../adr/0002-material3-expressive-alpha.md](../../adr/0002-material3-expressive-alpha.md)

# ADR-0002：採用 1.5.0 alpha 線的 Material 3 Expressive

日期：2026-09-06 · 狀態：accepted（在第一個 1.5.0 stable 時重新檢視）

## 背景

Compose BOM 2026.08.00 所提供的穩定版 `androidx.compose.material3:material3:1.4.0` 並未包含 Expressive
元件組：`LoadingIndicator`、`ButtonGroup`、`FloatingToolbar`、`FloatingActionButtonMenu`、
`LinearWavyProgressIndicator` 都不在它的類別清單中，而 `MaterialExpressiveTheme` 是 `internal`。此結論
由檢視 `material3-1.4.0.aar` 驗證（`MaterialThemeKt` 以 internal 可見度公開 `MaterialExpressiveTheme`；
沒有 `LoadingIndicatorKt`）。

## 決策

把 material3、material3-window-size-class 與 material3-adaptive-navigation-suite 覆寫為
`1.5.0-alpha27`，同時讓 ui/foundation 維持 BOM 提供的穩定版 1.12.0（該 alpha 相依於
1.12.0-beta01，因此 1.12.0 可滿足需求）。

## 後果

- 使用到的 Expressive 元件：`MaterialExpressiveTheme` + `MotionScheme.expressive()`、
  `LoadingIndicator`、`LinearWavyProgressIndicator`、`HorizontalFloatingToolbar`、
  `MediumFlexibleTopAppBar`／`LargeFlexibleTopAppBar`、`MaterialShapes` 裁切。
- 此 alpha 的 `ShortNavigationBar`／`WideNavigationRail` 在裝置上只繪出一個項目；
  因此改用穩定版的 `NavigationBar`／`NavigationRail`。下一個 alpha 再重新測試。
- Alpha API 可能變動；所有呼叫端都透過
  `-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi` 明確 opt-in。
