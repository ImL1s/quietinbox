# 範圍、完成定義與誠實的缺口

> English: [docs/SCOPE.md](../SCOPE.md)（英文版為準；本文隨每次變更同步翻譯）

計畫（`QuietInbox_開源專案完整計劃`，2026-09-05）描述的是 10–14 週的 v1.0。本文記錄這個 repository **今天實際交付了什麼**、以及**沒有交付什麼**。「能編譯」不等於「完成」（計畫 §16）；下面每一列都寫明證據。

## 本里程碑的完成定義（計畫 §3「v0.1」+ §18）

| 項目 | 狀態 | 證據 |
| --- | --- | --- |
| 授權 onboarding | 完成 | SM-S9280 / Android 16 實機走過（畫面：範圍 → 來源 → 授權 → 測試 → 預覽） |
| 合成測試通知發送器（L2） | 完成 | `SyntheticNotifications`；實機擷取 3/3 則訊息 |
| NotificationListenerService 擷取、有界 snapshot | 完成 | `platform:capture`；callback 執行緒上沒有 DB／網路／解碼 |
| 多訊息 parser（MessagingStyle / Inbox / BigText / summary） | 完成 | `core:parser` 10 個 JVM 測試 |
| 不跨串流合併的身分判定 | 完成 | `core:identity` 5 個 JVM 測試 |
| 去重（`AMBIGUOUS_REPEAT`、revision、過期視窗處理、resync 視為重貼） | 完成 | `core:reconcile` 20 個 JVM 測試，含兩個 1,000 次迭代的 property test（§7.2 的六個例子加上關閉視窗的歧義重複都是字面測試案例） |
| 加密金庫（Room + SQLCipher、每次安裝隨機金鑰、Keystore 包裝） | 完成 | 真機測試 `VaultRoundTripTest` + `MigrationTest`（1→2、2→3）+ `KeystoreWrapperTest`（序列化的 KEK 建立）；`KeystoreWrapper` 設定 `setUserAuthenticationRequired(false)` |
| Journal-first commit；撤權／暫停／來源變更／維護時的 commit 圍籬 | 完成 | `CaptureCoordinator`：等鎖前與鎖內各一次 admission 圍籬、寫入前的 commit 圍籬；來源政策變更在鎖內；`CaptureCoordinatorTest`（16） |
| 「刪除全部」是經驗證的獨佔維護執行；cipher 快取綁定金鑰 epoch | 完成 | `VaultMaintenance`、`VaultRepository.deleteEverything` → `ResetResult`；`VaultMaintenanceTest`（4）、真機 `DeletionGraphTest`；AVD 上實際走過重設 |
| 刪除圖與讀取時到期（journal payload 清空、媒體列／檔案隨訊息刪除、投影重算、到期副本隱藏） | 完成 | `DeletionGraphTest`（5，真機） |
| 帶品質標籤的收件匣／對話 UI | 完成 | 實機截圖 |
| 搜尋（CJK 二元組 + 拉丁三元組、參數化、keyset 分頁、驗證到頁面填滿） | 完成 | 真機 `VaultRoundTripTest`（開會 / hel）與 `SearchPagingTest`（250 筆假陽性候選、可續的游標）；實機 UI |
| 冷啟動 fail closed：來源政策未知前不讀任何通知 | 完成 | `CaptureCoordinatorTest`（原封保留、金庫打不開時以 `COLD_START` 缺口丟棄）；AVD 冷啟動後的合成擷取 |
| 搜尋與對話頁面的鎖定／開啟中金庫 | 完成 | `SearchViewModelTest`（2）、`ConversationViewModelTest`（1）；未在裝置上演練（AVD 無法隨時把金庫鎖上） |
| 活動洞察（僅觀測：概觀、熱區圖、排行、最佳時段、好聊度、神隱率、emoji、口頭禪） | 完成 | `core:analytics` 32 個 JVM 測試加 `AnalyticsViewModelTest` 8 個（狀態規則、非主執行緒計算、鎖定／開啟中金庫）；實機 UI；每個期間最多載入 50,000 則（超過時每個分頁都顯示提示） |
| 擷取健康頁（缺口與診斷） | 完成 | 實機 UI |
| 保留期限 TTL worker | 完成（未做 soak 測試） | `RetentionWorker`，12 小時週期 |
| 媒體複製（content:// + 通知 bitmap，加密） | 已實作，**未經裝置驗證** | `MediaCopier`；尚無測試碰過真實 content URI |
| 帶復原金鑰的加密備份匯出／匯入（在維護閘門內、分頁匯出、部分媒體回報） | 完成（模擬器） | `BackupService` + HKDF RFC 向量；`BackupStagerTest`（21 個 JVM 測試）；API 36 AVD 上的真機 `BackupRoundTripTest`（匯出 → 清空 → 匯入、排除到期副本、回報略過的媒體、媒體以現行金鑰解密）；尚未在裝置上走 SAF 選檔流程 |
| 自己的提醒（預設關閉、DST 安全的本地時間、只在有未查看時） | 已實作，**未經裝置驗證** | `ReminderSchedulerTest`（5 個 JVM 測試：`delayUntilNext`、`ReminderPolicy`）；worker 本身尚無裝置測試 |
| UI 鎖（BiometricPrompt）、截圖保護 | 已實作，**部分驗證** | 在加入 debug 專用豁免前，已驗證 FLAG_SECURE 會擋掉 `screencap`；生物辨識流程未演練 |
| 示範模式（僅 debug 版） | 完成 | `DemoDataRepository` 位於 `platform:storage` 的 `debug` source set，藏在 `DemoData` 介面後；release 綁定 no-op，其 dex 不含任何示範類別或文字（以 `strings` 檢查 `classes.dex`）；真機 `DemoDataTest` |
| 沒有 INTERNET 權限 | 完成 | debug APK 的 `aapt2 dump permissions`；CI 的 `tools/check-permissions.sh` |
| 在地化：en、zh-Hant、zh-Hans、ja、ko（UI 字串、App 內語言清單、商店文案） | 完成 | `core/designsystem/res/values*`、`app/res/xml/locales_config.xml`、`fastlane/metadata/android/{en-US,zh-TW,zh-CN,ja-JP,ko-KR}`；CI 的 `tools/check-strings.py` 保證每份目錄完整（名稱、佔位符、複數形）；示範金庫也在地化（`DemoLocalisation`，debug source set）；三種新語言由專案內部翻譯、在第 18 輪審查並在 API 36 AVD 上檢查，尚未經母語審閱 |
| 發行：付費 Google Play + 免費 GitHub Releases，同一個二進位（ADR-0006） | 完成（Play：0.1.2 已送審；GitHub：0.1.2） | `versionCode` 6 / 0.1.2（新增三種語言、第 13–25 輪審查）是 GitHub release `v0.1.2`（tag 在 `f6afecd`，簽章 APK + `SHA256SUMS.txt`），也是 2026-09-06 以 `gplay` 用同一份 CI 建置的 AAB 送出的 Play production 版本（單一 edit：bundle、en-US / zh-TW / zh-CN / ja-JP / ko-KR 商店文案、截圖、更新說明；取代了仍在審查中的 0.1.0）。`versionCode` 5 / 0.1.1（審計修正，issue #1–#16）只在 GitHub 發行（`v0.1.1`，tag 在 `c6b6645`）；`versionCode` 4 / 0.1.0 是第一次送 Play（付費、172 個國家、無 INTERNET 權限、無 Play Billing）。tag → `release.yml` 建置簽章 APK + `SHA256SUMS.txt` 並跑權限閘門；商店文案與圖片放在 `fastlane/metadata/android/`，截圖參考版在 `docs/screenshots/` |
| 自適應版面（寬視窗的 rail + list-detail） | 完成（模擬器） | Foldable_Test AVD（API 36，2076×2152）：`NavigationRail` + `ListDetailSceneStrategy` 讓收件匣與對話並排；手機顯示底部列 |

