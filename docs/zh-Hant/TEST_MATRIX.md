> English: [../TEST_MATRIX.md](../TEST_MATRIX.md)

# 測試層級與目前的涵蓋範圍

層級依循計畫 §15。工具與產品分開看待：fixture DSL 與合成發布器是測試工具，絕不是關於真實來源 App 的
證據。

| 層級 | 判準（Oracle） | 現有內容 | 執行方式 |
| --- | --- | --- | --- |
| L0 契約與 fixture | 手寫的預期值 | `core:testing` Fixtures DSL；每個解析器測試都是一個帶有明確預期批次的合成 fixture | JVM |
| L1 JVM 重播 | 純 Kotlin 的 parser／identity／reconcile／analytics | `core:*` 76 個測試（model 5、parser 10、identity 5、reconcile 22（含 `ReconcilerIdAlignmentTest`）、analytics 34）、`parsers:apps` 43 個、`app` 5 個（提醒）；兩個 1,000 次迭代的性質測試（property test）：不同的內容必須恰好被接受一次（seed 20260905）、沒有 id 的重複內容絕不可重複，且重播絕不可縮小視窗（seed 20260906） | `./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test :parsers:apps:test` |
| L2 Android 發布器 | 透過自家 package 產生的真實通知回呼 | `SyntheticNotifications`（MessagingStyle、BigText）；引導流程步驟 4 | 裝置、手動 |
| L3 真實來源 App | 兩個知情同意的測試帳號、錄影紀錄 | **未執行** | — |
| L4 故障與效能 | 終止 process、Doze、首次解鎖、撤銷、磁碟上限 | **未執行**（程式中已有 commit 圍籬（generation）；尚無注入故障的測試） | — |
| L5 發行產物 | 合併後的 manifest、權限傾印、可重現建置 | `tools/check-permissions.sh`（CI）；尚無 SBOM／重建比對 | CI |
| 設計系統 | 頭像單字 | `MonogramTest`（4 個：漢字、假名、諺文名字取一個字，拉丁名字取兩個縮寫，空白給 `?`） | `./gradlew :core:designsystem:testDebugUnitTest` |
| 字串目錄 | 每個語系都必須帶有預設目錄的所有名稱、佔位符與複數形 | `tools/check-strings.py`（`core:designsystem` 與 `platform:capture` 的 en、zh-Hant、zh-Hans、ja、ko）；Android lint 的 `MissingTranslation` 也是錯誤 | `python3 tools/check-strings.py`（CI：Assemble + permission gate） |
| 真機測試（instrumented）儲存 | 真實的 SQLCipher + Room 遷移 | `VaultRoundTripTest`（日誌 → commit → 搜尋 → 抑制 → 以持久化的金鑰重新開啟；刪除的會話在重播後不會復活）、`MigrationTest`（對照匯出的 schema 執行 1→2 與 2→3）、`DemoDataTest`（示範資料寫入 → 筆數與投影 → 重複寫入不重複 → 清除後不留痕跡；App 語言為 zh-Hans／ja／ko 時，寫入內容經 `DemoLocalisation` 在地化）、`DeletionGraphTest`（5 個：journal 離開 PENDING 即清空 payload；刪除最新訊息後重算投影；到期副本在 retention 跑之前就隱藏、跑之後投影重算；移除來源並刪資料後不留任何東西；刪除全部經驗證且沒有快取的 cipher 沿用舊金鑰）、`SearchPagingTest`（2 個：250 筆假陽性候選既藏不住真命中也不會讓頁面不足額，游標續頁不重疊；刪除 token 會抑制同一 post 的重播、但不抑制之後同文字的新 post）、`MediaExportBoundTest`（1 個：空表的 `maxId` 為 0，帶上限的匯出分頁排除快照之後才寫入的 blob）——共 16 個 | `./gradlew :platform:storage:connectedDebugAndroidTest` |
| 真機測試（instrumented）備份 | 真實金庫上的匯出 → 清空 → 匯入；維護閘門 | `BackupRoundTripTest`（2 個：備份只含看得見的副本、回報讀不到的媒體、還原後投影重算且媒體檔以目前金鑰可解密；exclusive 進行中的匯出會被拒絕） | `./gradlew :platform:backup:connectedDebugAndroidTest` |
| 真機測試（instrumented）加密 | 在真實檔案系統上的持久化金鑰寫入；KEK 建立競態 | `WrappedSecretFileTest`（資料 fsync → 更名 → 對目錄執行 `Os.fsync`；只建立一次、讀回、巢狀目錄）、`KeystoreWrapperTest`（全新 alias 下三把 secret 並行建立、五輪：只有一把 KEK，新的 wrapper 都能讀回） | `./gradlew :platform:crypto:connectedDebugAndroidTest` |
| 加密 | RFC 5869 測試向量、codec 來回轉換 | `HkdfTest`、`RecoveryKeyCodecTest` | JVM |
| 備份 staging | 還原讀取器的格式與上限強制 | `BackupStagerTest`（21 個測試：manifest 必須在第一筆、重複 manifest、不支援的版本、end 之後仍有資料、計數不符、每一項大小上限、未知記錄型別） | `./gradlew :platform:backup:testDebugUnitTest` |
| 擷取協調器 | 以 mock repository 驗證 commit 圍籬、冷啟動與維護 | `CaptureCoordinatorTest`（32 個測試：暫停後丟棄排隊事件、恢復時輪換 generation 與 session、來源清單載入後丟棄非來源套件、取消會傳播；等鎖期間被停用的來源事件永不寫入 journal、接受與 commit 之間的暫停讓事件維持 PENDING、暫停時不重播且恢復後重播、重播會丟棄來源已停用的列、維護執行會丟棄佇列並記錄精確缺口、每次維護都記錄自己的缺口；policy 未知前通知被原封不動地保留、只有來源會被建立 snapshot，金庫打不開時保留的通知被原封丟棄並記錄有界缺口；還在 copier 手上的 bitmap 仍計入佇列上限；保留緩衝溢位會記錄丟棄並只留下來源；journal 寫入拋例外會記為缺口；金庫鎖定期間無法記錄的冷啟動遺失與管線鎖定會在金庫開啟後補記為有界缺口；補記失敗的遺失會保留到下一次 policy 載入再寫、policy 載入後才落地的缺口列會立刻關閉、跨越斷線仍被保留的通知會得到自己的缺口，但來源已暫停或 resync 已再次擷取時不記；落在補記與旗標翻轉之間的缺口列由 policy 載入關閉；寫入失敗的溢位缺口會保留到下一次 policy 載入再寫；同 key 但 post 時間較舊的過期副本是獨立的遺失；釋放途中的斷線會給較晚的通知自己的缺口、而不是讓它被自己抑制；從未啟用的 app 只會被讀取套件名稱） | `./gradlew :platform:capture:testDebugUnitTest` |
| 儲存層邏輯（JVM） | 維護閘門順序、重設失敗分支、抑制規則 | `VaultMaintenanceTest`（5 個：work 正常執行並回傳；exclusive 進行中被拒絕；exclusive 會取消並等待進行中的 work；listener 即使在瞬間完成的 exclusive 也恰好各看到一次開始／結束；exclusive 之間與 pipeline 鎖持有者互相序列化）、`VaultRepositoryTest`（3 個：資料庫／媒體刪不掉時指出步驟、保留金鑰並重開金庫；正常路徑）、`SuppressionRuleTest`（4 個） | `./gradlew :platform:storage:testDebugUnitTest` |
| 搜尋／對話 ViewModel | 以 mock repository 驗證鎖定與開啟中的金庫 | `SearchViewModelTest`（2 個：鎖定時顯示鎖定且不執行查詢；開啟中輸入的查詢在就緒後執行、重試解鎖後再執行一次）、`ConversationViewModelTest`（1 個：開啟中維持載入、鎖定顯示鎖定、就緒顯示列） | `./gradlew :feature:search:testDebugUnitTest :feature:conversation:testDebugUnitTest` |
| 分析 ViewModel | 以 mock repository 驗證活動頁的狀態規則 | `AnalyticsViewModelTest`（8 個測試：首份報表不在收集端的執行緒計算、切換期間顯示乾淨的載入佔位（不帶上一期間的截斷標籤）、資料庫變動時安靜重算不顯示載入、保險庫鎖定時顯示鎖定並在解鎖後恢復、頁面開著時鎖定再解鎖且計數不變也會恢復、開啟中維持載入直到就緒、計數查詢失敗不會卡在載入、查詢失敗會把報表標示為可能不完整） | JVM |

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
- 截圖工具：`tools/demo-screenshots.sh <adb-serial> <en-US|zh-TW|zh-CN|ja-JP|ko-KR> <out-dir>` 會安裝 debug APK、清除 app
  資料、授予監聽器與 `POST_NOTIFICATIONS`、以雙語按鈕文字走完引導流程、寫入示範資料並拍攝
  `1_inbox.png … 7_inbox_dark.png`。請使用模擬器：在真機上監聽器會把使用者自己的通知複製進 debug 資料庫。 Android 13 以上鍵盤會跟著 App 語言走，所以工具在啟動 App 前先停用預設輸入法、之後還原，等輸入法穩定後一次一個字鍵入查詢、以搜尋欄的 ENTER 動作收起輸入法，並在搜尋欄沒有顯示該字串時拒絕拍搜尋頁；每張截圖至少 80 KB，對話頁截圖會等到釘選對話的標題出現，App 只在 per-app 語言請求確實生效後才啟動，而寫入示範資料的廣播會明確指定語言（`--es lang`）。
- 覆蓋範圍：`DemoDataTest`（真機測試，`platform:storage`）寫入示範資料、驗證各畫面讀取的筆數與對話投影、
  確認重複寫入不會產生重複資料，接著清除並驗證不留下任何示範資料列。因為 SQLCipher 的原生函式庫無法在
  JVM 載入，此測試需在裝置上執行（`./gradlew :platform:storage:connectedDebugAndroidTest`）。

## 尚未涵蓋

- 除了 `AnalyticsViewModel` 之外，`feature/*` 的 ViewModel 沒有 JVM 測試；活動頁的「保險庫已鎖定」狀態與
  「報表可能不完整」標籤有 `AnalyticsViewModelTest` 涵蓋，但尚未在裝置上實際走過。測試 harness 不會取消
  `viewModelScope`（每個測試各自擁有 ViewModel）。

## 量化目標（計畫 §15）—— 狀態

所有數值目標（回呼 p95 < 10 ms、commit p95 < 500 ms、10 萬列資料上的搜尋 p95 < 300 ms、
72 小時 soak）都**尚未測量**。沒有執行過任何 benchmark 模組；計畫中的數值仍然是規劃門檻，不是結果。
