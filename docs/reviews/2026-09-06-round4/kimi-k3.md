# 第 4 輪審查報告 — QuietInbox 發布前差異（96b0cf9..1f7b182）

## 結論：**APPROVE WITH MINOR FIXES**

無 Critical 問題。四條產品鐵律全部守住：main manifest 明確 `tools:node="remove"` 拔掉 INTERNET/ACCESS_NETWORK_STATE 且 CI 有 `check-permissions.sh` 把關；全程無任何對來源通知的操作；誠實標籤維持（demo 資料刻意覆蓋每種標籤）；無破壞性 migration（DB 仍 v2,DemoDao 不加表不加欄）;release build 無 demo/debug 入口。兩個 Important 建議在正式發布前修掉，其餘為 Minor。

`./gradlew test` 已跑：**BUILD SUCCESSFUL**(361 tasks，全部 up-to-date，即此 HEAD 先前已全綠）。

## Critical

無。

## Important

**I-1 還原備份時，被略過訊息的媒體檔會永久洩漏，且回報數量失真** — `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:219-233`
媒體在交易前先加密落碟是正確方向（I6)，但 `prepared`/`writtenFiles` 對**所有** media 記錄都先寫檔；之後訊息若因重複（`BackupService.kt:276-279`）或孤兒（`:270-272`）被 `continue` 略過，對應加密檔已寫入 `writtenFiles` 卻沒有任何 `media_blob` row 參照它。失敗清理只發生在 exception 路徑（`:337-343`),`RetentionWorker` 的孤兒清掃（`Daos.kt:264-265`）只找「有 row 但 message 不存在」的情況，**沒有 row 的檔案永遠掃不到** → 每次還原同一份舊備份就洩漏一批加密檔（上限 256 MB/次）。同時 `Counts(..., writtenFiles.size)`(`:334`）把沒用到的檔也算進去，回報給使用者的「媒體還原數」會膨脹。
修法：交易內記錄實際用到的 `prepared` key，交易成功後把未用到的檔 `mediaDir.delete` 掉，counts 只算用到的。

**I-2 `isSelf` 用顯示名稱比對，同名聯絡人會被誤標為自己** — `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:163`
`isSelf = person == null || (selfName != null && person.name?.toString() == selfName)`。`person == null` 符合 MessagingStyle 語意，沒問題；但群組裡若有成員顯示名稱剛好等於裝置擁有者（例如都叫「小明」)，其訊息會被誤標 `isSelf=true`，直接污染「我發的」氣泡與好聊度（sent/received）統計——這碰到「誠實標籤」鐵律。比原先硬編 `false` 好，但仍不嚴謹。
修法：優先比對穩定身分 `person.key == messaging.user.key`（或 `person.uri`)；只有雙方 key 皆 null 時才退回名稱比對。

## Minor

- `SnapshotFactory.kt:62-64`：註解被兩個空行截斷（`// MessagingStyle semantics…` 段），像是貼上殘留，請併回一段。
- `DemoDataRepository`/`DemoDao` 位於 main source set，會進 release APK；因 Hilt 注入 `SettingsViewModel`,R8 不會移除它。release 中無任何入口（receiver 在 `app/src/debug`;Settings 區塊由 `BuildInfo.debug` 閘控，`AppModule.kt` 餵 `BuildConfig.DEBUG`)，風險為零，但付費二進位內含完整示範文案與 seeder 邏輯，知悉即可。
- `app/src/debug/AndroidManifest.xml` 的 `DemoReceiver` exported 且無 permission:debug build 中任何 App 可觸發 seed/clear。影響範圍僅 demo 標籤列（已驗證 `clear()` 只走 `DemoDao` 的 `demo.quietinbox.`/`demo-` 前綴刪除）,debug-only，可接受，請確認是有意為之（註解有說明是為了 `am broadcast`，合理）。
- 商店文案稱保存期限「7–365 天」，但滑桿實際允許 1–365(`feature/settings/.../SettingsScreen.kt:154`,presets 為 7/30/90/365)。文案小誤差。
- `Daos.kt` `deleteGaps` 用 `createdAtEpochMs IN (SELECT startedAtEpochMs …)` 對接刪除：真實 gap 若與 demo session 啟動毫秒完全相同會被波及。機率可忽略，且 DAO 註解已誠實說明 schema 無可標記欄位。
- `MediaCopier.copyPending` 改為平行 `async` 後，`messageDao().get(id)` 在 semaphore 外執行（只讀、Room 執行緒安全，無害）;`coroutineScope` 下單一失敗會取消姊妹任務，與舊版順序執行「遇錯即停」語意大致相同。可接受。

