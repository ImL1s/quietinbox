# Round 24 — 0.1.2 發版就緒審查（Claude Fable 5.1 subagent）

- 審查對象：`git diff 996f8d7..0bf44ba`（0.1.2 版本 bump，13 個檔案）＋ `v0.1.1..0bf44ba` 整體發行（rounds 13–23 已審）。
- 審查方式：唯讀。讀完 brief、diff、`CHANGELOG.md`、`.github/workflows/release.yml`、`docs/RELEASE.md`（en／zh）、`docs/SCOPE.md`、`docs/TEST_MATRIX.md`、五語系 `strings.xml`、五語系 `fastlane/metadata`、round 18／21 報告；用 python 對照 CHANGELOG fold、`aapt2` 傾印 release APK、`apksigner`／`jarsigner` 驗簽、跑 `tools/check-permissions.sh`、統計 JUnit XML、用 `gplay docs generate` 查每個計畫用到的子指令旗標。沒碰裝置、沒碰 Play、沒改任何 tracked 檔案。

## Verdict：APPROVE WITH MINOR FIXES

沒有任何東西擋 tag 或上傳。但有兩處 store note 文字要在 **打 tag 之前** 修（不是上傳前）：`release.yml` 的 google-play job 從 tag 的 checkout 讀 `fastlane/whatsnew`，tag 後才修會讓 workflow 那條路徑永遠是舊字。

## Critical（tag／上傳前必修）

無。

## Important（tag／上傳前應修）

**I-1 五種語言的 versionCode 6 note 說的事不一樣：en-US 少了「到期副本立即隱藏」這一條。**
- `fastlane/metadata/android/en-US/changelogs/6.txt:1`、`fastlane/whatsnew/whatsnew-en-US:1`、`fastlane/release-notes.json:4`。
- zh-TW／zh-CN／ja-JP／ko-KR 四份都有「到期副本立即隱藏／到期副本立即隐藏／期限切れのコピーは即座に非表示／만료된 사본은 즉시 숨김」（CHANGELOG 0.1.1 #7，屬實），en-US 沒有。brief 第 1 項明講「the five texts say the same things」，而 round 18 報告（`docs/reviews/2026-09-06-round18/claude-subagent.md:109`，索引 row 18「en-US changelog missing one sentence」）已經對 changelog 5 抓過一模一樣的漂移，這次反方向再犯。
- 原因看得出來：en-US 現在 483 字元，直接加上 "expired copies are hidden at once; " 會到 518，超過 500。
- 修法（484／486 字元，實測）：把開頭的 "dates and times follow the app language, and the date and time pickers are Chinese for Chinese users" 縮成 "dates, times and the date and time pickers follow the app language"，騰出空間放回那一句。三個地方一起改（6.txt、whatsnew-en-US、release-notes.json）。完整替換字串見「建議替換文字」。

**I-2 五份 note 都用「read」家族的字，App 目錄刻意用的是「viewed」。**
- en "reminders fire only when something is unread"、zh-TW「有未讀時」、zh-CN「有未读时」、ja「未読があるとき」、ko「읽지 않은 항목이 있을 때」。
- App 五份目錄的同一概念是 `inbox_unviewed` = Unviewed／未查看／未查看／未閲覧／미확인，提醒本文是「有 N 個對話有新的副本等你查看」；五份目錄裡「未讀／未读／未読／읽지 않」一次都沒出現（grep 為 0）。App 之所以不說「已讀／未讀」，正是產品規則「誠實標籤」：它只知道你在 QuietInbox 裡看過沒有，不知道來源端讀沒讀。brief 也把 "read" 列為要檢查的字。
- 這條我評 Important 而非 Critical，因為句子講的是 QuietInbox 自己的提醒、不是來源訊息，使用者不太會誤解；但它同時踩到「術語與 App 一致」和「brief 點名的字」兩項，且和 I-1 是同一個修字 pass，順手一起修。替換：en "unviewed"、zh-TW「尚未查看的副本」、zh-CN「尚未查看的副本」、ja「未閲覧のコピー」、ko「미확인 사본」。字數見下。

## Minor／nitpicks

