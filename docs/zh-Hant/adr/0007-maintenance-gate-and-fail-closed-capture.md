> English: [../../adr/0007-maintenance-gate-and-fail-closed-capture.md](../../adr/0007-maintenance-gate-and-fail-closed-capture.md)

# ADR-0007：維護閘門與 fail-closed 的擷取

日期：2026-09-06 · 狀態：accepted

## 背景

一次獨立審計（GPT-5.5 Pro，基準 `96b0cf9`；issue #1–#17）指出產品的四句承諾在程式碼裡都不是完整保證：
*說停就停*（暫停、撤權或停用來源後，已排隊的事件仍可能被 commit）、*刪了就不會回來*（journal payload 與媒體檔活得比列久；
重播可能把內容帶回）、*沒同意前什麼都不做*（媒體複製在揭露前就是開的）、*重設後什麼都不留*（快取的 cipher 可能用已銷毀的金鑰加密；
重設期間其他工作照樣寫入）。冷啟動則在使用者的來源清單載入前就讀了所有 App 通知的 extras。

## 決策

- **所有金庫寫入者共用一個閘門。** `VaultMaintenance`（platform/storage）擁有 pipeline mutex、一份可取消的*金庫工作*登錄
  （`work {}`：媒體複製、journal 重播、retention、備份匯出）與*獨佔執行*（`exclusive {}`：刪除全部、備份匯入）。獨佔執行先立旗、
  取消並等待 worker，然後在整段期間持有 pipeline 鎖。監聽者（`MaintenanceListener`）在開始與結束時被同步通知，絕不透過會合併值的 flow。
  擷取端在開始時輪換 generation（排隊中的全部丟棄、不再收新事件），並把這段時間記錄為精確的 `MAINTENANCE` 缺口。
- **擷取管線有三道圍籬。** 進入判定在等 pipeline 鎖之前與鎖內各做一次；commit 前再圍一次。暫停或維護讓已接受的事件維持 `PENDING`
  等下次重播；期間被停用或移除的來源則丟棄（`DISCARDED`，payload 清空）。來源 policy 的變更（新增、啟用、暫停、移除）都在 pipeline
  鎖*內*進行，因此等鎖的事件會以新 policy 被圍籬。暫停時絕不重播，重播在查詢層排除暫停中的來源。
- **文字不會活得比列久。** journal 列一離開 `PENDING` 就清空 payload；媒體列與檔案跟著訊息走；移除來源並刪資料時整張刪除圖一起清；
  會話投影從殘留的列重算；讀取端自行過濾到期，不等 retention worker。
- **金鑰有 epoch。** 銷毀 secret 時 `KeyMaterial.epoch` 遞增；快取的 cipher primitive 綁定建立時的 epoch，建立途中 epoch 變動則
  既不快取也不回傳。KEK 的建立在整個 process 內序列化。
- **冷啟動 fail closed。** 來源清單未知前，第三方通知只以框架物件保留（不讀取任何內容），上限 256、最舊先淘汰；policy 一知道就只對
  啟用來源建立 snapshot。每一筆遺失——淘汰、金庫 15 秒內打不開、金庫鎖定時記不下來的遺失——都在金庫可寫時補記為有界的
  `COLD_START` 缺口，且只有寫入成功後才會忘掉那筆遺失。缺口一定顯示、絕不隱藏，即使缺口表本身當時不可達。
- **刪除全部經過驗證。** 每一步（資料庫檔、媒體檔、金鑰、重開）都檢查並指出失敗的步驟；金庫一定重開，重設失敗絕不會讓 App
  卡在 `Opening` 的金庫上。

## 後果

- schema v3（三個 nullable 欄位：journal `packageName`、suppression `sourceMessageId` 與 `postedAtEpochMs`）。沒有任何列被改寫。
- 還原或重設是一段短而誠實的擷取缺口，不是無聲的交錯；備份匯出可被重設取消，並在交易外讀媒體，因此永不阻塞擷取。
- 刪除抑制以 fingerprint 為鍵：同一 post 的重播整體被抑制（兩側 id 相同時以 id 判定，否則以 post 時間），而同一 post 內與已刪訊息
  同指紋的新訊息也會被抑制；逐 id 的 token 需要之後的 schema 版本。媒體複製在接受揭露前維持關閉，舊預設值的安裝亦同。
- 本 ADR 沒有決定的事：逐設定檔的來源控制（schema 工作）、真實來源 fixture（#17）、仍在 CI 之外的治理項目（detekt、CodeQL、SBOM、可重現建置）。
