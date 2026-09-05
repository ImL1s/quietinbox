# QuietInbox 全庫 Code Review（第四輪，獨立唯讀）

- 審查範圍：`4b825dc`（空樹）→ `d117ec3`，即整個 repository（21 個 Gradle module、192 個檔案、19,230 行）
- 對照依據：`/Users/iml1s/Downloads/QuietInbox_開源專案完整計劃.md`（§3 v0.1 範圍、§4 技術選型、§5 擷取管線、§7.2 去重、§8 儲存、§9 金鑰、§11 備份、§14–§17 測試與交付）、`docs/SCOPE.md`、`docs/ARCHITECTURE.md`、`docs/adr/*`
- **審查基準的精確說明（重要）**：brief 指定的 head 是 `d117ec3`，但我開始讀檔時 HEAD 已經是 `96b0cf9`（`chore: relicense to GPL-3.0-or-later, add repository CLAUDE.md`）。`git diff --stat d117ec3..96b0cf9` 只動了 `LICENSE`／`NOTICE`／`README.md`／`CHANGELOG.md`／`CLAUDE.md`／兩份 strings.xml，**沒有任何 Kotlin 或建置檔案改動**，所以下列所有程式碼 file:line 對 `d117ec3` 與 `96b0cf9` 都成立；授權相關的觀察（GPL-3.0-or-later、`NOTICE` 措辭、根目錄 `CLAUDE.md`）描述的是 `96b0cf9`。計劃 §17 把授權選擇留給 repository owner，因此 Apache-2.0 → GPL-3.0-or-later 是合法的 owner 決定，不列為偏離
- 交報告當下 `git status --short --branch` 顯示另一個 agent 已在工作樹修改四個檔案（`SnapshotFactory.kt`、`CaptureCoordinator.kt`、`IngestRepository.kt`、`BackupService.kt`），其中 `SnapshotFactory` 已加入 `selfName` 參數——也就是 **I3 的修正正在進行中**。本報告的所有發現都是對已提交狀態的判讀，請以「有沒有進 commit」為準來銷案
- 本次審查未改動任何受版控檔案；唯一寫入是本報告，位於 `.gitignore` 排除的 `.omc/` 下
- 實跑證據：`ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test --console=plain` → `BUILD SUCCESSFUL`, exit 0。由 19 份 `TEST-*.xml` 統計：**tests 97 / failures 0 / skipped 0**
- 每模組實測數：`core:model` 5、`core:parser` 10、`core:identity` 5、`core:reconcile` 20、`core:analytics` 4、`parsers:apps` 43、`platform:crypto` 3、`platform:backup` 3、`app` 4
- 未執行：instrumented tests 與任何裝置操作（依 brief 要求）

**分次審查說明。** diff 過大，分五個 pass 由本人獨立完成，未派生任何 subagent：

1. 擷取管線：`platform/capture` 全部、`core/model`、`core/parser` 全部、`parsers/apps` 全部（基底 `AppParser` 與五個具體 adapter 都逐檔讀過）
2. 儲存／加密／備份：`platform/storage`（entities、DAO、migration、五個 repository、retention）、`platform/crypto` 全部、`platform/backup` 全部、`platform/media`
3. 身分與去重：`core/identity`、`core/reconcile`、`core/analytics`
4. UI 與應用層：`app` 全部、`feature/*` 的 ViewModel 與關鍵畫面流程、字串資源對齊
5. 建置／CI／開源文件：`build-logic`、`gradle/`、`.github/workflows/ci.yml`、`tools/`、根目錄六份文件、`docs/` 全部（含三輪審查歸檔的逐條銷案核對）

---

### Strengths

**1. 計劃到程式碼的可追溯性是這份專案最強的部分。**
`docs/SCOPE.md` 每一列都附上證據種類，並且把「Done」「Implemented, **not device-verified**」「Not done」分成三塊，`MediaCopier`／`BackupService`／`ReminderScheduler`／BiometricPrompt 都誠實標為未經裝置驗證。`docs/COMPATIBILITY.md` 把五個 adapter 全部維持在 `SYNTHETIC_ONLY`，且明講「No adapter emits `sourceMessageId` or `SOURCE_CHAT_ID` evidence at `VERIFIED` confidence」——這正是計劃 §14 要求的態度。`docs/TEST_MATRIX.md` 把所有量化門檻標為 **unmeasured**，沒有把規劃值寫成量測值。這在 AI 協作專案裡極罕見。

