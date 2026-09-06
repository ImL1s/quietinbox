## What / 做了什麼

<!-- One paragraph. Link the issue: Refs #N. / 一段話說明。附上 issue：Refs #N。 -->

## Hard rules / 硬規則

- [ ] No `INTERNET` permission or SDK that merges one (`tools/check-permissions.sh` passes) / 沒有 `INTERNET` 權限，也沒有會合併進該權限的 SDK（`tools/check-permissions.sh` 通過）
- [ ] Never acts on a source notification (no reply / dismiss / mark-read / `PendingIntent`) / 絕不對來源通知採取動作（不回覆／不清除／不標已讀／不觸發 `PendingIntent`）
- [ ] No real-app notification captures, decompiled sources or vendor assets added / 沒有加入真實 App 的通知擷取、反編譯原始碼或廠商素材
- [ ] Strings added to all five catalogues (`values/`, `values-b+zh+Hant/`, `values-b+zh+Hans/`, `values-ja/`, `values-ko/`) — `python3 tools/check-strings.py` passes; docs in both languages where they exist / 字串已加進全部五份目錄，`python3 tools/check-strings.py` 通過；已有雙語版本的文件要兩邊都改

## Evidence / 證據

- [ ] `./gradlew test` green (count: ) — new tests listed below / `./gradlew test` 全綠（數量：），新增的測試列在下面
- [ ] `python3 tools/check-strings.py` and `./gradlew :app:lintDebug` green (both are hard CI gates) / `python3 tools/check-strings.py` 與 `./gradlew :app:lintDebug` 全綠（兩者都是 CI 硬關卡）
- [ ] Instrumented tests, if storage / crypto / backup changed (`connectedDebugAndroidTest` on an emulator) / 若動到 storage／crypto／backup，要在模擬器上跑 `connectedDebugAndroidTest`
- [ ] Device walkthrough of every user-visible change (which device / AVD:) / 每一項使用者看得到的變更都在裝置上走過（裝置／AVD：）
- [ ] Screenshots re-taken if a screen changed, and each one checked to be a QuietInbox screen in the right language / 畫面有變就重拍截圖，並逐張確認拍到的是正確語言的 QuietInbox 畫面
- [ ] `docs/SCOPE.md`, `docs/TEST_MATRIX.md`, `CHANGELOG.md` updated — docs never run ahead of code / 已更新 `docs/SCOPE.md`、`docs/TEST_MATRIX.md`、`CHANGELOG.md`——文件絕不超前程式碼
- [ ] Schema changed → `platform/storage/schemas/<n>.json` exported, migration + `MigrationTest` added, no destructive migration / schema 有變 → 匯出 `platform/storage/schemas/<n>.json`、加上 migration 與 `MigrationTest`，不得有破壞性 migration
- [ ] Dependencies changed → `gradle/verification-metadata.xml` regenerated from a cold cache (`docs/RELEASE.md`) / 依賴有變 → 以冷快取重新產生 `gradle/verification-metadata.xml`（見 `docs/RELEASE.md`）

## Review / 審查

<!-- Independent review round (docs/reviews/README.md): reviewers, verdicts, fix commit.
     獨立審查輪次（docs/reviews/README.md）：審查者、結論、修正 commit。 -->
