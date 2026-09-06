# Round 25 — round-24 修正的迷你再審（Claude Fable 5.1 subagent）

- 審查對象：`git diff 0bf44ba..1fbf693`（一個 commit，19 個檔案；其中 4 個是 round-24 報告歸檔）。
- 審查方式：唯讀。讀完 brief、整個 diff、round-24 三份報告（agy／Kimi／subagent）的相關條目；用 python 重算五份 note 的字數與三處一致性、重跑 `release.yml` 的 awk 抽取、grep 五份 note 是否還有「read」家族的字、diff 歸檔報告與 `.omc/research` 原稿。沒碰裝置、沒碰 Play、沒改任何 tracked 檔案。這個 commit 沒有任何程式碼或建置檔變更，不需要重跑測試或重建。

## Verdict：APPROVE

0 Critical、0 Important、1 個不擋的 Minor 措辭建議。每一項宣稱修好的東西都真的修好了，五份 note 現在說的事完全一樣，沒有退化。

## Critical

無。

## Important

無。

## Minor／nitpicks

**M-1 en-US 的 "dates, times and pickers follow the app language" 裡的 "pickers" 有點裸。** `fastlane/metadata/android/en-US/changelogs/6.txt:1`（同步在 whatsnew 與 release-notes.json）。ja／ko 的對應句都寫明是「日付・時刻の選択画面／날짜·시간 선택 화면」，英文母語讀者看到單獨的 "pickers" 要停一下才知道是哪種 picker。改成 "dates, times and the date and time pickers follow the app language" 是 486 字元（實測），仍在 500 內。純措辭，不影響上傳；若要改，三處一起。

## Observations（不擋）

**O-1 逐項確認 round-24 的修正。**

| 項目 | 宣稱 | 實際 | 結果 |
| --- | --- | --- | --- |
| Kimi Important 1／subagent I-1／agy nit 1 | en-US 補回 "expired copies are hidden at once"，縮短別處 | `en-US/changelogs/6.txt` 現在有 "expired copies are hidden at once;"，第一句縮成 "dates, times and pickers follow the app language"；468 字元 | 已修 |
| subagent I-2 | 五份 note 改用 unviewed 家族 | en "unviewed"、zh-TW「尚未查看的副本」、zh-CN「尚未查看的副本」、ja「未閲覧のコピー」、ko「미확인 사본」；對 `6.txt`、`whatsnew-*`、`release-notes.json` grep `unread\|未讀\|未读\|未読\|읽지 않` 為 0 筆；與目錄 `inbox_unviewed` = Unviewed／未查看／未查看／未閲覧／미확인 一致 | 已修 |
| subagent M-6（ja／ko 的中文選擇器句、日文冒號） | ja／ko 改說「選擇畫面跟著 App 語言」；日文全形冒號 | ja「日付と時刻、日付・時刻の選択画面はアプリの言語に従います。」＋「含みます：」；ko「날짜와 시간, 날짜·시간 선택 화면은 앱 언어를 따릅니다.」 | 已修 |
| subagent M-1 | CHANGELOG 0.1.2 lead 不再把 Play 升級路徑寫成事實 | `CHANGELOG.md:11` 現在是 "0.1.1 was a GitHub-only release, so the store notes for `versionCode` 6 also carry the 0.1.1 audit fixes in one clause."；只講已發生的事 | 已修 |
| Kimi Minor 2 | CLAUDE.md layout 行列出五個目錄 | `CLAUDE.md:48` "all strings (en, zh-Hant, zh-Hans, ja, ko; parity checked in CI)" | 已修 |
| 索引 row 24（en + zh）、歸檔 | 加列、歸檔三份報告與 brief | `docs/reviews/README.md:40`、`docs/zh-Hant/reviews/README.md:37` 各加一列，內容與三份報告的實際判定相符（agy `## Verdict: APPROVE`、Kimi `## Verdict: APPROVE WITH MINOR FIXES`、subagent APPROVE WITH MINOR FIXES）；`claude-subagent.md`、`gemini-3.8-flash-high-agy.md`、`kimi-k3.md`、`brief.md` 與 `.omc/research` 原稿 `diff` 逐位元組相同 | 已做 |

**O-2 五份 note 現在說的事完全一樣。** 每一份都有同樣七個子句：新增三語＋Android 13 per-app 語言；日期／時間／選擇器跟著 App 語言；停止／暫停／移除來源真的停；圖片複製在接受揭露前關閉；「刪除所有資料」逐步驗證；到期副本立即隱藏；搜尋不漏後面的命中；提醒只在有未查看副本時發出。brief 說明的刻意差異（zh-TW／zh-CN 保留「選擇器對中文使用者也是中文」，en／ja／ko 說「選擇器跟著 App 語言」）兩種說法都屬實，對各自讀者也都是有用的資訊，接受。

