> English: [../../adr/0001-toolchain-and-module-layout.md](../../adr/0001-toolchain-and-module-layout.md)

# ADR-0001：工具鏈與模組配置

日期：2026-09-06 · 狀態：accepted

## 決策

- Gradle 9.7.1 wrapper、AGP 9.4.0（內建 Kotlin、新版 DSL）、Kotlin 2.4.10、KSP 2.3.11、Hilt 2.60.1、
  Room 2.8.4、Compose BOM 2026.08.00（ui/foundation 1.12.0）、Navigation 3 1.1.7、WorkManager 2.11.2、
  DataStore 1.2.1、Tink 1.23.0、SQLCipher for Android 4.18.0 搭配 androidx.sqlite 2.7.0。
- compileSdk 37（由 androidx.core 1.19／compose 1.12／sqlcipher 4.18 的 AAR metadata 強制要求），
  targetSdk 36（計畫 §4 基準），minSdk 26。
- 慣例外掛（convention plugin）以預編譯腳本外掛的形式放在 `build-logic/`（`quietinbox.kotlin.jvm`、
  `quietinbox.android.library`、`.library.compose`、`.hilt`、`.feature`）。
- `core:*` 與 `parsers:apps` 是 `kotlin("jvm")` 模組，在 JUnit Platform 上使用 Kotest；Android
  模組使用 JUnit4 + Kotest 斷言，讓真機測試（instrumented）也能共用相同的 matcher。

## 理由

計畫要求 parser／identity／reconcile／analytics 的邏輯能在不依賴 Android 的 JVM 上執行，並且只有單一個
加密的 SQL 資料層。AGP 9 的內建 Kotlin 移除了 `kotlin-android` 外掛；模組層級的
`kotlin { compilerOptions { … } }` 仍可運作，並用於 opt-in。在建立完整配置之前，已先建置並安裝了一個
三模組的試作（spike）。

## 後果

- `-Xjvm-default=all` 在 Kotlin 2.4 已消失（`-jvm-default` 只接受 `enable|no-compatibility|disable`）；預設值已足夠。
- KSP 2.3.11 以 Kotlin 2.3.20 為目標，但用 2.4.10 也能正常編譯；若出現 KSP 不相容，應把 Kotlin 降版到 2.3.x，而不是釘住 KSP。

## 附錄（2026-09-06）：一個 `:parsers:apps` 模組取代五個

計畫原本把 `:parsers:line`、`:parsers:whatsapp`、`:parsers:telegram`、`:parsers:instagram`、
`:parsers:messenger` 列為各自獨立的模組，另外還有 `:tools:fixture-publisher`、`:tools:replay-cli` 與
`:benchmark`。v0.1 把五個 adapter 放在單一的 `:parsers:apps` 模組裡：它們共用同一個 `AppParser` 基底，
其候選項 hook 是 `final`，所以 adapter 只能覆寫 `appSingleCandidates` 與 `postProcess`，讓每個 App 的
表面積小而好審。拆成五個模組等於為各約 60 行程式碼多開五個 Gradle 模組，卻換不到隔離上的好處（它們全部
都是 `SYNTHETIC_ONLY`）。工具與 benchmark 模組尚未建置；見 `docs/SCOPE.md`。
