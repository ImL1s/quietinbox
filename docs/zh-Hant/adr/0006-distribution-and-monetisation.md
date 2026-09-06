> English: [../../adr/0006-distribution-and-monetisation.md](../../adr/0006-distribution-and-monetisation.md)

# ADR-0006：發行與盈利——Google Play 付費、GitHub 免費、不用 billing SDK

日期：2026-09-06 · 狀態：accepted

## 背景

計畫要求商店版要有「一點」收入、同時有完全開放的版本。最直覺的做法（免費 App + 應用內「Pro」解鎖）需要 Google Play Billing
Library。它 9.x 的 POM 會拉進 `com.google.android.datatransport:transport-backend-cct`，其 manifest 宣告 `android.permission.INTERNET`
與 `ACCESS_NETWORK_STATE`（2026-09-06 對照已發布的 AAR 確認）。這些權限會被合併進 App manifest，`tools/check-permissions.sh` 會失敗，
產品最核心的承諾——*永遠不申請 INTERNET 權限*——在商店版就會變成假話。用 `tools:node="remove"` 拿掉權限，留下的是一個仍會嘗試
上傳遙測、在執行期失敗的函式庫；那也不是誠實的設定。

## 決策

- **Google Play：** 靜讀是**付費 App**（一次買斷，沒有訂閱、沒有內購、沒有廣告）。二進位檔與開放版完全相同：同套件、同功能。
- **GitHub releases：** 同一個 APK 以 GPL-3.0-or-later 免費發布，用專案的 upload key 簽章。Google Play 會用它的 app-signing key
  重新簽章商店版，因此兩種安裝無法互相更新；README 有說明。
- **不鎖功能。** 包含統計期間、搜尋、備份與匯出在內的每個功能兩個版本都有。在 Play 付費買到的是便利（自動更新、一鍵安裝）
  與對開發的支持，不是能力。
- Play Billing Library、Play Services、廣告 SDK 與任何分析 SDK 都不進相依圖。`tools/check-permissions.sh` 仍是強制點。

## 後果

- 收入是前置、每次安裝比 freemium 漏斗少，但隱私宣稱任何人 dump manifest 就能驗證。
- 可以改價，但 Play 上付費 App 不能改成免費。
- 未來的維護者若想加內購，必須連同 README、PRIVACY.md 與商店文案的「不申請 INTERNET 權限」承諾一起改寫並取代本 ADR。
