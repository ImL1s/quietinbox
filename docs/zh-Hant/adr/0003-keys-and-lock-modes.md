> English: [../../adr/0003-keys-and-lock-modes.md](../../adr/0003-keys-and-lock-modes.md)

# ADR-0003：金鑰處理與鎖定模式

日期：2026-09-06 · 狀態：accepted

## 決策（計畫 §9，v1 持續擷取模式）

- AndroidKeyStore 中有一把 AES-256-GCM 金鑰加密金鑰（`dev.quietinbox.kek.v1`），設定
  `setUserAuthenticationRequired(false)`，因此通知監聽器可以在螢幕鎖定時寫入。它只有在裝置首次解鎖之後
  才存在（credential-encrypted 儲存）。
- 三個每次安裝各自產生的 32 位元組隨機密鑰——資料庫金鑰、媒體金鑰、復原金鑰——只以 KEK 包裝後儲存，
  並把用途綁定為關聯資料，讓檔案無法被互換。
- 交給 `SupportOpenHelperFactory` 的資料庫金鑰陣列在開啟之後**不會**被歸零：
  SQLCipher 會在連線池中的每一條 WAL 連線重複使用它（裝置測試期間將它歸零，會在第二條連線上產生
  "file is not a database"）。
- 金鑰失敗會以 `VaultState.Locked(KeyFailure)` 呈現；介面提供重試或明確的重設。
  App 絕不會自行刪除它打不開的金庫。
- App 鎖（BiometricPrompt、`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`）只是介面閘門，設定中也是這樣描述。
  並未宣稱解密需要生物辨識。

## 此處未決定的事項

高安全性的鎖定金庫模式（與驗證綁定的金鑰、鎖定期間暫停擷取）以及以密碼為基礎的備份（Argon2id）
仍屬 P2，需要各自的 ADR 與審查。