**M-1 `CHANGELOG.md:11` 的 lead line 超前於現實。** "Play users update from 0.1.0 straight to 0.1.2 (0.1.1 was released on GitHub only)" 在 Play 上傳還不存在時就寫成事實，而且 `release.yml` 的 awk 會在打 tag 那一刻把這句原樣抄進 GitHub release notes。這和 SCOPE row 37／RELEASE.md 刻意延後到上傳後才改（537ad80 模式）互相矛盾。零成本修法：改成 "Play users will update …"，或把那句留給上傳後的 docs commit。

**M-2 brief 第 5 項的 Play 計畫少一個必要旗標。** `gplay images delete-all` 的 `--confirm` 預設 `false`，不帶就拒絕執行（`gplay docs generate` 產出的參考表：`--confirm | Confirm delete | false`）。en-US 與 zh-TW 兩次 delete-all 都要帶。

**M-3 「en-US／zh-TW 的 tenInch 保留」這個假設要用 `images plan` 先驗。** `gplay images sync` 的說明只寫 "Upload local Play media to the current edit"；只有 `images plan` 是 "Plan deterministic Play media sync operations"（metadata-format 文件說單一資產用 SHA-256 比對）。tenInch 自 v0.1.0 起沒有任何 commit（`git log v0.1.0..0bf44ba -- …/tenInchScreenshots` 為空），hash 應該相同、會被略過；但若 sync 是純追加，7 張既有 + 7 張 = 14 > 8，整個 edit 會在 validate 掛掉。做法：先跑 `images plan --dir fastlane/metadata/android`，確認 tenInch 沒被列成 upload；若有，加 `delete-all --type tenInchScreenshots`（或用 `--locale` 限縮）。同理 `sync import-listings --dry-run` 存在，先跑一次看 en-US／zh-TW 只有「搜尋語言」那一行在變（我 diff 過 v0.1.0..HEAD，確實只有那一行）。

**M-4 R8 mapping 沒有跟著 CI 產物走。** `app/build.gradle.kts:57` `isMinifyEnabled = true`；`release.yml` 的 `dist/` 只有 APK、AAB、SHA256SUMS，沒有 `app/build/outputs/mapping/release/mapping.txt`，計畫裡也沒有 `gplay deobfuscation upload`。Play Vitals 的 crash 會是混淆過的堆疊。**不要**拿本機的 63 MB mapping 去配 CI 建的 AAB（RELEASE.md 自己說 CI 與本機位元組可能不同）；正確修法是下一版在 `release.yml` 的 "Name artifacts" 把 mapping.txt 放進 `dist/`。0.1.0／0.1.1 就是這樣，不是本版退化。

**M-5 docs 平行版落後（上傳後的 docs commit 一起補）。**
- `docs/zh-Hant/RELEASE.md:21` 少了 en 版同一條的五個 locale 清單和「workflow 只上傳 what's-new；新語言在 Play Console 加一次」那句（`docs/RELEASE.md:21`）。
- `docs/RELEASE.md:21` 那句「a new listing language is added once in the Play Console（Manage translations）」與這次計畫用 `sync import-listings` 建 ja-JP／ko-KR／zh-CN 的做法不同；`docs/RELEASE.md:33-35` 步驟 5 寫的是 `gplay release --track internal` → `promote`，計畫是直接一個 edit 上 production。上傳成功後把文件改成實際做法。
- `docs/zh-Hant/TEST_MATRIX.md:24` 用「金庫」、`:26`、`:72` 用「保險庫」，同一份文件兩個詞（App 目錄是「金庫」）。

**M-6 文字小刺（不必為此重來，但同一個 pass 可順手）。**
- ja／ko note 告訴日韓讀者「日付・時刻の選択画面も中国語ユーザーには中国語で表示されます／중국어 사용자에게 중국어로 표시됩니다」：屬實（AndroidX zh-r* 資源），但對日韓使用者是無關資訊；可刪或改成「日付と時刻はアプリの言語に従います」即可。
- ja note「0.1.1 のセキュリティ監査の修正も含みます:」日文後面接半形冒號，日文排版慣用全形「：」。
- en 說 "0.1.1 audit fixes"，四份 CJK 說「安全審計／セキュリティ監査／보안 감사」；CHANGELOG 0.1.1 自己寫的是 "GPT-5.5 Pro audit"。不是錯，只是五份又不完全一樣。
- `fastlane/metadata/android/zh-TW/changelogs/5.txt` 用「保險庫」（App 已統一為「金庫」）。versionCode 5 從未上 Play，這個檔只是歷史，不必動。