**2. 計劃 §7.2 的六個例子是字面上的測試案例，不是換句話說。**
`docs/TEST_MATRIX.md` 的對照表逐條列出 `ReconcilerTest` 的測試名稱，`core:reconcile` 有 20 個測試含兩個 1,000 次迭代的 property test（固定 seed 20260905／20260906）。`Reconciler.kt` 的 `WINDOW_KEPT` 規則（`addsNothing && prevItems.size > fps.size` 時保留較長的舊視窗）是非顯而易見的正確性設計：沒有它，關閉視窗後的 `[C]` 會讓下一個 `[B,C,D]` 重複 B 和 C。這條規則有專屬測試（`ReconcilerAmbiguousKeepTest`）。

**3. 離線不變量是用機制強制的，不是靠慣例。**
`app/src/main/AndroidManifest.xml:8-9` 用 `tools:node="remove"` 移除 `INTERNET` 與 `ACCESS_NETWORK_STATE`，讓任何遞移依賴都無法把它加回來；`tools/check-permissions.sh` 在 CI 的 `assemble` job 對 **release** APK 跑 `aapt2 dump permissions`，同時擋 `QUERY_ALL_PACKAGES`。`<queries>` 用五個具名 package 加一個 LAUNCHER intent，沒有用 `QUERY_ALL_PACKAGES` 偷懶。

**4. 「不動來源通知」是可驗證的。**
我對 `contentIntent|deleteIntent|cancelNotification|RemoteInput|setNotificationsShown|snoozeNotification` 做全庫 grep，非註解命中只有 `SnapshotFactory.kt:69` 的 `hasRemoteInput`（純布林旗標，不持有 `RemoteInput` 物件）與 `NotificationSnapshot.kt` 的資料欄位。`QuietInboxListenerService` 完全沒有覆寫任何會改動來源的 API，且 snapshot 從不保留 `PendingIntent`。

**5. 管線的併發設計是紮實的，不是補丁堆疊。**
journal-first（`IngestRepository.journal` 回傳後才算 accepted）＋ generation commit fence（`CaptureCoordinator.process` 二次檢查 `paused || item.generation != activeGeneration || !stillCapturable`）＋ 單一 `pipelineMutex` 序列化 live 與 replay ＋ replay 在持鎖內再驗 `isJournalPending(eventId)`。`Channel(capacity = MAX_QUEUE_DEPTH)` 用 `trySend`，失敗時計數並轉 `DEGRADED` 且 `recordGap`，沒有任何 `DROP_OLDEST`。這完全符合計劃 §5 的「佇列不能默默 DROP_OLDEST」。

**6. 加密選型保守且可驗證。**
Tink 負責 AEAD 與 Streaming AEAD 的 nonce／順序／截斷／EOF；自寫的只有 HKDF（`Hkdf.deriveSha256`，有 RFC 5869 向量測試）與備份 header framing，且 header 被綁成 associated data。`KeystoreWrapper.unwrap` 在 KEK 遺失時明確丟 `KeyPermanentlyInvalidatedException` 而不是重新產生金鑰——這正是「不靜默清庫假裝新安裝」（計劃 §9）。`WrappedSecretFile.writeAtomically` 走 data fsync → rename → 目錄 `Os.fsync`，且目錄 fsync 失敗會丟 `IOException` 而不是交出未證明持久化的金鑰。`ADR-0003` 記錄了「不歸零交給 `SupportOpenHelperFactory` 的 key array」這個裝置實測結論。

**7. 搜尋沒有把使用者輸入送進 SQL 語法。**
`Daos.kt:304-305` 是 `token IN (:tokens) GROUP BY messageId HAVING COUNT(DISTINCT token) = :tokenCount`，全參數化；`SearchRepository.search` 用建索引的同一個 `SearchNormalizer.tokens` 產生查詢 token，再用 Kotlin `normalize(body).contains(normalized)` 做候選複核。這是計劃 §8「不把用戶輸入直接插進 FTS query 語法」的正確實作。

**8. 沒有破壞性 migration，而且 v1→v2 真的搬了資料。**
`MIGRATION_1_2` 用 `RENAME` 加 `INSERT ... SELECT ... JOIN conversation` 把舊的 `conversationId` 主鍵重算成 `scopeKey`，而不是 DROP 重建；schema JSON（1.json／2.json）有匯出並納入版控；`MigrationTest` 針對匯出 schema 驗證。