**O-3 字數與三處一致。** en 468、zh-TW 192、zh-CN 187、ja 238、ko 260（Python `len()`，UTF-16 單位相同）；`changelogs/6.txt` 與 `whatsnew-<locale>` 逐位元組相同（含尾端換行），`release-notes.json` 的 `text` 與去尾端換行後的 6.txt 相同；無 markdown 字元、無內嵌換行；JSON 可解析、五個 language 齊全。

**O-4 CHANGELOG 仍能被 workflow 抽出。** `awk -v ver="0.1.2" '/^## \[/{p = index($0, "[" ver "]") > 0} p' CHANGELOG.md`：18 行，起於 `## [0.1.2] — 2026-09-06`、止於 `## [0.1.1]` 前；本 commit 對 CHANGELOG 只改了 lead 那一行（單一 hunk，−1／+1），fold 的其餘內容與 round 24 驗過的一致。

**O-5 沒有退化。** diff 之外的檔案沒動；沒有程式碼、gradle、資源或 workflow 變更，round 24 對 badging／簽章／權限閘門／212 JVM 測試的證據仍然代表這個 tree（CI 反正會從 tag 重建）。`git status` 只有 untracked 的 `docs/reviews/2026-09-06-round25/brief.md`，記得與本報告一起歸檔。

**O-6 留著沒改、也不需要改的小刺。** en 說 "0.1.1 audit fixes"，四份 CJK 說「安全審計／セキュリティ監査／보안 감사」（round-24 M-6 就列為 nit，這次沒宣稱要修）；ko 用半形冒號是韓文慣例；index row 24 的 Fix commit 欄寫「follow-up commit」（與 row 20、23 的慣例一致，因為 commit 寫自己的 hash 做不到），若之後的 docs commit 順手填成 `1fbf693` 更好。Brief 註明刻意延後的項目（SCOPE／RELEASE.md 的 Play 狀態、zh 文件對位、R8 mapping 進 CI 產物）本輪不重提。

**O-7 打 tag 前仍剩一件離線驗不到的事（round 24 O-4）：** 確認 GitHub 上 `996f8d7` 的 `ci.yml` 是綠的；`0bf44ba` 與 `1fbf693` 都只動了版本號、文案與文件，不會改變那個結果。

## Verification performed

| 項目 | 指令 | 結果 |
| --- | --- | --- |
| 範圍 | `git diff --stat 0bf44ba..1fbf693` | 19 檔 +419／−17；非歸檔部分為 CHANGELOG 1 行、CLAUDE.md 1 行、兩個 index 各 1 列、五語 6.txt／whatsnew、release-notes.json |
| Note 字數 | python `len()` 與 UTF-16 單位 | en 468、zh-TW 192、zh-CN 187、ja 238、ko 260 |
| 三處一致 | python：6.txt raw == whatsnew raw；json text == 6.txt strip | 五語系皆 True |
| 禁字 | `grep -l -E "unread\|未讀\|未读\|未読\|읽지 않" fastlane/metadata/android/*/changelogs/6.txt fastlane/whatsnew/* fastlane/release-notes.json` | 0 檔 |
| 到期子句 | python 檢查 expired／到期／期限切れ／만료 | 五語系皆有 |
| awk 抽取 | `awk -v ver="0.1.2" … CHANGELOG.md \| wc -l`；`grep -n '^## \['` | 18 行；標頭 Unreleased(5) → 0.1.2(9) → 0.1.1(27) → 0.1.0(73) |
| 歸檔逐字 | `diff docs/reviews/2026-09-06-round24/{claude-subagent,gemini-3.8-flash-high-agy,kimi-k3,brief}.md` vs `.omc/research/dual-review-round24-{subagent,agy,kimi,brief-safe}.md` | 四份 IDENTICAL |
| 判定核對 | `grep -n Verdict` 三份報告 | agy APPROVE；Kimi APPROVE WITH MINOR FIXES；subagent APPROVE WITH MINOR FIXES；與 index row 24 相符 |
| 替代句 | python 替換後 `len()` | "dates, times and the date and time pickers follow the app language" → 486 |
| 工作樹 | `git status --short --branch` | `main...origin/main [ahead 2]`；untracked 只有 round25 brief |