## Other observations

**O-1 對「docs 刻意延後」的立場：同意。** SCOPE row 37（en／zh）與 RELEASE.md 的 Play 狀態句留到上傳落地後再改，符合「docs 不得跑在 code 前面」；唯一的例外是 M-1 的 CHANGELOG lead，它現在就跑在前面了。上傳後的 docs commit 清單：SCOPE row 37 + zh；RELEASE.md 步驟 5；RELEASE.md／zh 的 one-time-setup 那條（M-5）；zh TEST_MATRIX 用詞。
其餘 brief 第 3 項點名的檔案沒有與 0.1.2 矛盾的地方：`README.md` 沒有任何版本字串（只有 Play／GitHub 通路表和 `:core:designsystem` 那行的五語系清單）；`CLAUDE.md:130-134` 的 Audit trail 段寫 rounds 13–17、18–23 都以雙方無發現核可收尾，與 `docs/reviews/README.md` row 17／23 一致；`docs/TEST_MATRIX.md`（en／zh）沒有總數句，`MonogramTest`（4）、`TimeFormatTest`（2）與程式相符；`docs/SCOPE.md:36` 與 `docs/zh-Hant/SCOPE.md:34` 的在地化列對位。

**O-2 CHANGELOG fold 完整，沒有掉字也沒有重複。** 把 `git show 996f8d7:CHANGELOG.md` 的 `[Unreleased]` 段（6188 字元）逐字對照新 `[0.1.2]` 段去掉 lead line 之後的本文：**完全相等**；`[Unreleased]` 現在只剩 "Nothing yet."；`[0.1.1]` 舊文是新文的前綴，只追加了 "Known issues in 0.1.1" 區塊；`[0.1.0]` 以下逐位元組相同。標頭序列：Unreleased → 0.1.2 → 0.1.1 → 0.1.0。

**O-3 兩條 Known issues 對得上程式與報告。**
- 第一條：v0.1.1 的 `app/build.gradle.kts` 確實是 `localeFilters += listOf("en", "b+zh+Hant")`；round 18 subagent 報告 §5.2（`claude-subagent.md:144-145`）用 `aapt2 dump resources` 證明 APK 裡 AndroidX 的 zh 設定為 0 條；現在是 `listOf("en", "b+zh+Hant", "b+zh+Hans", "zh-rTW", "zh-rCN", "zh-rHK", "ja", "ko")`（`app/build.gradle.kts:76`），release APK badging `locales: '--_--' 'ja' 'ko' 'zh-CN' 'zh-HK' 'zh-Hans' 'zh-Hant' 'zh-TW'`。「also in 0.1.0」屬實（0.1.0 就只有兩個 filter）。
- 第二條：round 21 subagent I-1（`claude-subagent.md:63-93`）：`TimeFormat` 用 `Locale.getDefault()`、process 因 listener 常駐不會跟 per-app 語言更新。現在 `Formatting.kt:41` `currentLocale()` 走 `LocalConfiguration`，`TimeFormat.time/dateTime/date` 的 `locale` 是必填參數（`Formatting.kt:20-25`），main source set 裡 `Locale.getDefault()` 只剩兩處 KDoc 註解；`TimeFormatTest`（2）在。描述「Chinese strings with English dates and AM/PM times」與 brief 的裝置證據（zh-TW 顯示「下午7:12」，en 顯示「7:12 PM」）一致。

**O-4 Release 機制：這個 CHANGELOG 用 awk 抽得出來，workflow 在這個 commit 上沒有會失敗的理由。**
- 實跑 `awk -v ver="0.1.2" '/^## \[/{p = index($0, "[" ver "]") > 0} p' CHANGELOG.md`：18 行，從 `## [0.1.2] — 2026-09-06` 到 `## [0.1.1]` 前一行；`[0.1.2]` 在檔案裡只出現一次（沒有底部連結參照會誤觸）。
- 自 v0.1.1 起 gradle 相關只改了 `app/build.gradle.kts`（版本號與 localeFilters），沒有新依賴，`gradle/verification-metadata.xml` 不需重生。`ci.yml` 自 v0.1.1 起多了 `check-strings.py` 步驟，release.yml 沒變。
- 一個我離線無法驗的前提：**確認 GitHub 上 `996f8d7` 的 `ci.yml` 是綠的**再打 tag。`release.yml` 會在 Linux 重跑 `test`，一個 Linux-only 的失敗會讓 GitHub release 半途中止（round 12 就發生過 CI 才抓到的排序問題）。