**9. 五個 adapter 的設計克制得恰到好處。**
`AppParser` 把 `singleCandidate`／`inboxCandidates`／`messagingCandidates` 三個 hook 設成 `final`，強迫 adapter 只能透過 `appSingleCandidates` 與 `postProcess` 擴充，讓 app 規則保證只套用一次——這是防止「單一六千行 switch」（計劃 §4）的具體手段。`LineParser` 只接受 `" : "`（兩側都有空格）才切分發送者，明確避免 `12:30 見` 被誤切；`InstagramParser` 要求前綴與 title 不同才切；`MessengerParser` 對「傳送了一張相片」只標 `MessageKind.MEDIA` 並記錄「通知本身有沒有帶 bitmap」，**不發明 URI 也不改寫 body**。每個檔案的 KDoc 都明寫「Every phrase is a synthetic guess (SYNTHETIC_ONLY)」。

**10. 品質標示的實作與宣稱一致。**
`ActivityAnalytics.compute` 回傳的 `ActivityReport` 帶 `sampleSize`／`confirmedCount`／`ambiguousCount`／`summaryOnlyCount`／`previewRestrictedCount`／`timeZoneId`／`gapCount`／`unknownGapCount`，計算前先 `filter { it.dedupState != AMBIGUOUS_REPEAT }`。沒有任何「回覆率」「已讀率」「收回率」欄位。

**11. 工程衛生。**
字串資源 en 與 `values-b+zh+Hant` **精確 298 / 298 對齊，零缺漏零多餘**（逐 name 做 comm 比對）。全庫 grep `TODO|FIXME|XXX|@Ignore|.skip(|.only(|NotImplementedError` 在 `app/core/feature/platform/parsers/build-logic` 下**零命中**。三輪獨立審查逐字歸檔於 `docs/reviews/`，包含被額度擋下的 reviewer 的 blocker 說明，`README.md` 也誠實記錄「strictest verdict wins」。我逐條核對 round-3 的 10 個 Minor，`08cbed9` 全部收尾（含 `findLatestIdsByFingerprint` 改抓最新 k 列、`guarded {}` 不吞 cancellation、CI 加入 crypto 裝置測試 lane、staging 上限降到 16M chars 並標為 nominal、Known defects 補進 SCOPE）。

---

### Issues

#### Critical (Must Fix)

**無。** 本輪未發現資料遺失、金鑰洩漏、功能損毀或安全邊界破口等級的缺陷。前三輪的 Critical 我逐條回溯驗證，都仍然修好且沒有回歸。我不把下列 Important 升級成 Critical——它們會造成錯誤或誤導，但不會破壞加密邊界，也不會無條件毀損使用者資料。

#### Important (Should Fix)

**I1 — 刪除整個會話後，通知重播會讓一個「空會話」帶著對方名稱回到收件匣**

- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:171-172`（找不到就先 insert，才跑 decision loop）
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:105`（`observeInbox` 無 `messageCount` 過濾）
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:146-147`（`emptyOlderThan` 需 7 天）

**問題**：`commit()` 的順序是「先確保 conversation 存在，再決定每個 decision 要不要寫入」。`InboxRepository.deleteConversation` 刪掉 conversation row（messages 由 FK CASCADE 一併刪除）之後，任何一次重播都會在 `:172` 用 `identity.displayTitle` 重新 `insert` 一列 `messageCount = 0` 的會話；接著每個 decision 都因為既有 message 已刪（`Decision.Known` 的 `takeIf { get(it) != null }` 回傳 null）或被 suppression 擋下而什麼都不寫。

**為什麼重要**：訊息內容確實沒有復活（suppression 與 dangling-id 檢查都正常運作），但**會話標題復活了**。使用者刪除整個會話多半正是為了讓那個名字消失。這一列會在收件匣停留到 `RetentionWorker` 的 `emptyOlderThan(now - 7 天)` 生效為止——而 `createdAtEpochMs` 是「重建時間」，所以是重建後整整 7 天，不是刪除後 7 天。`observeInbox` 完全沒有過濾 `messageCount = 0`，`InboxScreen.kt:181` 直接渲染。

這條沒有出現在 `docs/SCOPE.md` 的 Known defects，也沒有測試覆蓋：`VaultRoundTripTest:124-128` 只驗 `deleteMessages` 的單筆抑制，沒有驗整個會話刪除後的重播。

**修法**：把 conversation 的建立延後——先跑完 decision loop，只有在真的要寫入 message／ambiguous／summary 時才 `insert`。這是唯一真正關閉這個問題的改法，並且順帶消除所有「全部被抑制」「全部是 Known」情境留下的空列。緩解性做法是在 `observeInbox` 加 `AND (messageCount > 0 OR ambiguousCount > 0)`，但那只遮住 UI，空列仍留在庫裡 7 天。
（順帶一提，`deleteConversation` 也沒刪掉對應的 `notification_checkpoint`，那一列會帶著全是 dangling id 的視窗活到 `deleteStale` 的 14 天。這是衛生問題，**單獨清 checkpoint 不會解決本條**：`:172` 的 insert 在 decision loop 之前，跟視窗狀態無關。）

**I2 — 還原比保存期限更舊的備份，UI 回報「成功還原 N 則」，但這些訊息在 12 小時內就會被清除且無任何提示**

- `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:334`（`expiresAtEpochMs = m.expiresAtEpochMs`，原封沿用）
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt:53`、`:83`（12 小時週期）
- `core/designsystem/src/main/res/values/strings.xml:251`（`backup_result_ok`：「Done: %1$d conversations, %2$d messages, %3$d media items.」）

