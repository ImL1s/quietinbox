> English: [../../SECURITY.md](../../SECURITY.md)

# 安全政策

## 回報漏洞

安全問題請**不要**開公開 issue。請透過下列其中一種方式私下回報：

- GitHub 私人漏洞回報（此 repository 已啟用）：
  <https://github.com/ImL1s/quietinbox/security/advisories/new>
- Email：<aa22396584@gmail.com>，主旨前綴 `[quietinbox security]`。

你會在 7 天內收到確認回覆。請附上重現步驟與 QuietInbox commit。
報告中絕不可包含真實的私人訊息、通知傾印或復原金鑰。

## 威脅模型（計畫 §9–§11 摘要）

範圍內：拿到已鎖定裝置、或拿到備份檔但沒有復原金鑰的攻擊者；惡意或格式錯誤的通知內容；
刪除之後作用中通知的重播；遭竄改或被截斷的備份。

範圍外：完全控制 App process 或取得 root 的攻擊者；平台層級的螢幕錄影；發布誤導性內容的來源 App；
在已解鎖裝置上的記憶體鑑識。

## 加密選擇

- Android Keystore 的 AES-256-GCM 金鑰加密金鑰（v1 不綁定使用者驗證；見 ADR-0003）。
- 每次安裝各自產生的隨機資料庫／媒體／復原金鑰，靜態時以 Keystore 包裝。
- 金庫使用 SQLCipher for Android 4.18（社群版）。
- blob 使用 Tink AEAD（AES-256-GCM）；備份使用 Tink Streaming AEAD（AES-256-GCM-HKDF-1MB）。
- 備份金鑰使用 HKDF-SHA256（測試中含 RFC 5869 測試向量）。
- 沒有自製的密碼演算法；唯一自訂的結構是備份標頭 + 記錄框架，而它由 streaming AEAD 進行驗證。

## 已就位的強化措施

`FLAG_SECURE`（預設開啟；debug build 除外）、沒有明文圖片快取、有界且經允許清單過濾的通知輸入、
先寫日誌再 commit、撤銷時的 commit 圍籬（generation）、不含內容的診斷、重播抑制 token。