**O-5 Play 計畫的順序與遺漏（brief 第 5 項）。**
- 順序正確：`sync import-listings --edit`（需要 `--edit`，所以「一個 edit」成立）先建好 ja-JP／ko-KR／zh-CN 的 listing，之後 `images sync` 才有 listing 可掛圖；`delete-all` 在 sync 之前；`tracks update` 用 `--releases @releases.json`（`[{"name":"0.1.2","status":"completed","versionCodes":["6"],"releaseNotes":[…五筆 {language,text}…]}]`，`fastlane/release-notes.json` 的元素形狀可直接當 `releaseNotes` 陣列）；`edits commit` 預設 `--changes-not-sent-for-review=false`，會送審。
- 0.1.1 的 `changelogs/5.txt` 永遠不上傳：沒問題。Play 的 release notes 是綁在 release 上的；versionCode 5 沒有 Play release，就沒有地方放。
- 建議在開 edit 之前先跑離線檢查 `gplay validate listing --dir fastlane/metadata/android` 與 `gplay validate bundle --file quietinbox-0.1.2.aab`。
- 建議順序：修字 commit（I-1、I-2、M-1）→ tag → CI 綠 → `images plan` + `import-listings --dry-run` → edit → validate → commit。

**O-6 brief 的證據我能驗的都驗過了（見下方清單）。一個要講明的時間差：** JUnit XML 最新時間 19:06:30、APK／AAB 19:07，而 commit `0bf44ba` 是 19:20:20。也就是測試與建置是在 commit 之前、從同一個 working tree 跑的。badging 顯示 versionCode 6／0.1.2，且該 commit 的 13 個檔案沒有任何測試或建置相關內容，所以證據確實代表這個 commit；CI 反正會從 tag 重建。

**O-7 五份 note 逐份讀（母語讀者視角）。**
- en-US：自然、無 markdown。除 I-1／I-2 外沒有 over-claim：「really stops its copies」= #1、「image copies stay off until you accept the disclosure」= #2（App 開關就叫 "Copy images"，用 image 沒問題）、「verified step by step」= #3、「never misses a later match」= #11、「date and time pickers are Chinese for Chinese users」= zh-r* 資源。沒有 reply／read（source）／all messages 之類的字。
- zh-TW：自然；「App」與目錄一致（目錄自己也寫「來源 App」）；「來源／副本／擷取（未用）／刪除所有資料／搜尋／提醒」全對得上目錄；「日期／時間」全形斜線是 zh-TW 慣例。「揭露」目錄裡沒有（對話框叫「關於媒體副本」），但 0.1.1 note 就這樣寫、意思清楚，不動。
- zh-CN：自然；「应用」與目錄一致（目錄 25 處用「应用」）；引號用「“”」正確；「来源／副本／删除所有数据／搜索／提醒」對得上。
- ja-JP：自然；「ソース／コピー／リマインダー／すべてのデータを削除／削除（remove）／一時停止」全部與目錄一致（round 18 把 取り除く 改成 削除 之後就對了）。只有 M-6 的半形冒號。
- ko-KR：自然；「소스／사본／리마인더／모든 데이터 삭제／제거／일시중지」與目錄一致；「‘모든 데이터 삭제’」引號可接受。
- 長度：五份都遠低於 500（UTF-16 單位也一樣）；三處（6.txt、whatsnew、release-notes.json）逐位元組相同；無 markdown、無內嵌換行、單行尾端換行。

**O-8 其他不擋的觀察。**
- `docs/reviews/2026-09-06-round24/brief.md` 目前是 untracked；記得和報告一起歸檔。
- release APK 的簽章與 brief 一致：CN=QuietInbox Upload, O=CB Studio, SHA-256 `a82bddbe…80bedf29`；AAB `jar verified.`。
- 權限：POST_NOTIFICATIONS、USE_BIOMETRIC、USE_FINGERPRINT、WAKE_LOCK、RECEIVE_BOOT_COMPLETED、FOREGROUND_SERVICE ＋ 自家 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION；沒有 INTERNET／網路權限、沒有 QUERY_ALL_PACKAGES。