## 其他觀察（已逐項驗證，無需動作）

- **焦點 1(I1)已正確修復**：逐路徑核對 `IngestRepository.commit`——全數被 suppression 擋下的 replay 不再建立 conversation 列（`conversationId` 保持 null，投影 `:344` 跳過）;checkpoint 仍更新（無 FK 依賴，正確）;`preExisting` 防重改用 `ownerId = existing?.id`(`:203-207`)，新會話時為 null → emptyMap，與舊行為等價；真正的新訊息仍會重建已刪會話（正確語意，suppression 只蓋已知指紋）。`CommitOutcome.conversationId` 改 nullable 後唯一消費者 `CaptureCoordinator.kt:427-429` 只用 `pendingMediaMessageIds`，安全。
- **焦點 2 測試接縫未削弱防線**:`offerCaptured`(`CaptureCoordinator.kt:317-327`）忠實重述 `offer` 的准入規則（同包須 SYNTHETIC、來源清單已知才過濾）;commit fence 在下游 `process()`(`:356-360`:paused + generation + stillCapturable 三重檢查）,`enqueue` 不改變執行緒模型。`internal`，僅同模組測試可達。
- **焦點 3 備份其餘項全對**:`BackupStager` 為逐字搬移、邏輯相同，預設值即 `BackupLimits`;staging 上限 16M chars 與 CHANGELOG 相符；還原來源強制 `enabled=false`(`BackupService.kt:241`);過期重定基 `maxOf(it, now + retentionMs)`(`:298`);`retentionDays` 恆為正（`coerceIn(1,3650)`)，無「永久」哨兵值問題；重複多重性改用計數表（`:260-279`)；`restoredRevisions` 只計實際寫入（`:310-315`)。
- **焦點 4 demo 模式**:receiver/manifest 僅存於 `app/src/debug`,release source set 無此類；`BuildInfo` 由 app 模組單點餵 `BuildConfig.DEBUG`;DemoDao 刪除順序（media_blob 先行、gap 在 session 前）與 cascade 註解正確；suppression 的 `scopeKey` 以 packageName 開頭（`InboxRepository.kt:104` + `SourceScope.key`),`LIKE 'demo.quietinbox.%'` 前綴匹配成立。
- **焦點 6 release plumbing**：簽章讀 gitignored `keystore.properties` 或 `QUIETINBOX_KEYSTORE_*` 環境變數，無密鑰入庫（倉庫內的 `keystore.properties` 經 `git ls-files` 確認已被追蹤——**請維護者注意：根目錄確有 `keystore.properties` 檔案且工作區可見，若其中含真實密碼應確認它已在 `.gitignore` 且未入庫**；本次未讀其內容）。V1 關閉（minSdk 26 合理）；無 keystore 時 release 不簽章但權限閘門仍跑；workflow 先 `test` 再建 APK+AAB、權限閘、SHA256SUMS、GitHub Release 只上 APK（符 ADR-0006)、Play 預設 internal、production 需手動 dispatch。`verification-metadata.xml`(703 components）僅 trust aapt2 且註解說明理由，可接受。
- **焦點 7 文件誠實抽查全過**：SCOPE.md「32 JVM tests」= 28+4 實測相符；TEST_MATRIX 各模組數字逐一點算完全一致（5/10/5/20/32/43/4);CHANGELOG 每條宣稱皆對得上程式碼；`DemoDataTest` 確為 instrumented(`androidTest/`);README badge 指向的 `ci.yml` 存在；ADR-0006 關於 Play Billing 拉入 INTERNET 的論證與 manifest 防禦一致；zh-Hant 文件為對譯並有互鏈。
- `tools/demo-screenshots.sh`：只對 debug 包名操作、`pm clear` 限 debug APP_ID、開頭明確警告真機風險，合理。

## 建議發布前動作

1. 修 I-1（還原媒體檔洩漏 + 計數失真）——唯一有實質副作用的項目。
2. 修 I-2(`isSelf` 改 key/uri 優先比對）。
3. 順手處理 Minor 第 1、4 條；確認根目錄 `keystore.properties` 未入 git 追蹤。
