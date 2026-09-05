> English: [../../adr/0005-backup-container.md](../../adr/0005-backup-container.md)

# ADR-0005：備份容器

日期：2026-09-06 · 狀態：accepted

## 格式

```
"QIBK" | version(1) | salt(16)              plaintext header, bound as associated data
Tink AES-256-GCM-HKDF streaming AEAD (1 MiB segments) over UTF-8 JSON lines:
  {"type":"manifest", formatVersion, schemaVersion, appVersion, createdAtEpochMs, expected counts}
  {"type":"source"|"conversation"|"message"|"revision"|"media"...}
  {"type":"end", actual counts}
```

金鑰：HKDF-SHA256（ikm = 256 位元的復原金鑰、salt、info = "quietinbox-backup-v1"）。復原金鑰會以
Crockford base32（13 × 4 個字元 + 4 個字元的檢查碼）顯示一次，而且是唯一的跨裝置密鑰；Keystore
金鑰絕不離開裝置。

## 匯入規則（計畫 §11）

所有內容都在記憶體中暫存（有界：每行 16 MiB、200 萬筆記錄、256 MiB 媒體），會驗證 `end` 記錄與兩組
計數，Tink 會驗證每一個分段與 EOF，之後才由一個 Room 交易套用合併（既有對話以 scope + 身分金鑰比對，
重複的指紋會略過，計數器重新計算）。任何失敗都會讓金庫維持原狀，並移除暫存期間寫入的媒體檔案。

## 刻意不做的事

不做壓縮（避免處理 zip bomb）、沒有密碼 KDF（P2）、不做部分／串流式套用。
