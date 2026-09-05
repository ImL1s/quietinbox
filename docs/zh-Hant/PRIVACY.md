> English: [../../PRIVACY.md](../../PRIVACY.md)

# 隱私

QuietInbox 的設計是：除非**你**主動移動，你的訊息副本絕不會離開你的裝置。

## App 可以存取的內容
- 透過 Android 的 `NotificationListenerService`，取得你所啟用的 App 發布的通知文字與 metadata。
  不會從來源 App 自己的儲存空間讀取任何東西。
- 那些通知所引用的圖片（`content://` URI 或內嵌的 bitmap），而且只在「複製通知中的圖片」開啟時才會。
  QuietInbox 絕不下載任何東西；來源 App 的 content provider 在 URI 被讀取時可能自行抓取資料，App 會在
  啟用此功能之前揭露這一點。

## App 絕不會做的事
- 沒有 `INTERNET` 權限；沒有分析、廣告或當機回報 SDK。
- 金庫不會有自動的系統備份或裝置間轉移（`allowBackup=false`，資料提取規則排除所有內容）。
- 不對通知採取任何動作：不使用 `contentIntent`、`deleteIntent`、`RemoteInput`，不取消通知，
  也不標示為已讀。
- 不記錄訊息內容、標題、URI 或 URL；本機診斷只包含代碼與計數。

## 資料存放位置
- App 私有儲存空間中的 SQLCipher 資料庫與加密的媒體 blob，使用每次安裝各自產生、並由 Android Keystore
  包裝的隨機金鑰。
- 非敏感的偏好設定放在 DataStore。

## 會離開裝置的內容
- 只有你匯出到自選位置（可能是雲端資料夾）的加密備份，且僅能以你的復原金鑰讀取；或是你明確分享或
  複製的內容。

## 保留期限
- 副本會在你設定的保留期限之後到期（預設 30 天）。刪除會移除資料列與檔案，但無法保證對快閃記憶體做
  物理覆寫；「刪除所有資料」也會一併銷毀金鑰。