## 未完成（計畫 v1.0 中明確不在本里程碑的項目）

- **真實來源 E2E（L3）**：LINE / WhatsApp / Telegram / Instagram / Messenger 各用兩個同意的測試帳號。五個 adapter 都是 `SYNTHETIC_ONLY`；沒有從真實 App 觀測到任何東西。見 `docs/COMPATIBILITY.md`。
- **72 小時 soak、OEM 矩陣、API 26 lane、16 KB page-size 驗證**（計畫 §15）。本機只演練過一台實機（Samsung SM-S9280，Android 16）與一台可摺疊模擬器（API 36）；CI workflow 定義了 API 29/35 的模擬器 lane，但尚未執行過。
- **發行簽章、可重現建置比對、SBOM、F-Droid 上架**（計畫 §17）。`assembleRelease` 已設定 R8，但 repo 內沒有 keystore。
- **密碼式備份（Argon2id）**、高安全的鎖定金庫模式、遠端設定的規則更新、聯網媒體版本——依計畫皆為 P2。
- **開啟來源 App 時重用原始通知的 `PendingIntent`**：v0.1 一律退回 launcher intent（snapshot 刻意不保留任何 `PendingIntent`）。
- **每個 profile 的來源控制與非空的 account key**（審計 #8）：來源只以套件為鍵，會話身分裡的 `accountKey` 可為 null。改成 `NOT NULL` 需要重建 `conversation` 表（`message` 的 FK 指向它），延後到之後的 schema 版本；收件匣會標記工作設定檔的會話，`docs/COMPATIBILITY.md` 記錄其限制。
- **到期與 retention 掃除之間的會話清單計數**（審計 #7）：到期副本在所有讀取處都於讀取時隱藏，但會話列的 `messageCount` 只在刪除、掃除或還原時重算，所以一列可能比頁面總數多 1，直到下一次掃除（最多 12 小時）。
- **還原會重設到期時間**（審計 #7 / #16）：備份永遠不含製作當時已到期的副本；還原較舊的備份會刻意給它的副本一段新的保留期（整體審查要求舊備份不要在下一次執行就被掃掉）。因此還原是刻意的「把它帶回來」，不會是意外。
- **刪除抑制以 fingerprint 為鍵**（審計 #9，第 11 輪）：多則同 fingerprint 的已刪訊息共用一個 token，所以同一 post 的重播會整體被抑制（正確），但同一 post 內真正新的同 fingerprint 訊息也會被抑制；每個 id 一個 token 需要 schema v4。
- **尚未進 CI 的治理項目**（審計 #12）：detekt / ktlint、CodeQL、SBOM、覆蓋率門檻、可重現建置與 commit 簽章。每一項都會增加依賴或維護者端的金鑰；已追蹤，未開始。
- **parser 變更的 golden corpus diff 報告**（計畫 §14）：fixture 是 Kotest 案例，尚無獨立的 corpus 工具。
- **帶去識別化預覽的診斷包匯出**（計畫 §14）：目前只有不含本文的剪貼簿摘要。
- **名稱／商標／套件 id 清查**：`dev.quietinbox` 是佔位名稱。