**問題**：還原直接沿用備份裡的 `expiresAtEpochMs`。備份若是 40 天前從一個 30 天保存期限的 vault 匯出，所有 message 的到期時間都已經是過去。還原成功後 UI 顯示 `backup_result_ok` 的完整筆數，但下一次 `RetentionWorker` 執行（最多 12 小時後）就把它們全部刪除。

**為什麼重要**：刪除本身**符合**使用者設定的保存期限，所以行為不算錯——錯的是**回報**。計劃 §11 要求「跨安裝、跨 schema、舊版→新版、新版→不支援舊版的結果**清楚**」，而這裡使用者看到的是「成功還原 1,842 則」，隔天全部消失，沒有任何線索。這正是計劃 §11 明列的「裝置更換演練」最常見的情境：舊手機壞了，拿幾週前的備份還原到新手機。

**修法**：不要在還原時延長到期時間（那會覆蓋使用者刻意設定的保存期限，而且 `MessageDao.recomputeExpiryAll` 會在下次調整保存期限時把它改回去）。改成在 staging 階段就統計 `expiresAtEpochMs != null && expiresAtEpochMs < now` 的筆數，然後二選一：(a) 在結果字串加上「其中 N 則已超過你目前的保存期限，會在下一次清理時移除；若要保留請先延長保存期限再重新還原」；(b) 直接略過這些列並把略過數回報出來。(a) 比較符合「不替使用者決定」的專案基調。

**I3 — `isSelf` 在 Android 擷取路徑上永遠是 false，而測試用的 fixture 繞過了那段程式碼**

- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:157`（`isSelf = false` 寫死）
- 受影響：`feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt:297`、`core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/ActivityAnalytics.kt:90`、`core/parser/src/main/kotlin/dev/quietinbox/core/parser/StandardParser.kt:134`
- 誤導性測試：`parsers/apps/src/test/kotlin/dev/quietinbox/parsers/apps/WhatsAppParserTest.kt:28,32`

**問題**：`NotificationCompat.MessagingStyle.Message` 的 `person == null` 是「這則訊息由使用者本人送出」的標準語意。`SnapshotFactory.bound()` 把每一則都寫成 `isSelf = false`，也沒有拿已經擷取到的 `selfDisplayName`（`:113`）去比對。結果：

- 使用者自己的訊息在 `ConversationScreen.kt:297` 渲染成「對方送來的」氣泡；
- `StandardParser.kt:134` 因為 `senderName == null && !isSelf` 而多加一個 `ParseWarning.NO_SENDER`，讓品質標示無謂變差；
- `ActivityAnalytics.kt:90` 的 `filter { !it.isSelf }` 形同無效，發送者排名會把使用者自己算進去。

**為什麼特別值得記一筆**：`WhatsAppParserTest.kt:28` 用 `message(null, "on my way", 3_000, isSelf = true)` 建構 fixture，`:32` 斷言 `batch.messages[2].sender?.isSelf shouldBe true` 並通過。但 fixture 是 `core/testing/Fixtures.kt:158` 直接組出 `MessagingMessageShape`，**完全繞過 `SnapshotFactory`**。這個測試證明的是 parser 會傳遞 `isSelf`，卻讓人誤以為整條路徑會產出 `isSelf = true`——而生產路徑永遠不會。這正是本專案 CLAUDE.md 自己列的「no fake completion」要防的形狀。

**修法**：`bound()` 多收一個 self 名稱參數，改成 `isSelf = person == null || (person.name != null && person.name == selfName)`。同時補一個直接餵 `Notification` 給 `SnapshotFactory` 的 instrumented 或 Robolectric 測試——否則 `StatusBarNotification → NotificationShape` 這一段依然沒有任何 oracle。

**I4 — 兩個最有狀態的元件完全沒有自動化測試**

**問題**：`./gradlew test` 的輸出顯示 `platform:capture`、`platform:storage`、`platform:media` 以及全部七個 `feature:*` 模組都是 `testDebugUnitTest NO-SOURCE`。也就是說：

- `CaptureCoordinator`（generation commit fence、pause／resume、冷啟動 `sourcesLoaded` 分支、`replayJournal` 與 live 的互斥、bitmap 預算）——**零測試**；
- `BackupService.stage()` / `apply()`（manifest 必須第一筆、`data after end`、`MAX_RECORDS`／`MAX_STAGED_TEXT_CHARS`／`MAX_STAGED_MEDIA_BYTES` 門檻、count 交叉驗證、還原合併去重）——**零測試**。

`platform:backup` 已經有可跑的 JVM lane（`HkdfTest` 走 `testDebugUnitTest`），而 `stage(reader: BufferedReader)` 是純函式，改成 `internal` 再餵 `StringReader` 就能測——這是幾小時的工作量。

**為什麼重要**：round 1–3 對這兩個檔案做了大量修正（generation 輪換、mutex、cancellation、staging 上限、count 驗證），而這些修正**唯一的證據是人工閱讀與裝置走查**。計劃 §16 明寫「代理不得把『編譯過』標成完成」；同理，「三個 reviewer 讀過」也不是回歸保護。任何後續改動都沒有網子接。

**修法**：優先補 `BackupService.stage()` 的負面案例（缺 manifest、manifest 不在首筆、end 之後還有資料、count 不符、超過各項上限、未知 record type），再用 fake repository 覆蓋 `CaptureCoordinator` 的 fence 與冷啟動分支。

**I5 — 沒有 dependency lockfile，也沒有 verification metadata**

**問題**：`gradle/` 只有 `libs.versions.toml` 與 `wrapper/`。全庫沒有 `verification-metadata.xml`、沒有 `*.lockfile`，`build.gradle.kts` 也沒有任何 `dependencyLocking` 設定。

**為什麼重要**：計劃 §4 的依賴列明寫「版本 catalog、**lockfile**、**verification metadata**、固定 toolchain」，四項只做到兩項。這是一個帶密碼學元件（SQLCipher native、Tink）、以「離線、可稽核」為賣點、即將公開發布的專案；§17 又要求「保留 toolchain/container digest、**依賴鎖**、source tag、SBOM、artifact hashes」。少了 checksum 驗證，`mavenCentral()` 上任何一個被替換的 artifact 都能無聲進入建置。而且 `material3` 釘在 `1.5.0-alpha27`、`ksp` 釘在 `2.3.11`（ADR-0001 自己說它 target 的是 Kotlin 2.3.20），這種 alpha 與錯位組合更需要 lockfile。

**修法**：`./gradlew --write-verification-metadata sha256 help` 產生 `gradle/verification-metadata.xml` 並提交；`dependencyLocking { lockAllConfigurations() }` 搭配 `--write-locks`。兩者都是一次性設定，之後升級版本時重跑。

**I6 — 還原時在 Room 寫入交易內做 base64 解碼與檔案加密，交易可能長達數分鐘**

- `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:285`（`db.withTransaction {`）
- 同檔 `:339`（`blobCipher.encryptToFile(...)` 在交易內）

**問題**：`apply()` 把整個合併包在單一 `withTransaction` 裡，迴圈內對每一筆 media 做 `Base64.decode` 再 `encryptToFile`（Tink AES-GCM、寫檔、rename）。上限是 256 MB media，最壞情況下 SQLite 寫鎖被持有數分鐘。

**為什麼重要**：期間所有 `IngestRepository.commit` 都會卡在寫鎖上；超過 busy timeout 就丟例外，被 `markJournalRetryable` 標記，最多重試 3 次後變 FAILED。事件不會遺失（journal 先寫了），但擷取進入 degraded，且沒有任何 UI 訊號說明原因。低階裝置上也有 ANR 風險。

**修法**：把 `encryptToFile` 移到交易外——先把所有 blob 寫進檔案系統並記下 `fileName`，再進交易只做 DB 插入。`writtenFiles` 的失敗清理邏輯已經存在，直接沿用即可。

**I7 — SECURITY.md 沒有可用的回報管道**

- `SECURITY.md:5-8` 指向 `CONTRIBUTING.md`
- `CONTRIBUTING.md:33-35` 指向「the repository owner (see git history)」

**問題**：安全通報路徑是「去翻 git history 找作者 email」。對一個把「加密、離線、可稽核」當核心賣點並主動邀請漏洞回報的專案，這不足夠。

**為什麼重要**：計劃 §16 的開源交付列了「安全回報」為必做項。研究者遇到這種指示，多半直接開公開 issue（正是 SECURITY.md 要避免的），或乾脆不報。

**修法**：發布前填一個真實信箱（可用專用別名），在 GitHub 啟用 Private Vulnerability Reporting，並把 SECURITY.md 的措辭從「once published」改成實際狀態。

#### Minor (Nice to Have)

**M1 — 兩處文件計數／狀態落後於程式碼。**
(a) `docs/TEST_MATRIX.md` 的「39 tests in `core:*`」少算了 `core:model` 的 5 個；實測 `core:*` 合計 44（model 5、parser 10、identity 5、reconcile 20、analytics 4）。總數 97 是對的。(b) `docs/SCOPE.md` 對「Own reminders」寫「`ReminderScheduler.delayUntilNext` pure function; **no unit test yet**」，但 `app/src/test/kotlin/dev/quietinbox/reminders/ReminderSchedulerTest.kt` 存在且貢獻了那 4 個 `app` 測試。round-3 的 Minor 1 才剛校過相鄰數字，這兩處沒一起校。

**M2 — `CHANGELOG.md` 沒有 round 3 的條目。**
`:22` 那段只寫到「Round 2 review」。但 `08cbed9` 改了行為：checkpoint-loss guard 從最舊列改成最新 k 列（影響 `incrementObservation` 打在哪一列、以及寫進 checkpoint 的 id）、`MAX_STAGED_TEXT_CHARS` 從 64M 降到 16M chars、`guarded {}` 改變了 cancellation 語意。CLAUDE.md 自己寫「Docs must not run ahead of the code；reviewers flagged 『docs ahead of code』 in every round」——這裡是反方向的落後，但同樣是文件與程式碼不同步。

**M3 — `MediaCopier` 的 `Semaphore(2)` 從來不會平行。**
`MediaCopier.kt:39` 宣告 `Semaphore(2)`，但 `:43` 是 `for (id in messageIds)` 循序迴圈，`:46` 的 `withPermit` 在迴圈體內被逐一 await，實際並行度永遠是 1。要嘛改成 `messageIds.map { async { ... } }.awaitAll()`，要嘛移除 semaphore 以免誤導讀者。

**M4 — `lint { abortOnError = false }` 讓 CI 的 lint 永遠不會失敗。**
`app/build.gradle.kts:56` 與 `build-logic/src/main/kotlin/quietinbox.android.library.gradle.kts:19-22` 都設了。CI 的 `assemble` job 跑了 `:app:lintDebug`，但結果不可能是紅燈，等於只是消耗 CI 時間。建議至少把安全相關的 issue id（`UnsafeIntentLaunch`、`ExportedService` 等）設成 `error`，或改成 `abortOnError = true` 搭配 baseline。

**M5 — 還原的去重用 HashSet，既有重複列會吃掉備份裡的多重性；`Counts` 的 revisions 會高報。**
`BackupService.kt:308` 把既有列收成 `HashSet<String>`，`:318` 命中就 `continue` 但不消耗。所以若 vault 已有 1 筆相同的 `fingerprint|sortKey|observedAtEpochMs`，備份裡 3 筆相同的會全部被跳過。`:296` 的註解寫「legitimate duplicates inside the backup keep their multiplicity」，只在「既有 0 筆」時成立。另外 `:370` 回傳 `Counts(..., s.revisions.size, ...)`，但 `:355-358` 對找不到 `msgMap` 的 revision 是 `continue`，被跳過訊息的 revision 沒寫入卻仍被計入。改成 `Map<String, Int>` 計數消耗、並回報實際寫入的 revision 數即可。

**M6 — `onRemoved` 用未截斷的 tag 算 streamKey，snapshot 卻截斷到 256 字元。**
`CaptureCoordinator.kt:220` 傳的是 `sbn.tag`；`SnapshotFactory.kt:97` 存的是 `sbn.tag?.take(Limits.MAX_KEY_CHARS)`。tag 超過 256 字元時兩者算出的 streamKey 不同，`closeWindow` 打不到那一列，該串流的視窗永遠不會被標記 `closed`，`AMBIGUOUS_REPEAT` 判定就失效。實務上罕見（多數 app 的 tag 很短），修法是在 `:220` 也套上同一個 `take`。

**M7 — 暫停／撤權不取消進行中的媒體工作。**
計劃 §5 明寫「撤權／暫停／刪除來源時切換 generation、**取消媒體工作**、關閉接受新事件」。`setPaused` 只輪換 generation，`scope.launch { mediaCopier.copyPending(...) }` 用的是 coordinator 那個永不取消的 scope。影響有限（對應 message 在 fence 之前就已 durable，撤權後 URI 授權多半失效並回報 `PERMISSION_DENIED`），但這是與計劃字面不符的一項。可用 per-generation 的 `Job`，或在 `copyPending` 迴圈裡檢查 generation。

**M8 — 備份不含 `mediaUri`，且同一訊息的多個 blob 只保留最後一個。**
`BackupRecord.Message` 沒有 `mediaUri` 欄位，`BackupService.kt:333` 還原時寫 `mediaUri = null`。跨裝置的 `content://` 本來就無意義，但同機還原後 `MediaState.PENDING` 的重試線索也一併失去。另外 `:304` 的 `associateBy { it.messageId!! }` 對同一 `messageId` 的多個 blob 只留最後一筆——目前 schema 一則訊息只會有一個 blob，但這是個沉默的假設，建議改 `groupBy` 或加註解說明。

**M9 — 模組佈局與計劃 §4 的清單有差距，ADR-0001 沒有解釋。**
計劃列的是 `:parsers:standard` 加 `:parsers:line / whatsapp / telegram / instagram / messenger` 五個獨立模組，以及 `:tools:fixture-publisher`、`:tools:replay-cli`、`:benchmark`。實際是單一 `:parsers:apps`（五個 adapter 共存），三個工具／benchmark 模組完全不存在。合併 parser 模組對 v0.1 是合理簡化（`AppParser` 把 hook 設成 `final` 的設計反而更安全），但 `ADR-0001` 只記錄了 toolchain 與 `core:*` 的 JVM 邊界，**沒有記錄這個偏離**；`docs/SCOPE.md` 的 Not done 清單也只提到 golden corpus 工具，沒提 `replay-cli` 與 `benchmark`。建議在 ADR-0001 補一段「模組合併」的 Decision／Consequences，並把缺的工具模組列進 SCOPE。

**M10 — `docs/reviews/` 內嵌維護者的本機絕對路徑，且引用的檔案被 `.gitignore` 排除。**
三份 gemini 報告合計 82 個 `../../../...` 連結（round1: 20、round2: 39、round3: 23），`round2/brief.md:6` 與 `round3/brief.md:6` 也直接寫了絕對路徑。同時 `docs/SCOPE.md:45` 與六份審查文件引用 `.omc/research/*.md`，而 `.gitignore:22` 排除了整個 `.omc/`——公開後讀者點不到任何一個。建議發布前做一次 `sed` 把絕對路徑換成相對路徑，並把 `.omc/research/` 的引用改指向 `docs/reviews/` 下的歸檔副本。

**M11 — 三個 adapter 的整句 notice 比對可能吞掉真實訊息。**
`TelegramParser` 的 `noticePhrases` 含 `"sending"`／`"connecting"`／`"updating"`，`LineParser` 含 `"正在通話中"`，比對方式是 `foldForMatch(body) in noticePhrases`（整句相等）。若某人真的傳了一則內容就是「Sending」或「Connecting」的訊息，`AppParser.parse` 會走 `noticeBatch` 回傳零則，`processJournaled` 隨即把 journal 標成 `SKIPPED` 並記診斷——訊息就此消失，而不是降級成不確定觀測。計劃 §17 的原則是「unknown 高發時優先降級通用觀察而非丟訊」。建議整句 notice 至少要求 `!structured && shape.isOngoing` 之類的第二個條件，或把這類單詞從 `noticePhrases` 移到只影響 warning 的清單。實務風險低（單詞訊息罕見，且 adapter 目前全是 `SYNTHETIC_ONLY`），但這是唯一一條會靜默丟棄內容的路徑。

**M12 — `LockController.prompt` 沒有覆寫 `onAuthenticationError` / `onAuthenticationFailed`。**
`app/src/main/kotlin/dev/quietinbox/ui/LockController.kt:78-86` 只實作 `onAuthenticationSucceeded`。使用者按取消或多次失敗時沒有任何回饋。因為 `LockScreen` 有一顆重試按鈕，使用者不會被困住，所以只是體驗粗糙，不是缺陷。

**M13 — 還原會把備份裡 `enabled = true` 的來源直接寫回，不重新徵詢同意。**
`BackupService.kt:288-292` 對 vault 中不存在的 package 直接 `upsert(... enabled = src.enabled ...)`。還原自己的備份時這是合理預期，但效果是「還原動作順帶開啟了對某些 app 的擷取」，而 `backup_import_desc` 只提到「可能帶回你刪除過的內容」，沒提來源開關。建議還原後在結果訊息列出被啟用的來源，或一律以 `enabled = false` 匯入讓使用者重新確認。

**M14 — `REASON_LOCKDOWN` 只記診斷，計劃 §7.3 要求的是安全事件處理。**
`CaptureCoordinator.kt:224` 對 lockdown 移除只寫一筆 `LOCKDOWN_REMOVAL` 診斷，沒有清除當前展示、鎖住該 scope。實際暴露很低（裝置 lockdown 時畫面本來就被系統遮蔽，本 App 也沒有鏡像通知或 actions 可清），所以這是 `docs/SCOPE.md` 的文件缺口而不是缺陷——但計劃明確把它列為安全事件，建議在 Known defects 寫清楚目前只做到記錄。

**M15 — `RecoveryKeyCodec` 接受非正規形式。**
52 個 base32 字元對應 260 bits，只用前 256 bits；最後一個字元的低 4 bits 未被驗證，所以同一把金鑰有 16 種可接受的文字表示。因為 checksum 是對**解碼後的 32 bytes** 算的，不存在金鑰混淆或碰撞風險，純粹是格式潔癖。若在意，可在 `decode` 裡重新 `encode(bytes)` 比對。

---

### Recommendations

**測試策略**（對應 I3、I4）
把 `BackupService.stage()` 改成 `internal` 並補一組 JVM 負面測試（缺 manifest、manifest 不在首筆、end 後有資料、count 不符、各項上限、未知 type），成本低、回報高，因為 `platform:backup` 已有可跑的 `testDebugUnitTest` lane。接著用 fake repository 覆蓋 `CaptureCoordinator` 的 generation fence 與冷啟動路徑。`SnapshotFactory` 需要一個真的餵 `Notification` 的測試（instrumented 或 Robolectric）——I3 的缺陷之所以躲過三輪審查與 97 個測試，正是因為所有 parser 測試都從 `MessagingMessageShape` 起跳，`StatusBarNotification → NotificationShape` 這一段完全沒有 oracle。

**發布前置**（對應 I5、I7、M10）
`verification-metadata.xml`、`dependencyLocking`、真實安全信箱、清掉 `docs/reviews/` 的本機路徑，這四件都是一次性的，且都是計劃 §16／§17 明列的開源交付項。建議打包成一個 pre-publication commit。

**文件可稽核性**
計劃書目前只存在於 `~/Downloads/`，不在 repo 內。任何外部審查者都無法驗證「實作是否符合計劃」。建議把一份去識別化的計劃（移除研究來源檔名與 hash）放進 `docs/PLAN.md`，或至少在 README 說明計劃文件的取得方式。同時把 `docs/SCOPE.md:45` 對 `.omc/research/` 的引用改指 `docs/reviews/2026-09-06-round1/`。

**流程**
三輪審查 → 修正 → 再審查的節奏是有效的：round 3 的 10 個 Minor 我逐條核對，`08cbed9` 全部收尾，沒有出現「修好但沒 commit」（round 2 踩過的坑）。唯一該補強的是**審查對象的偏斜**：三輪都聚焦在 diff 上，所以 `SnapshotFactory`（第一輪之後幾乎沒再改過）這種「早期寫好、後來沒動」的檔案從來沒被仔細看過，I3 就藏在那裡。建議在發布前對「從未被任何一輪 diff 覆蓋到的檔案」做一次專門掃描。

**架構層面**
`commit()` 目前是「先建 conversation，再決定要不要寫東西」，I1 是這個順序的直接後果。改成「先跑 decision loop，確定有東西要寫才建 conversation」會同時解掉 I1 與所有「全部被抑制／全部是 Known」情境留下的空列，也讓 `emptyOlderThan` 從必要路徑退回成純粹的保險。

---

### Assessment

**Ready to merge?** With fixes

**Reasoning:** 整個 repository 在計劃遵循度、離線不變量的機制化強制、去重演算法的正確性與加密選型上都達到可發布水準，97 個 JVM 測試全綠、工作樹乾淨、前三輪的 Critical 全部驗證未回歸。但發布前必須處理三件會造成使用者可見錯誤或誤導的問題——刪除會話後標題復活（I1）、還原舊備份後 UI 回報的筆數在 12 小時內消失且無提示（I2）、`isSelf` 在生產路徑上永遠是 false 而測試因繞過 `SnapshotFactory` 而看不見（I3）——以及三件開源交付缺口：`CaptureCoordinator` 與 `BackupService` 零自動化測試（I4）、缺 dependency lockfile 與 verification metadata（I5）、SECURITY.md 沒有可用回報管道（I7）。I6 的長交易建議一併修，成本很低。