## 建議替換文字（實測字數，全部 ≤ 500）

先說明一個刻意的差異，免得下一輪又被當成漂移：en-US 的第一句改成 "dates, times and the date and time pickers follow the app language"，四份 CJK 仍是「日期／時間選擇器對中文使用者也是中文」。兩者講的是同一件事（AndroidX zh-r* 資源跟著 App 語言走），只是 en 這句原本的寫法正是把字數推過 500 的那一段，所以 en 用短句換回「expired copies」那一條。修字的人**不要**再把 en 對齊回 CJK 的長句；若想五份完全同構，反過來把 CJK 的那半句縮成「日期／時間選擇器也跟著 App 語言」即可（M-6 對 ja／ko 也建議這樣做）。

en-US（486）：

```
0.1.2 adds Simplified Chinese, Japanese and Korean with a per-app language setting on Android 13+; dates, times and the date and time pickers follow the app language. It also carries the 0.1.1 audit fixes: stopping, pausing or removing a source really stops its copies; image copies stay off until you accept the disclosure; "Delete all data" is verified step by step; expired copies are hidden at once; search never misses a later match; reminders fire only when something is unviewed.
```

zh-TW（192）：

```
0.1.2 新增簡體中文、日文與韓文，Android 13 以上可在系統設定為 App 單獨選擇語言；日期與時間跟著 App 語言顯示，日期／時間選擇器對中文使用者也是中文。同時包含 0.1.1 的安全審計修正：停止、暫停或移除來源後副本真的不再保存；圖片複製在你接受揭露前維持關閉；「刪除所有資料」逐步驗證；到期副本立即隱藏；搜尋不會漏掉後面的命中；提醒只在有尚未查看的副本時發出。
```

zh-CN（187）：

```
0.1.2 新增简体中文、日语和韩语，Android 13 及以上可在系统设置中为应用单独选择语言；日期和时间跟随应用语言显示，日期/时间选择器对中文用户也是中文。同时包含 0.1.1 的安全审计修正：停止、暂停或移除来源后副本真的不再保存；图片复制在你接受披露前保持关闭；“删除所有数据”逐步验证；到期副本立即隐藏；搜索不会漏掉后面的命中；提醒只在有尚未查看的副本时发出。
```

ja-JP（256；含全形冒號）：

```
0.1.2 では簡体字中国語・日本語・韓国語を追加し、Android 13 以降のアプリごとの言語設定に対応しました。日付と時刻はアプリの言語に従い、日付・時刻の選択画面も中国語ユーザーには中国語で表示されます。0.1.1 のセキュリティ監査の修正も含みます：ソースの停止・一時停止・削除でコピーの保存が確実に止まる、画像のコピーは注意事項に同意するまでオフ、「すべてのデータを削除」は段階ごとに検証、期限切れのコピーは即座に非表示、検索が後続の一致を見逃さない、リマインダーは未閲覧のコピーがあるときだけ通知。
```

ko-KR（281）：

```
0.1.2에서는 중국어 간체, 일본어, 한국어를 추가하고 Android 13 이상의 앱별 언어 설정을 지원합니다. 날짜와 시간은 앱 언어를 따르며, 날짜·시간 선택 화면도 중국어 사용자에게 중국어로 표시됩니다. 0.1.1의 보안 감사 수정 사항도 포함합니다: 소스를 중지·일시중지·제거하면 사본 저장이 확실히 멈춤, 이미지 복사는 안내에 동의하기 전까지 꺼짐, ‘모든 데이터 삭제’를 단계별로 검증, 만료된 사본은 즉시 숨김, 검색이 뒤쪽 일치를 놓치지 않음, 리마인더는 미확인 사본이 있을 때만 발송.
```

三處同步：`fastlane/metadata/android/<locale>/changelogs/6.txt`、`fastlane/whatsnew/whatsnew-<locale>`、`fastlane/release-notes.json`。

## Verification performed

