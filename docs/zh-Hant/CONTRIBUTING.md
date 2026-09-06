> English: [../../CONTRIBUTING.md](../../CONTRIBUTING.md)

# 貢獻指南

感謝你的協助。以下幾條規則讓這個專案保持誠實，也讓它的使用者安全。

## 基本規則
- **不得使用逆向工程取得的素材。** 不要貢獻取自任何其他 App、或取自關於這些 App 的研究報告的規則
  清單、字典、常數、反編譯程式碼或 fixture。請描述行為、自己撰寫啟發式規則，並以合成 fixture
  （`core:testing` DSL）涵蓋它們。
- **不得使用真實訊息。** fixture 與問題回報都必須是合成的。如果你需要分享真實的通知結構，
  請先把每一個名稱、id、URI 與內容替換成佔位文字。
- **要證據，不要宣稱。** 修改解析器的 PR 必須包含 fixture 測試；宣稱有真機結果的 PR 必須包含裝置、
  OS build、來源 App 版本，以及 `docs/TEST_MATRIX.md` 中的情境編號。「可以編譯」不等於「完成」。
- **維持不變量。** 不使用 `INTERNET`、不對來源通知採取動作、不跨串流合併、不靜默刪除使用者資料、
  記錄與診斷中不得出現內容文字。

## 工作流程
1. Fork 並從 `main` 開分支。
2. `./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test :parsers:apps:test`
3. `./gradlew :app:assembleDebug && tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk`
4. 儲存、加密或備份相關的變更：在裝置上執行 CI 會跑的那三套
   `./gradlew :platform:storage:connectedDebugAndroidTest :platform:crypto:connectedDebugAndroidTest :platform:backup:connectedDebugAndroidTest`。
5. 開一個填好檢查清單的 PR。加密、schema 以及身分／去重的變更需要第二位審查者，並且要更新 ADR。

## 風格
- Kotlin 官方風格、120 欄寬、結尾逗號。
- 每一個使用者可見的字串都要放進全部五份目錄——`values`（英文）、`values-b+zh+Hant`、`values-b+zh+Hans`、
  `values-ja`、`values-ko`——名稱、佔位符與 plurals 都要一致；`python3 tools/check-strings.py`（同時也是 CI
  的關卡）必須通過。
- 顏色絕不是狀態的唯一訊號；請加上文字 + 圖示。

## 維護者
暫定維護者：<aa22396584@gmail.com>（安全問題請見 `SECURITY.md`）。App 以 `dev.quietinbox.app` 發行
（Gradle namespace 為 `dev.quietinbox`）並已在 Google Play 上架，套件 id 已固定；名稱本身尚未做過商標清查。
