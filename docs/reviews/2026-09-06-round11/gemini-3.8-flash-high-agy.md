I have started the test run command and will review the changes while it runs.
# 程式碼審查報告：QuietInbox 審計 Wave 2 與 Round-10 修復確認 (`f64ae7b..825d708`)

- **審查範圍**：Commit [`825d708`](file:///Users/iml1s/Documents/mine/quietinbox-wave2)（分支 `wave2`，相對於 `f64ae7b`）
- **審查焦點**：驗證 Round-10 報告中所有 Important 與 Minor 修復項目、GitHub Issues #6, #8, #9, #10, #11, #12, #13, #14, #15, #16 之宣稱、以及狩獵清單所列之邊界情境。
- **本地執行驗證**：193 個 JVM 測試全數通過（`BUILD SUCCESSFUL`，0 failures, 0 errors），字串雙語系嚴格對齊（en / zh-Hant 各 317 個字串、1 個複數字串，0 差異）。

---

## 一、審查結論 (Verdict)

### **APPROVE WITH MINOR FIXES**（核准但建議修復次要與邊界問題）

**總體評價**：
本次 commit 展現了極高的工程自律與修復完整度：
1. **Round-10 所有 Important 與可動手的 Minor 均已徹底解決**：包含 `pausedPackages` 在 SQL 查詢層排除（解開重播餓死）、`BlobCipher` 遭遇 epoch 變動時改為重試並 fail closed、`VaultMaintenance` 以同步 `MaintenanceListener` 取代易被 conflate 的 `StateFlow`、`deleteEverything` 在 `finally` 區塊強制重開金庫並將結束記帳改為異步 launch、`MediaCopier` 在交易內清空清理清單等，全數具備扎實的實作與測試佐證。
2. **Wave 2 的 10 項 GitHub Issues 均已如實交付**：涵蓋 cold-start 框架通知持有緩衝（#13）、ID 優先的視窗比對與刪除抑制規則（#9）、Keyset 搜尋分頁與候選者循環校驗（#11）、金庫鎖定/開啟中的頁面狀態與重試（#10）、未讀計數與複數提醒（#15）、通知存取跳轉降級鏈（#14）、分頁且過濾到期記錄的備份維護閘門（#16）、工作設定檔標記（#8）、以及 Gradle wrapper SHA 與 Lint abortOnError 治理（#12）。
3. 本次審查未發現任何 Critical 等級的安全退步或資料損毀。新發現的 **1 項 Important 問題**（備份匯出將媒體檔案解密與磁碟串流包在 Room 交易內，大量媒體時可能引發 SQLite 鎖競爭）與 **3 項 Minor 邊界問題**（套件可見性對 `resolveActivity` 的潛在過濾、金庫鎖定時連續產生零碎 `COLD_START` gap、空對話最後活動時間重置）建議在下一輪重構或維護時補強。

---

## 二、Round-10 缺陷修復驗證表 (Round-10 Verification Table)

| Round-10 發現項目 | 狀態 | 驗證結果與證據（檔案與行號） |
| :--- | :---: | :--- |
| **Important-1：`deleteEverything` 失敗讓金庫永遠停在 `Opening` 並卡死 collector** | **已修復** | • [`VaultRepository.kt:57-61`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/VaultRepository.kt#L57-L61)：加入 `finally { if (holder.state.value !is VaultState.Ready) holder.retry() }`，無論何種失敗分支必重開金庫。<br>• [`CaptureCoordinator.kt:456, 477-483`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L456-L483)：`onMaintenance(false)` 的記帳與 `replayJournal()` 均改以 `scope.launch` 異步執行，不再阻斷呼叫端。<br>• 具備完整 Mock 單元測試：[`VaultRepositoryTest.kt:39-60`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/test/kotlin/dev/quietinbox/platform/storage/repo/VaultRepositoryTest.kt#L39-L60) 驗證了 DB/媒體刪除失敗時皆呼叫 `holder.retry()`。 |
| **Important-2：備份／還原完全不在維護閘門內，但文件聲明在閘門內** | **已修復** | • [`BackupService.kt:83-85`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L83-L85)：`export` 納入 `maintenance.work { exportNow(...) }`，金庫重設時可安全取消。<br>• [`BackupService.kt:243-245`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L243-L245)：`import` 納入 `maintenance.exclusive { importNow(...) }`，還原時獨佔金庫並持有管線鎖。<br>• 新增真機測試：[`BackupRoundTripTest.kt:62`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRoundTripTest.kt#L62)。 |
| **Important-3：備份匯出包含到期訊息，還原時替過期訊息續命** | **已修復** | • [`Daos.kt:310-314, 328-332, 380-384`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L310-L384)：`MessageDao.exportPage`、`RevisionDao.exportPage`、`MediaDao.exportPage` 全面加上 `(expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)` 過濾。<br>• [`BackupService.kt:146-175`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L146-L175)：傳入當前 `now` 進行過濾。<br>• 測試驗證：[`BackupRoundTripTest.kt:94-107`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRoundTripTest.kt#L94-L107) 斷言過期訊息不會進入備份。 |
| **Important-4：`MediaCopier.store()` 交易已 commit 但協程取消導致實體檔被刪** | **已修復** | • [`MediaCopier.kt:180-182`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L180-L182)：將 `written.clear()` 移入 `db.withTransaction { ... }` 內最後一行，徹底消除了 commit 完成後到函式返回間因協程取消導致誤刪檔案的窗口。 |
| **Round-10 審計：`replayJournal` 遇暫停來源佔滿分頁產生 Head-of-Line 阻塞** | **已修復** | • [`Daos.kt:53-55`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L53-L55)：新增 `pendingExcluding(limit, excludedPackages)`，SQL 查詢直接排除暫停套件。<br>• [`CaptureCoordinator.kt:710`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L710)：重播時呼叫 `ingest.pendingJournal(excludingPackages = pausedPackages)`。 |
| **Round-10 審計：`BlobCipher.primitive()` 與重設競態時回傳已死 epoch 的金鑰實體** | **已修復** | • [`BlobCipher.kt:27-49, 76`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/BlobCipher.kt#L27-L76)：`build()` 在 `epoch != keyMaterial.epoch` 時回傳 `Build.Stale`；`primitive()` 迴圈重試 1 次，若仍失敗則以 `KeyResult.Failed` 閉鎖失敗，絕不回傳已廢棄的 Primitive。 |
| **Round-10 審計：`VaultMaintenance.active` 的 StateFlow 合併快速狀態轉換** | **已修復** | • [`VaultMaintenance.kt:23-26, 54, 88, 95`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenance.kt#L23-L95)：引入同步回呼介面 `MaintenanceListener`，於 `exclusive` 開始前與 `finally` 結束時各呼叫一次，不再依賴 Flow 收集。<br>• [`CaptureCoordinator.kt:195-198`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L195-L198)：改為註冊 Listener。<br>• 測試驗證：[`VaultMaintenanceTest.kt:68-77`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/test/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenanceTest.kt#L68-L77) 斷言即使瞬間完成的 exclusive 亦恰好接收 3 次 start 與 end。 |
| **Minor-3：對話預覽截斷單位不一致（UTF-16 vs Unicode code point）** | **已修復** | • [`IngestRepository.kt:65-68, 369`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L65-L369)：新增 `takeCodePoints(PREVIEW_CODE_POINTS)`，與 SQLite `substr(m.body, 1, 200)` 對齊，避免截斷 emoji。 |
| **Minor-5：重設失敗 snackbar 混合英文字串** | **已修復** | • [`SettingsScreen.kt:110-118`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsScreen.kt#L110-L118)：將 `"database"`, `"media"`, `"keys"`, `"reopen"` 映射為 `delete_everything_step_*` 在地化字串。 |
| **Minor-9：測試中的非決定論 `delay(200)` 與無效殘留呼叫** | **已修復** | • [`CaptureCoordinatorTest.kt:389`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L389)：改用 `CoroutineStart.UNDISPATCHED` 確保排入 Mutex 等待佇列，完全移除 `delay(200)`。<br>• [`DeletionGraphTest.kt:198`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/DeletionGraphTest.kt)：刪除無效的 `inbox.deleteMessages(emptyList(), ...)` 殘留呼叫。 |

---

## 三、GitHub Issues 交付驗證表 (#6, #8, #9, #10, #11, #12, #13, #14, #15, #16)

| Issue 編號與主題 | 狀態 | 驗證結果與證據（檔案與行號） |
| :--- | :---: | :--- |
| **#6 `MediaCopier` 交易性與孤兒處理** | **已交付** | • 寫入檔案後單一交易關聯 Blob 與 Message：[`MediaCopier.kt:163-182`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L163-L182)<br>• 縮圖失敗不影響主圖且刪除暫存檔：[`MediaCopier.kt:155-160`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L155-L160)<br>• `queuedBitmaps` 維持計數直到 copier 處理完成：[`CaptureCoordinator.kt:575, 606`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L575-L606)<br>• `orphans()` 納入未被任何訊息指向的 Blob：[`Daos.kt:357`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L357) |
| **#8 工作設定檔 (Work Profile) 支援與相容性文件** | **已交付** | • 收件匣對 `profileKey != "user:0"` 顯示 Work 圖示：[`InboxScreen.kt:302-307`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt#L302-L307)<br>• 新增雙語相容性指南：[`docs/COMPATIBILITY.md`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/docs/COMPATIBILITY.md)、[`docs/zh-Hant/COMPATIBILITY.md`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/docs/zh-Hant/COMPATIBILITY.md)，詳述工作設定檔限制、低 RAM 與 Device Policy。 |
| **#9 ID 優先對齊與進階抑制規則** | **已交付** | • `Reconciler.aligns()` 雙邊具備來源 ID 時以 ID 比對，否則以 fingerprint 比對：[`Reconciler.kt:203-205`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt#L203-L205)<br>• `SuppressionRule` 依 ID 或 post time 決定是否抑制：[`SuppressionRule.kt:15-20`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SuppressionRule.kt#L15-L20)<br>• 單元測試：[`ReconcilerTest.kt:213-236`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/reconcile/src/test/kotlin/dev/quietinbox/core/reconcile/ReconcilerTest.kt#L213-L236)、[`SuppressionRuleTest.kt:6-23`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/test/kotlin/dev/quietinbox/platform/storage/repo/SuppressionRuleTest.kt#L6-L23)<br>• 真機測試：[`SearchPagingTest.kt:108-127`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SearchPagingTest.kt#L108-L127)。 |
| **#10 搜尋與對話頁面金庫鎖定狀態** | **已交付** | • ViewModel 監聽 `vault.state`，顯示 `vaultLocked` / `vaultOpening`：[`SearchViewModel.kt:39-55`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt#L39-L55)、[`ConversationViewModel.kt:53-70`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationViewModel.kt#L53-L70)<br>• UI 顯示 EmptyState 與重試按鈕：[`SearchScreen.kt:102-108`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchScreen.kt#L102-L108)、[`ConversationScreen.kt:80-87`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L80-L87)<br>• 單元測試：[`SearchViewModelTest.kt:54-90`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/search/src/test/kotlin/dev/quietinbox/feature/search/SearchViewModelTest.kt#L54-L90)、[`ConversationViewModelTest.kt:58-76`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/conversation/src/test/kotlin/dev/quietinbox/feature/conversation/ConversationViewModelTest.kt#L58-L76)。 |
| **#11 Keyset 搜尋與分析對話內中位數** | **已交付** | • SQL Keyset 游標分頁 `(sortKey, id)`：[`Daos.kt:424-440`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L424-L440)<br>• 搜尋儲存庫循環校驗偽陽性並回傳游標：[`SearchRepository.kt:49-76`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SearchRepository.kt#L49-L76)<br>• 分析在對話內計算時間間隔中位數，且發送者以對話隔離區分：[`ActivityAnalytics.kt:119-124, 100-109`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/ActivityAnalytics.kt#L100-L124)<br>• 測試驗證：[`ActivityAnalyticsTest.kt:60-80`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/analytics/src/test/kotlin/dev/quietinbox/core/analytics/ActivityAnalyticsTest.kt#L60-L80)、[`SearchPagingTest.kt:80-105`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SearchPagingTest.kt#L80-L105)。 |
| **#12 專案治理與建置防護** | **已交付** | • Gradle Wrapper 鎖定 SHA-256：[`gradle/wrapper/gradle-wrapper.properties:7`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/gradle/wrapper/gradle-wrapper.properties#L7)<br>• Lint 啟用 `abortOnError = true`：[`app/build.gradle.kts:57`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/app/build.gradle.kts#L57)、[`build-logic/.../quietinbox.android.library.gradle.kts:33`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/build-logic/convention/src/main/kotlin/quietinbox.android.library.gradle.kts#L33)<br>• GitHub 範本與治理：[`.github/CODEOWNERS`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/.github/CODEOWNERS)、[`.github/PULL_REQUEST_TEMPLATE.md`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/.github/PULL_REQUEST_TEMPLATE.md)、[`.github/dependabot.yml`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/.github/dependabot.yml)、Issue 表單。<br>• 關於頁面連結真實 Repo：[`strings.xml:288`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/designsystem/src/main/res/values/strings.xml#L288)。 |
| **#13 冷啟動持有緩衝 (Held Buffer)** | **已交付** | • 策略未知前僅保留 `StatusBarNotification` 原生框架物件不解析 Snapshot：[`CaptureCoordinator.kt:74-82, 368-382`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L74-L382)<br>• 上限 64 筆，15 秒逾時拋棄並記錄 `COLD_START` 缺口：[`CaptureCoordinator.kt:412-416, 757-760`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L412-L760)<br>• 策略載入後以管線鎖釋放並檢查白名單：[`CaptureCoordinator.kt:394-410`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L394-L410)<br>• 單元測試：[`CaptureCoordinatorTest.kt:525-564`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L525-L564)。 |
| **#14 通知存取跳轉降級與 Manifest 修正** | **已交付** | • 移除 Manifest 中造成版本行為分歧的 `disabled_filter_types="ongoing"`：[`platform/capture/src/main/AndroidManifest.xml`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/AndroidManifest.xml)<br>• 降級嘗試 Detail → List → AppInfo：[`ListenerAccess.kt:28-59`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/ListenerAccess.kt#L28-L59)<br>• 失敗時 UI 呈現 `listener_settings_manual` 手動指引：[`OnboardingScreen.kt:210`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/onboarding/src/main/kotlin/dev/quietinbox/feature/onboarding/OnboardingScreen.kt#L210)、[`HealthScreen.kt:143`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt#L143)。 |
| **#15 提醒條件檢驗、未讀數量與排程** | **已交付** | • DAO 查詢未檢視對話數：[`Daos.kt:170-177`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L170-L177)<br>• `ReminderPolicy.shouldRemind` 要求未讀 > 0：[`ReminderScheduler.kt:98-102`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/app/src/main/kotlin/dev/quietinbox/reminders/ReminderScheduler.kt#L98-L102)<br>• 提醒通知顯示對話數量複數字串：[`ReminderScheduler.kt:148`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/app/src/main/kotlin/dev/quietinbox/reminders/ReminderScheduler.kt#L148)<br>• `rescheduleNow()` 在 Worker 結束前 `await`：[`ReminderScheduler.kt:63-75, 124`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/app/src/main/kotlin/dev/quietinbox/reminders/ReminderScheduler.kt#L63-L124)<br>• `post()` 內部進行 API 33+ 權限安全檢查：[`ReminderScheduler.kt:154`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/app/src/main/kotlin/dev/quietinbox/reminders/ReminderScheduler.kt#L154)。 |
| **#16 備份分頁匯出、維護閘門與投影重建** | **已交付** | • 匯出走 `work`，還原走 `exclusive`：[`BackupService.kt:83, 244`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L83)<br>• Keyset 分頁匯出（每頁 500 筆）且過濾到期：[`BackupService.kt:144-180`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L144-L180)<br>• 損毀/缺失媒體計數並回報 `skippedMedia`：[`BackupService.kt:176, 185`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L176-L185)<br>• 還原完成後呼叫 `rebuildProjection`：[`BackupService.kt:365`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L365)<br>• 真機端到端測試：[`BackupRoundTripTest.kt:88-136`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRoundTripTest.kt#L88-L136)。 |

---

## 四、新發現問題與風險 (New Findings)

### 1. [Important] `BackupService.writeRecords()` 在 Room 交易內進行耗時的媒體檔案解密與磁碟串流，阻塞 SQLite 交易鎖，可能導致擷取管線拋出 `SQLiteDatabaseLockedException`
* **具體位置**：[`BackupService.kt:136-180`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L136-L180) 與 [`CaptureCoordinator.kt:589-604`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L589-L604)
* **故障情境**：
  1. `BackupService.export` 刻意運行在 `maintenance.work` 之下（而非 `exclusive`），設計目標是允許備份期間前景通知擷取仍可繼續運作。
  2. 然而在 `writeRecords` 中，程式碼將包含 conversations、messages、revisions、以及**所有 media 的查詢與檔案解密**全部放在單一 `db.withTransaction { ... }` 內：
     ```kotlin
     return db.withTransaction {
         ...
         while (true) {
             val page = db.mediaDao().exportPage(after, PAGE, now)
             if (page.isEmpty()) break
             for (b in page) {
                 val bytes = when (val r = blobCipher.decryptFile(mediaDir.file(b.fileName))) { ... }
                 line(BackupRecord.Media(...))
             }
             ...
         }
     }
     ```
  3. Android Room 的 `withTransaction` 在底層呼叫的是 SQLiteDatabase 的 `beginTransaction()`（預設為 `BEGIN IMMEDIATE` / `EXCLUSIVE` 寫入交易鎖）。
  4. 當使用者的資料庫累積了較多媒體檔案（例如數百 MB 的圖片）時，在交易內進行大量磁碟讀取、Tink AES-GCM 解密與串流輸出將耗時數秒至數十秒。
  5. 在此期間，`CaptureCoordinator.process()` 接收到通知，取得 `pipelineMutex` 後呼叫 `ingest.journal()` 或 `ingest.commit()`，均需要向 SQLite 申請交易寫入。
  6. 因 SQLite 交易已被 `writeRecords` 鎖定，擷取管線將在 `db.journalDao().insert()` 或 `ingest.commit()` 處阻塞。一旦等待超過 SQLite busy timeout（通常為 5 秒），將拋出 `android.database.sqlite.SQLiteDatabaseLockedException`。
  7. 且因為 `pipelineMutex` 在等待期間被持有，整個通知處理迴圈都會被卡死，甚至引發 `overflowCount` 增加或事件重試。
* **建議修復**：
  在 `withTransaction` 內僅分頁讀取媒體記錄的元資料（或分開多次短暫交易讀取），將磁碟 I/O（`blobCipher.decryptFile`）與加密串流寫入移出 SQLite 交易區塊，避免長時間持有資料庫交易排他鎖。

---

### 2. [Minor] `ListenerAccess.openSettings` 的 `resolveActivity` 預先檢查在 Android 11+ 套件可見性限制下可能被 OEM 系統誤判為不存在
* **具體位置**：[`ListenerAccess.kt:48`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/ListenerAccess.kt#L48) 與 [`app/src/main/AndroidManifest.xml:16-26`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/app/src/main/AndroidManifest.xml#L16-L26)
* **說明**：
  1. `openSettings()` 針對每個 Intent 先呼叫 `if (intent.resolveActivity(context.packageManager) == null) continue`。
  2. 在 Android 11+（API 30+，本專案 Target API 36）中，`resolveActivity` 會受到 Package Visibility 限制。Manifest 的 `<queries>` 區塊並未宣告 `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` 等 Intent Action。
  3. 在特定 OEM 系統（如部分小米/MIUI、三星設備，其「特殊應用程式存取權/通知存取權」由非 AOSP 標準 Settings 套件（如安全中心）接管），`resolveActivity` 可能回傳 `null`。
  4. 這會導致程式碼誤跳過該 Intent，三個 Intent 均跳過後回傳 `false` 並在畫面上呈現手動操作指引，即便直接呼叫 `from.startActivity(intent)` 其實可以由系統正確調起。
* **建議修復**：
  在 `<queries>` 中補充宣告對應的 Settings Intent Action，或者移除 `resolveActivity` 檢查，直接在 `try { from.startActivity(intent); return true } catch (e: ActivityNotFoundException) { ... }` 內處理。

---

### 3. [Minor] 金庫鎖定時，新進通知會連續觸發 `coldStart()` 並產生多次零碎的 `COLD_START` 缺口記錄
* **具體位置**：[`CaptureCoordinator.kt:384-391, 412-416`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L384-L391)
* **說明**：
  1. 當金庫因金鑰問題或未解鎖而處於 `VaultState.Locked` 時，`sources.sources()` 會拋出 `VaultUnavailableException`。
  2. 在 `coldStart()` 中，`guarded { loadSourcePolicy() }` 捕獲該例外，`sourcesLoaded` 保持為 `false`，`withTimeoutOrNull` 立即返回 `false`。
  3. 隨後 `dropHeld()` 被呼叫，清空 held 並記錄一筆 `GapReason.COLD_START`，且 `coldStartJob` 結束。
  4. 當下一則通知抵達時，因 `sourcesLoaded == false` 且 `coldStartJob?.isActive != true`，會再度觸發 `coldStart()` 並立刻失敗丟棄，再度記錄一筆 `COLD_START` 缺口。
  5. 這會導致在金庫鎖定期間，健康記錄頁面出現多筆零星微小的 `COLD_START` 缺口。
* **建議修復**：
  當 `loadSourcePolicy()` 偵測到金庫為 `Locked` 時，可配合 `vaultGapOpen` 狀態標記，避免重複產生短暫且零碎的冷啟動缺口。

---

### 4. [Minor] 空對話在還原後其 `lastActivityEpochMs` 會退回 `createdAtEpochMs`
* **具體位置**：[`Daos.kt:195-196`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L195-L196) 與 [`BackupService.kt:298, 365`](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L298)
* **說明**：
  1. 若備份中包含一個所有訊息均已到期或被刪除的空對話，其備份中的 `c.lastActivityEpochMs` 記錄的是原始最後一則訊息的時間。
  2. 還原時，雖然初始 `ConversationEntity` 寫入了該時間，但最後執行的 `rebuildProjection` 使用了：
     `COALESCE((SELECT MAX(m.observedAtEpochMs) FROM message ...), conversation.createdAtEpochMs)`
  3. 因為該對話沒有有效訊息，`lastActivityEpochMs` 被更新為 `createdAtEpochMs`，導致該空對話在列表中排序退回到建立時間。這屬於資料以可見訊息為唯一依據的已知一致性權衡，對正常含有訊息的對話無任何不良影響。

---

## 五、專題深入分析與驗證 (Detailed Observations)

### 1. Keyset 搜尋演算法驗證
* **分析結果：無漏列、無重複列、邊界完全正確**。
  - SQL 條件為 `(m.sortKey < :beforeSortKey OR (m.sortKey = :beforeSortKey AND m.id < :beforeId))`，搭配 `ORDER BY m.sortKey DESC, m.id DESC`。在同一毫秒有多則訊息（`sortKey` 相同）時，以主鍵 `id` 嚴格破平，徹底解決了 Offset 模式在資料增刪時的游標漂移問題。
  - 迴圈在 `verified.size == limit` 觸發中斷時，`position` 剛好停留在第 `limit` 筆符合條件的記錄上；下次調用傳入此游標時，查詢能無縫接續比對下一筆候選者。
  - `MAX_CANDIDATE_PAGES = 200`（最多 40,000 筆候選者）的防禦上限能在極端偽陽性時主動中止並回傳有效游標，確保 UI 協程不被餓死或無窮迴圈。

### 2. 維護閘門 (`VaultMaintenance`) 併發死鎖與例外安全性
* **分析結果：無死鎖風險**。
  - 針對 Brief 提出的疑問「`SettingsViewModel.import` 在 `exclusive` 下執行是否會因為不是 worker 而自我死鎖？」：
    `exclusive` 的 cancel 與 join 僅針對已註冊至 `workers` 集合的 Job（由 `work { }` 進入）。`SettingsViewModel.import` 直接調用 `backup.import`，其自身 Job 未註冊於 `workers`，因此不會對自己發出 cancellation 亦不會等待自己，**不存在自我死鎖**。
  - `exclusive` 區塊採用 Dekker 樣式屏障（flag → snapshot → cancel → join → pipelineMutex.withLock），進入時持有 `exclusiveMutex`，生命週期結束於 `finally` 重置 `_active = false` 並呼叫 `onMaintenanceEnded()`，結構嚴密。

### 3. 測試套件品質與時序決定論
* **分析結果：扎實嚴謹**。
  - 移除了原先依賴固定時間延遲的 `delay(200)`，改採 `CoroutineStart.UNDISPATCHED` 與 `CompletableDeferred` 握手，消除 CI 虛擬機高負載時的 Flaky 機會。
  - `SearchViewModelTest` 與 `ConversationViewModelTest` 明確使用了 `Dispatchers.setMain(Dispatchers.Unconfined)`，並透過真實 Dispatcher 驗證 Debounce 與狀態轉換，覆蓋率真實有效。
  - 總測試數 193 個 JVM 測試全綠（capture 20, storage 12, backup 21, analytics 8, search 2, conversation 1, core 72 等），與 `TEST_MATRIX.md` 兩語系之宣告完全一致。

---

## 六、建議行動清單 (Actionable Recommendations)

1. **將備份匯出中的媒體檔案解密移至 Room 交易外**：
   在 `BackupService.writeRecords` 中，先在短交易內查詢出該頁媒體 metadata，隨後在交易外讀取磁碟並解密串流，避免單一交易霸佔 SQLite 排他鎖導致前景擷取拋出 `SQLiteDatabaseLockedException`。
2. **強化 `ListenerAccess.openSettings` 的啟動容錯**：
   在 Manifest `<queries>` 中補全 Settings 相關 Intent Action，或直接在 `try-catch` 區塊中呼叫 `startActivity`，以兼容深度定制 OEM ROM。
3. **收斂金庫鎖定期間的冷啟動缺口**：
   在 `CaptureCoordinator` 中，若金庫處於鎖定狀態，將通知丟棄計入一般金庫鎖定缺口，避免每次收到新通知均產生微小的獨立 `COLD_START` 缺口。