## 第 1 輪審查（2026-09-06）：第一次推送前修正的發現

四位獨立審查者（透過 agy 的 Gemini 3.8 Flash high、Claude subagent、Claude Fable 5；Codex 與 Kimi 受用量限制阻擋——見 `docs/reviews/`）回覆 REQUEST CHANGES。每個 Critical 與 Important 發現都已修正，且在單元測試能表達之處補上覆蓋：

- `DatabaseHolder.db()` 在呼叫端等待期間金庫變成 Locked 時不再懸掛。
- 刪除抑制改以 scope + identity 為鍵（DB v2，明確遷移），所以刪除整個會話能撐過活動通知的重播。
- 暫停／停用來源會輪換 generation，並在 `process()` 中再次檢查；consumer 迴圈遇到任何 throwable 都會重啟；在飛的 bitmap 有上限。
- Journal 重播與即時處理共用同一把 mutex；失敗的 commit 最多 3 次維持 PENDING；FK 安全的觀測連結。
- Reconciler 對齊整個視窗（id 作為 override），在過期重播時保留 checkpoint，並把同一 post 的 resync 視為重貼。
- 還原把媒體 blob 連結到還原後的訊息，只對既有列去重，並限制每一行、記錄數、暫存媒體位元組與暫存文字總量。它只讀取選定的文件。
- 匯出先把完整密文寫到私有暫存檔，只在最後複製時才開啟選定的文件，因此在那次複製之前的失敗不會動到既有目標（複製本身失敗仍可能讓它被截斷；錯誤文字會說明）。
- 金鑰檔以 資料 fsync → rename → 目錄 fsync 寫入（`Os.fsync`；java.io 無法開啟目錄），目錄 fsync 失敗會回報而不是交出未經證實的金鑰。遺失的 Keystore 金鑰回報為已失效，而不是鑄造新的。

