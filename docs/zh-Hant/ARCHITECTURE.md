> English: [../ARCHITECTURE.md](../ARCHITECTURE.md)

# 架構

## 模組關係圖

```
app ──► feature:* ──► core:designsystem ──► core:model
 │         │
 │         └──► platform:storage ──► core:parser / core:identity / core:reconcile / core:analytics
 │                     │            └──► platform:crypto (Keystore KEK, Tink AEAD, recovery key codec)
 │                     └──► Room + SQLCipher, DataStore, WorkManager
 ├──► platform:capture ──► parsers:apps ──► core:parser
 │            └──► platform:media ──► platform:crypto, platform:storage
 └──► platform:backup ──► platform:crypto, platform:storage, Tink Streaming AEAD
```

`core:*` 與 `parsers:apps` 是純 Kotlin/JVM 模組：它們不能引用 `android.*`，在 JVM 上以 Kotest 執行，並且
持有計畫要求「不需裝置即可測試」的每一個演算法（解析、身分、去重、統計、正規化）。`platform:*` 模組包裝
Android API；`feature:*` 模組是 Compose UI + Hilt ViewModel；`app` 串接導覽與 DI。

## 擷取管線（計畫 §5）

```
StatusBarNotification
  → CaptureCoordinator.isCapturable      (enabled-source allow-list; own package only with the synthetic marker)
  → SnapshotFactory.create               (allow-listed extras, size bounds, TruncationFlags; no PendingIntent/RemoteViews/Bitmap decode)
  → Channel(MAX_QUEUE_DEPTH)             (overflow ⇒ counted, DEGRADED, gap recorded — never DROP_OLDEST silently)
  → admission fence, twice               (before waiting for the pipeline lock and again inside it: pause, maintenance,
                                          generation, source policy — whatever changed while the event waited wins)
  → IngestRepository.journal             (durable accepted; JSON payload in the encrypted vault, cleared on leaving PENDING)
  → ParserRegistry.parse                 (adapter by package, else StandardParser)
  → IdentityResolver.resolve             (chat id > shortcut > notification stream > title; never cross-stream)
  → Reconciler.reconcile                 (suffix/prefix window alignment, ids, AMBIGUOUS_REPEAT, stale windows)
  → commit fence                         (a source disabled since ⇒ journal DISCARDED; a pause or maintenance ⇒ stays PENDING)
  → IngestRepository.commit              (one transaction: conversation, messages, revisions, links, tokens, checkpoint, journal state)
  → MediaCopier.copyPending              (bounded, time-limited, encrypted blobs; failure reasons kept)
  → Room Flows → ViewModels → Compose
```

在 `journal` 之前發生 process 死亡會遺失該事件（已記載為平台層面不可觀測）；在 `journal` 之後，該資料列
會在下次開啟金庫、恢復擷取或維護結束後以 `CaptureOrigin.REPLAY` 重播——暫停期間絕不重播，來源在此期間被停用者一律丟棄。

來源 policy（新增／啟用／暫停／移除）一律經由 `CaptureCoordinator`：金庫寫入與記憶體內的允許清單在 pipeline 鎖內
一起更新，因此正在等鎖的事件會以新 policy 被圍籬，不會用舊的。停用或移除來源會把該來源的 PENDING journal 全部標為丟棄。

## 維護閘門（QI-SEC-003）

`VaultMaintenance`（platform:storage）擁有 pipeline 鎖與一份可取消的金庫工作登錄。`MediaCopier.copyPending`、journal
重播與 `RetentionService` 都在 `work {}` 內執行：維護進行中會被拒絕，維護開始時會被取消。`VaultRepository.deleteEverything`
在 `exclusive {}` 內執行：立旗 → 取消並等待所有 worker → 持有 pipeline 鎖 → 關閉並刪除資料庫檔 → 刪除媒體 → 銷毀金鑰 →
清除設定 → 重新開啟，每一步都驗證，結果會指出失敗的步驟。擷取端看到旗標就輪換 generation（排隊中的全部丟棄、不再收新事件），
並把這段時間記錄為精確的 `MAINTENANCE` 缺口。`KeyMaterial.epoch` 在 `destroyAll()` 時遞增，`BlobCipher` 發現 epoch 變了就
重建快取的 primitive，因此絕不會用已銷毀的媒體金鑰加密任何東西。

## 儲存（計畫 §8）

單一 SQLCipher 資料庫 `quietinbox.vault`（WAL），包含 §8 的資料表：`source_configuration`、
`capture_session`、`gap_interval`、`event_journal`、`notification_checkpoint`、`conversation`、
`message`、`message_revision`、`observation_link`、`media_blob`、`deletion_suppression`、
`search_token`、`summary_observation`、`local_diagnostic_event`。schema 會匯出到
`platform/storage/schemas/`（v3），且不使用 `fallbackToDestructiveMigration()`。

刪除圖（QI-DATA-004 / 007）：journal 列一離開 `PENDING` 就清空 payload；刪除訊息或會話時，`media_blob` 列在同一交易刪除、
檔案在交易後刪除；移除來源並刪資料時，抑制 token、摘要、診斷與 PENDING journal 一併清除；`ConversationDao.rebuildProjection`
在每次刪除、到期清理與還原後，從殘留的列重算筆數、預覽、最後發送者與最後活動時間。讀取端（對話、搜尋、統計、計數）自行過濾
`expiresAtEpochMs > now`；Flow 的 `now` 在收集時固定。

搜尋：`search_token(token, messageId)` 保存由 `SearchNormalizer.tokens` 產生的 CJK bigram、拉丁字詞與
3-gram；查詢是把同一組 token 以 `GROUP BY … HAVING COUNT(DISTINCT
token) = n` 串接，接著每個候選項目都會在 Kotlin 中以正規化子字串重新驗證一次。

## 金鑰（計畫 §9）

- `KeystoreWrapper`：AndroidKeyStore 中的 AES-256-GCM 金鑰，`setUserAuthenticationRequired(false)`（KEK 的建立在整個 process 內序列化：全新安裝時同時建立的三把 secret 必須共用同一把金鑰），
  因此監聽器可以在螢幕鎖定時寫入。介面鎖是另一道獨立的閘門。
- `KeyMaterial`：三個 32 位元組的隨機密鑰（`db.key`、`media.key`、`recovery.key`），只以 Keystore 包裝後
  存放在 `files/keys/` 底下。失敗 ⇒ `VaultState.Locked`，絕不靜默清除。
- `BlobCipher`：Tink AES-256-GCM，以檔名作為關聯資料（associated data）。
- `BackupCrypto`：HKDF-SHA256（復原金鑰、隨機 salt）→ Tink AES-256-GCM-HKDF streaming AEAD；
  標頭（magic、version、salt）綁定為關聯資料。

## 介面

Material 3 Expressive（`MaterialExpressiveTheme`、expressive motion scheme、大型形狀），預設使用品牌
色盤，並可選用動態色彩。Navigation 3 返回堆疊；在寬度 ≥ medium 的視窗上，`NavigationRail` 加上
`ListDetailSceneStrategy` 會並排顯示收件匣與對話。每個品質狀態都會同時呈現文字 + 圖示（顏色絕不是唯一
的訊號）。
