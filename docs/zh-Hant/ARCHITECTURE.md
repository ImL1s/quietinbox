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
  → generation check                     (commit fence: anything queued before revoke/pause is discarded)
  → IngestRepository.journal             (durable accepted; JSON payload in the encrypted vault, short TTL)
  → ParserRegistry.parse                 (adapter by package, else StandardParser)
  → IdentityResolver.resolve             (chat id > shortcut > notification stream > title; never cross-stream)
  → Reconciler.reconcile                 (suffix/prefix window alignment, ids, AMBIGUOUS_REPEAT, stale windows)
  → IngestRepository.commit              (one transaction: conversation, messages, revisions, links, tokens, checkpoint, journal state)
  → MediaCopier.copyPending              (bounded, time-limited, encrypted blobs; failure reasons kept)
  → Room Flows → ViewModels → Compose
```

在 `journal` 之前發生 process 死亡會遺失該事件（已記載為平台層面不可觀測）；在 `journal` 之後，該資料列
會在下次開啟金庫時以 `CaptureOrigin.REPLAY` 重播。

## 儲存（計畫 §8）

單一 SQLCipher 資料庫 `quietinbox.vault`（WAL），包含 §8 的資料表：`source_configuration`、
`capture_session`、`gap_interval`、`event_journal`、`notification_checkpoint`、`conversation`、
`message`、`message_revision`、`observation_link`、`media_blob`、`deletion_suppression`、
`search_token`、`summary_observation`、`local_diagnostic_event`。schema 會匯出到
`platform/storage/schemas/`，且不使用 `fallbackToDestructiveMigration()`。

搜尋：`search_token(token, messageId)` 保存由 `SearchNormalizer.tokens` 產生的 CJK bigram、拉丁字詞與
3-gram；查詢是把同一組 token 以 `GROUP BY … HAVING COUNT(DISTINCT
token) = n` 串接，接著每個候選項目都會在 Kotlin 中以正規化子字串重新驗證一次。

## 金鑰（計畫 §9）

- `KeystoreWrapper`：AndroidKeyStore 中的 AES-256-GCM 金鑰，`setUserAuthenticationRequired(false)`，
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
