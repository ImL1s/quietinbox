> English: [../TEST_MATRIX.md](../TEST_MATRIX.md)

# 測試層級與目前的涵蓋範圍

層級依循計畫 §15。工具與產品分開看待：fixture DSL 與合成發布器是測試工具，絕不是關於真實來源 App 的
證據。

| 層級 | 判準（Oracle） | 現有內容 | 執行方式 |
| --- | --- | --- | --- |
| L0 契約與 fixture | 手寫的預期值 | `core:testing` Fixtures DSL；每個解析器測試都是一個帶有明確預期批次的合成 fixture | JVM |
| L1 JVM 重播 | 純 Kotlin 的 parser／identity／reconcile／analytics | `core:*` 44 個測試（model 5、parser 10、identity 5、reconcile 20、analytics 4）、`parsers:apps` 43 個、`app` 4 個（提醒）；兩個 1,000 次迭代的性質測試（property test）：不同的內容必須恰好被接受一次（seed 20260905）、沒有 id 的重複內容絕不可重複，且重播絕不可縮小視窗（seed 20260906） | `./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test :parsers:apps:test` |
| L2 Android 發布器 | 透過自家 package 產生的真實通知回呼 | `SyntheticNotifications`（MessagingStyle、BigText）；引導流程步驟 4 | 裝置、手動 |
| L3 真實來源 App | 兩個知情同意的測試帳號、錄影紀錄 | **未執行** | — |
| L4 故障與效能 | 終止 process、Doze、首次解鎖、撤銷、磁碟上限 | **未執行**（程式中已有 commit 圍籬（generation）；尚無注入故障的測試） | — |
| L5 發行產物 | 合併後的 manifest、權限傾印、可重現建置 | `tools/check-permissions.sh`（CI）；尚無 SBOM／重建比對 | CI |
| 真機測試（instrumented）儲存 | 真實的 SQLCipher + Room 遷移 | `VaultRoundTripTest`（日誌 → commit → 搜尋 → 抑制 → 以持久化的金鑰重新開啟）、`MigrationTest`（對照匯出的 schema 執行 1→2）、`DemoDataTest`（示範資料寫入 → 筆數與投影 → 重複寫入不重複 → 清除後不留痕跡） | `./gradlew :platform:storage:connectedDebugAndroidTest` |
| 真機測試（instrumented）加密 | 在真實檔案系統上的持久化金鑰寫入 | `WrappedSecretFileTest`（資料 fsync → 更名 → 對目錄執行 `Os.fsync`；只建立一次、讀回、巢狀目錄） | `./gradlew :platform:crypto:connectedDebugAndroidTest` |
| 加密 | RFC 5869 測試向量、codec 來回轉換 | `HkdfTest`、`RecoveryKeyCodecTest` | JVM |

## 計畫所引用的情境編號（已實作成測試的子集）

| 計畫範例（§7.2） | 測試 |
| --- | --- |
| `[A] → [A,B] → [A,B,C]` ⇒ A B C | `ReconcilerTest` "yields exactly A B C" |
| 只有 `[A,B,C]` ⇒ 三則訊息 | "stores all three, not just C" |
| `[A,B,C] → [B,C,D]` ⇒ A B C D | "keeps A B C D" |
| `[好(id=1), 好(id=2)]` ⇒ 兩則 | "are two messages" |
| `[好(?)] → [好(?)]` ⇒ 可能重複 | "is an ambiguous observation" |
| `[A,B,C]` 之後收到舊的 `[A]` ⇒ 保留 B C | "does not delete B C" |
| 已關閉的 `[A,B,C]` → `[C]`（新的發布）→ `[B,C,D]` ⇒ B C 為已知，只有 D 是新的 | `ReconcilerAmbiguousKeepTest` |

## 示範模式（僅 debug 版本）

`DemoDataRepository`（`platform:storage`）會在資料庫中填入明顯屬於合成的內容，讓 app 不必暴露任何真實
通知就能展示、走查與截圖。它會寫入三個以 `demo.quietinbox.` 為前綴的虛構來源、八個虛構對話（雙語標題、
一個置頂、一個封存、涵蓋三種身分可信度），以及約 130 則分布在最近 30 天、以晚間為高峰的訊息，另外還有
一個已結束的擷取工作階段、兩段中斷區間、三筆診斷事件與兩筆僅摘要觀測。刻意包含的項目：一組
`AMBIGUOUS_REPEAT` 與其觀測連結、一則帶有前一版內容的修訂訊息、一張 `PLACEHOLDER_ONLY` 圖片、一則預覽
受限的內容、自己發出的訊息、長文、emoji 與 URL，讓介面上每個誠實標籤都有對應的資料列。資料列的形狀完全
比照 `IngestRepository.commit`（相同的 fingerprint、排序鍵規則與搜尋斷詞），因此示範走的是真正的讀取路徑。

其中沒有任何真實成分：所有姓名、群組、品牌與 App 都是虛構的，也從未讀取任何來源通知。

- 從 adb 觸發（僅 debug APK；receiver 位於 `app/src/debug`）：
  ```bash
  adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op seed \
      -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver
  adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op clear \
      -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver
  ```
- 從 app 觸發：設定 → 開發者 →「填入示範資料」／「移除示範資料」。此區塊只有在注入的 `BuildInfo.debug`
  為 true 時才會繪製，因此在 release 版本中並不存在。
- `seed()` 具有冪等性（會先清除），`clear()` 只依示範標記刪除 —— `demo.quietinbox.` 套件前綴與 `demo-`
  擷取世代 —— 已擷取的副本不受影響。沒有 schema 變更：只新增查詢，不新增資料表或欄位。
- 截圖工具：`tools/demo-screenshots.sh <adb-serial> <en-US|zh-TW> <out-dir>` 會安裝 debug APK、清除 app
  資料、授予監聽器與 `POST_NOTIFICATIONS`、以雙語按鈕文字走完引導流程、寫入示範資料並拍攝
  `1_inbox.png … 7_inbox_dark.png`。請使用模擬器：在真機上監聽器會把使用者自己的通知複製進 debug 資料庫。
- 覆蓋範圍：`DemoDataTest`（真機測試，`platform:storage`）寫入示範資料、驗證各畫面讀取的筆數與對話投影、
  確認重複寫入不會產生重複資料，接著清除並驗證不留下任何示範資料列。因為 SQLCipher 的原生函式庫無法在
  JVM 載入，此測試需在裝置上執行（`./gradlew :platform:storage:connectedDebugAndroidTest`）。

## 量化目標（計畫 §15）—— 狀態

所有數值目標（回呼 p95 < 10 ms、commit p95 < 500 ms、10 萬列資料上的搜尋 p95 < 300 ms、
72 小時 soak）都**尚未測量**。沒有執行過任何 benchmark 模組；計畫中的數值仍然是規劃門檻，不是結果。