| 項目 | 指令 | 結果 |
| --- | --- | --- |
| 工作樹 | `git status --short --branch` | `main...origin/main [ahead 1]`，tracked 無變更；untracked 只有 `docs/reviews/2026-09-06-round24/brief.md` |
| 範圍 | `git log --oneline v0.1.1..0bf44ba`；`git diff --stat 996f8d7..0bf44ba` | 14 個 commit；bump commit 13 檔 +27/−12 |
| Note 字數 | `wc -m` 與 python `len()`（去尾端換行） | en 483、zh-TW 187、zh-CN 182、ja 252、ko 283；UTF-16 單位相同 |
| 三處一致 | python 比對 6.txt vs whatsnew vs release-notes.json | 五語系逐位元組相同（whatsnew 現在有尾端換行，與 6.txt 一樣） |
| CHANGELOG fold | python：`git show 996f8d7:CHANGELOG.md` 的 `[Unreleased]` 段 vs 新 `[0.1.2]` 段本文 | 相等（6188 字元）；0.1.1 段只追加 Known issues；0.1.0 以下相同 |
| awk 抽取 | `awk -v ver="0.1.2" '/^## \[/{p = index($0, "[" ver "]") > 0} p' CHANGELOG.md \| wc -l` | 18 行，起於 `## [0.1.2]`，止於 `## [0.1.1]` 前 |
| JVM 測試 | python 彙總 `**/build/test-results/**/*.xml` | 31 檔、212 tests、0 failures、0 errors、0 skipped；最新 XML 19:06:30 |
| 儀器測試數 | `grep -r "@Test" platform/storage/src/androidTest \| wc -l` | storage 16（crypto 2、backup 2）；與 CHANGELOG「instrumented storage 16」相符 |
| Badging | `aapt2 dump badging app/build/outputs/apk/release/app-release.apk` | versionCode 6、versionName 0.1.2、targetSdk 36、compileSdk 37、locales `--_-- ja ko zh-CN zh-HK zh-Hans zh-Hant zh-TW` |
| 權限閘門 | `tools/check-permissions.sh app/build/outputs/apk/release/app-release.apk` | `OK: no network permission`，exit 0 |
| 簽章 | `apksigner verify --print-certs`；`jarsigner -verify app-release.aab` | CN=QuietInbox Upload，SHA-256 `a82bddbe…bedf29`；`jar verified.` |
| 依賴變動 | `git diff --name-only v0.1.1..0bf44ba -- gradle/ build-logic/ '*.gradle.kts' '*.toml'` | 只有 `app/build.gradle.kts` |
| 術語 | 對五份 `strings.xml` grep 金庫／保險庫／來源／擷取／副本／未讀／未查看 等 | zh-Hant 金庫 12、來源 25、擷取 18、副本 27、未讀 0、未查看（`inbox_unviewed`）；zh-Hans 保险库 12、未读 0；ja 保管庫 12、未読 0、未閲覧；ko 보관소 12、읽지 않 0、미확인 |
| 商店文案術語 | grep 五語系 `fastlane/metadata` | zh-TW full 用 金庫（保險庫 只在 5.txt）；zh-CN 保险库、ja 保管庫、ko 보관소 各 1 |
| 截圖歷史 | `git log v0.1.0..0bf44ba -- …/tenInchScreenshots`（en-US、zh-TW） | 空（未變）；phoneScreenshots 有 c90e75f／8954af1／b813c41 |
| en-US／zh-TW 文案變動 | `git diff v0.1.0..0bf44ba -- fastlane/metadata/android/{en-US,zh-TW}/*.txt` | 各只有 full_description 的「搜尋語言」一行 |
| gplay 旗標 | `gplay docs generate --output-file <scratch>/gplay-ref.md`（6621 行）後查表 | `sync import-listings` 需 `--edit`，有 `--dry-run`；`images delete-all --confirm` 預設 false；`images plan` 為 sync 的計畫模式；`edits commit --changes-not-sent-for-review` 預設 false |
| 修正碼 | `grep -rn "Locale.getDefault" --include="*.kt" feature core/designsystem/src/main app/src/main platform` | 只剩 `Formatting.kt:16`、`:34` 兩處註解 |
| Docs 版本字串 | `grep -n -E "0\.1\.[0-9]\|versionCode" README.md CLAUDE.md docs/SCOPE.md docs/RELEASE.md docs/TEST_MATRIX.md docs/zh-Hant/*.md` | README 0 筆；SCOPE row 37（en／zh）只提 0.1.0／0.1.1（刻意延後）；RELEASE.md 只有流程說明；COMPATIBILITY 的 0.1.0 是 adapter 版本，不是 App 版本 |