第 2 輪（對那些修正的再審，`docs/reviews/2026-09-06-round2/`）沒有 Critical，三個 Important 全部修正：單一歧義重複縮小了 checkpoint 視窗（下一個 post 可能重複兩則訊息）、目錄 fsync 是無聲的 no-op、兩處文件宣稱跑在程式碼前面。其 Minor 清單也修了：checkpoint 遺失的防護對每個既有列各連結一列，而不是把一批壓在單一 id 上；已不存在的視窗 id 寫成 null 而不是往前帶；擷取管線會重新拋出 `CancellationException`；斷線的 session id 會被清除；還原暫存限制文字總量。
- App 鎖在設定未知前保持關閉；搜尋查詢用 3-gram／單一 CJK 字元，所以「hell」找得到「hello」、「開」找得到「開會」。

## 裝置驗證時發現的已知缺陷與粗糙處

- material3 1.5.0-alpha27 的 `ShortNavigationBar` / `WideNavigationRail` 只渲染一個項目；改用 `NavigationBar` / `NavigationRail`（見 ADR-0002）。
- 擷取頁的管線計數是每個程序各自的（重啟即歸零）；持久化的 journal 計數另外顯示。
- Android 11+ 的套件可見性需要 `<queries>`；未列在其中且沒有 launcher activity 的來源可以用精確的套件名稱新增。
- 還原會在原子合併前把整個備份暫存在記憶體。上限（2,000,000 筆記錄、16M 字元文字、以 base64 文字持有的 256 MB 媒體）只是名目值：刻意做大的檔案可能在觸發上限前就耗盡 heap，而真的超過這些上限的金庫也無法還原。把暫存串流到暫存檔是 v1.0 的項目。在計數核對通過之前金庫本身絕不會被碰。
- 冷啟動期間、第一份來源清單載入前，第三方通知只以框架物件保留（不從中讀取任何東西；最多 256 則，最舊的先淘汰）；一次淘汰、15 秒內沒開的金庫、跨越斷線／暫停／維護仍被保留的可擷取來源通知（除非重連的 resync 再次提供了同一個 post）、或當時鎖定金庫記不下來的遺失，都會在金庫可寫時寫成一筆有界的 `COLD_START` 缺口，而且只有寫入成功後才會忘掉（審計 #13）。
- `closeWindow` / `closeAllWindows` 在管線 mutex 之外執行，所以一次關閉可能與進行中 commit 的 checkpoint upsert 競爭（同 post 時間規則把影響限制在可能多一個 `AMBIGUOUS_REPEAT` 標籤）。
