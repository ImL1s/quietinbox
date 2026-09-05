> English: [../../adr/0004-identity-and-dedup.md](../../adr/0004-identity-and-dedup.md)

# ADR-0004：身分與去重模型

日期：2026-09-06 · 狀態：accepted

## 身分（計畫 §7.1）

在一個 `SourceScope`（package + profile + 選用的帳號 key）之內：

1. 由 adapter 提供、具 `VERIFIED` 證據的來源聊天室 id → `chat:<id>`（目前尚不存在）；
2. `shortcutId` → `shortcut:<id>`（`INFERRED_FROM_STREAM`）；
3. 通知 tag/id 串流 → `stream:<tag>|<id>`（`INFERRED_FROM_STREAM`）；
4. 只有標題 → `title:<text>`（`UNRESOLVED`）。

group key 與 collapse key 絕不會是對話 id。位於不同串流、名稱相同的對話絕不會被自動合併。

## 去重（計畫 §7.2）

對齊器（`Reconciler`）在 `notification_checkpoint` 中，為每個通知串流保留一個有界視窗（≤ 64 個項目）：

- 已證實的 `sourceMessageId` 決定身分；相同 id + 不同內容 ⇒ 修訂；
- 否則新視窗會以最大的後綴／前綴重疊與前一個視窗對齊；重疊之後的項目是新的；完全被前一個視窗
  包含的視窗屬於過期重播；
- 單一個沒有 id、沒有時間戳、且與最後已知項目相同的項目，若是在已關閉或不同的通知底下發布，
  即為可能重複（`AMBIGUOUS_REPEAT`）：存成自己的一列、連結到原始項目、在介面中分開計數，
  絕不靜默丟棄；
- 同一個視窗內相同的項目會保留其重複次數；
- 過大的視窗會降級（截斷 + `DEGRADED_RESOURCE_LIMIT`）而不是阻塞；
- 對齊會涵蓋整個視窗（無 id 與有 id 的項目一視同仁），而已證實的 id 接著會覆寫依位置所做的判定，
  因此位置永遠不會漂移；
- 沒有新增任何東西的重播會保留前一個 checkpoint 視窗（`WINDOW_KEPT`），因此 `[A,B,C]` 之後的 `[A]`
  不會讓接下來的 `[B,C,D]` 重複 B 與 C。「沒有新增任何東西」意指沒有 `New` 也沒有 `Revision`：
  一次可能重複的單則重複（在已關閉的 `[A,B,C]` 之後、帶有新發布時間的 `[C]`）是重新觀測到一個
  既有位置，同樣會保留視窗（`ReconcilerAmbiguousKeepTest`）；
- 具有相同通知 key *而且* 相同 `postTime` 的重新觀測（重新連線後的作用中通知再同步）算是重新發布，
  即使該視窗在斷線時已關閉。

使用者刪除時會寫入一個不含內容的抑制 token（`SourceScope.key + "#" + identityKey` + 指紋，30 天 TTL，
DB v2），讓作用中通知的重播無法使該訊息復活，即使對話資料列本身已被刪除也一樣。還原備份則可以讓它
復活，介面中也如此說明。
