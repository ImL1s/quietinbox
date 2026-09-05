# `:parsers:apps` — per-app notification adapters

Five versioned adapters for the sources listed in `KnownSources`, plus the shared
`AppParser` skeleton they all extend. Every adapter is a pure function of one
`NotificationSnapshot`: no clock, no IO, no database, no `Context`.

| Adapter | `id` | Package | Version |
| --- | --- | --- | --- |
| `LineParser` | `line` | `jp.naver.line.android` | `0.1.0` |
| `WhatsAppParser` | `whatsapp` | `com.whatsapp` | `0.1.0` |
| `TelegramParser` | `telegram` | `org.telegram.messenger` | `0.1.0` |
| `InstagramParser` | `instagram` | `com.instagram.android` | `0.1.0` |
| `MessengerParser` | `messenger` | `com.facebook.orca` | `0.1.0` |

`AppParsers.all()` returns them in that order. `ParserRegistry(AppParsers.all())`
routes each package to its adapter and everything else to `StandardParser`.

## Evidence status

**Every phrase, prefix and suffix in this module is `SYNTHETIC_ONLY`.** They are
invented guesses at common wording, written from scratch for this repository. None
of them was copied from another product, extracted from an APK, or taken from a
reverse-engineering report. Nothing here has been validated against a real device
capture, so no rule may be treated as fixture-proven until a real fixture exists.

Consequences of that status, enforced by `AppParsersRegistryTest`:

* No adapter ever sets `MessageCandidate.sourceMessageId`.
* No adapter ever emits `IdentityEvidenceKind.SOURCE_CHAT_ID`, and no adapter ever
  emits any evidence at `Confidence.VERIFIED`.
* Conversation keys stay at `ConversationKey.ShortcutId` (`Confidence.INFERRED`,
  inherited from `StandardParser`) or `NotificationStream`.

Adapters also never merge conversations, never invent a timestamp, and never
rewrite body text. The only text change is dropping a `Sender:` prefix while
splitting, and the resulting sender and body are both trimmed.

## Input surface

Only fields already present in `NotificationShape` are read: `messages`,
`historicMessages`, `textLines`, `bigText`, `text`, `title`, `shortcutId`, `tag`,
`groupKey`, `isGroupSummary`, `isGroupConversation`, `conversationTitle`,
`category`, `template`, `isOngoing` and `hasPicture`. There is no reflection, no
`RemoteViews` scraping and no private extra.

## Shared rules (`AppParser`)

Applied in this order, before any app-specific behaviour:

1. **Group summaries** (`isGroupSummary`) go straight to `StandardParser`.
2. **System notices** — `isOngoing`, `category == "call"`, `template == CALL`, the
   shared `TextHeuristics.looksLikeSystemNotice` rules applied to the body, or one
   of the adapter's own notice phrases/prefixes applied to the body or title.
   * If the notification carries **no** structured items, the batch has zero
     messages, `ParseWarning.POSSIBLE_SYSTEM_NOTICE`, no conversation and no
     identity evidence. `contentStatus` is `NOTIFICATION_TEXT` when any text was
     present, otherwise `EMPTY`.
   * If it **does** carry `MessagingStyle` messages or inbox lines, they are parsed
     normally and only the warning is added. Structured content is never discarded.
   * Generic notice wording is matched against the body only. Titles are matched
     against the adapter's own phrases only, so a contact name cannot silence a chat.
3. **Bare summary lines** — a body that `TextHeuristics.parseSummary` recognises,
   in a notification with no `messages`, `historicMessages` or `textLines`, becomes
   a populated `SummaryObservation` with `ContentStatus.SUMMARY_ONLY` and zero
   messages. `MULTIPLE_CONVERSATIONS_SUSPECTED` is added when the parsed
   conversation count is greater than one. (`StandardParser` returns
   `UNKNOWN_FORMAT` with `SUMMARY_WITHOUT_ITEMS` and no observation here.)
4. **Unmodelled templates** — anything outside `MESSAGING`, `BIG_TEXT`, `INBOX`,
   `BIG_PICTURE` and `BASE` is delegated to `StandardParser` and the result carries
   `ParseWarning.ADAPTER_FALLBACK_TO_STANDARD`.
5. Otherwise the standard pipeline runs, with the adapter hooks below.

`AppParser` also adds per-app preview placeholders. Matching folds the body with
`trim` + trailing `.`/`。`/`…` removal + lowercase, then compares against whole
phrases or suffixes. A match sets `ContentStatus.PREVIEW_RESTRICTED_SUSPECTED` and
raises `PREVIEW_PLACEHOLDER`; the body itself is kept unchanged.

`singleCandidate`, `inboxCandidates` and `messagingCandidates` are `final` here so
that app rules run exactly once. Adapters extend `appSingleCandidates` (plain-text
path) and `postProcess` (all paths) instead.

## Per-adapter rules

### LINE

* Extra hidden-preview phrases: `您有新的訊息`, `你有新的訊息`, `有新的訊息`,
  `新着メッセージ`, `new message received`.
* Extra notice phrases: `語音通話中`, `視訊通話中`, `正在通話中`, `call in progress`.
* **Spaced-colon group split.** A one-item plain body of the form
  `Sender : text` — spaces on *both* sides of the colon — is split into sender and
  body even when `isGroupConversation` is not set, adding `SENDER_SPLIT_HEURISTIC`.
  The sender part must be non-empty, at most 48 characters and free of newlines.
  A declared `isGroupConversation == false` is a known 1:1 chat and is never split.
  Requiring the spaced form is what keeps `12:30 見` intact.

### WhatsApp

* Extra hidden-preview phrases: `您可能有新訊息`, `你可能有新訊息`,
  `you have unread messages`.
* Notice phrases: `checking for new messages`, `正在檢查新訊息`,
  `whatsapp web is currently active`, `whatsapp web 目前已啟用`. Notice prefixes:
  `backing up`, `backup`, `restoring`, `正在備份`, `正在還原`.
  `checking for new messages` also appears in the shared placeholder list; the
  adapter deliberately classifies it as a status notice instead, which yields zero
  messages rather than one placeholder message.
* **Multi-line group split.** A plain body of two or more non-blank lines where
  *every* line splits via `TextHeuristics.splitSenderPrefix` becomes one candidate
  per line, in order, with `SENDER_SPLIT_HEURISTIC`. It requires either
  `isGroupConversation == true`, or at least two distinct sender prefixes none of
  which equals the notification title. Candidates keep
  `ContentStatus.NOTIFICATION_TEXT` because they were derived from plain text, and
  they share the single notification timestamp — none is invented.

### Telegram

* `MessagingStyle` is the normal path and is left entirely to `StandardParser`.
* Extra hidden-preview phrases: `message hidden`, `hidden message`,
  `訊息內容已隱藏`.
* Notice phrases: `sending`, `connecting`, `updating`, `正在連線` (trailing `...`
  and `…` are folded away first). Notice prefixes: `uploading`, `downloading`,
  `sending photo`, `sending video`, `正在上傳`, `正在傳送`.
* Collapsed multi-chat notifications (`N new messages from M chats`) are handled by
  the shared summary rule and therefore raise `MULTIPLE_CONVERSATIONS_SUSPECTED`.

### Instagram

* Hidden-preview **suffixes** (the sender name varies): `sent you a message`,
  `傳送了一則訊息給你`, `向你傳送了一則訊息`.
* **Group thread split.** A one-item plain body of the form `Alice: hey` is split
  when the prefix differs from the notification title, adding
  `SENDER_SPLIT_HEURISTIC`. A 1:1 thread repeats the contact name in the title, so
  `title = Alice` with body `Alice: hey` is left intact.
* **Reactions** are kept verbatim and marked `MessageKind.SYSTEM` with
  `POSSIBLE_SYSTEM_NOTICE`, because people want to see them but they are not chat
  messages. The rule is deliberately narrow: `liked your message`, `讚了你的訊息`,
  or `reacted` together with `your message`, or `回應` together with `你的訊息`.
  A bare `reacted` is not enough.

### Messenger

* `MessagingStyle` is the normal path.
* Hidden-preview suffixes: `sent you a message`, `傳送了一則訊息給你`.
* **Attachment wording** — a body ending in `sent a photo`, `sent you a photo`,
  `sent a video`, `sent you a video`, `sent an attachment`, `傳送了一張相片`,
  `傳送了一張照片`, `傳送了一段影片` or `傳送了一個檔案` becomes
  `MessageKind.MEDIA` with a `MediaReferenceCandidate` whose only content is
  `fromNotificationBitmap = shape.hasPicture`. No URI and no MIME type are
  invented, and the body text is preserved. A candidate that already carries a
  media reference (a real `content://` URI from `MessagingStyle`) is left untouched.

## Known false positives

* **WhatsApp multi-line split.** A single multi-line message whose every line
  happens to start with a short `Word: ` prefix — for example
  `Note: buy milk` / `Also: eggs` — satisfies the "two distinct prefixes" branch
  and would be split into two candidates attributed to `Note` and `Also`.
  `SENDER_SPLIT_HEURISTIC` marks the batch so downstream can discount it, but the
  gate should be replaced by a real device-fixture signal before this rule is
  trusted.
* **Instagram single-line split.** The only gate is "the prefix differs from the
  title", and `TextHeuristics.splitSenderPrefix` accepts any non-numeric prefix of
  up to 48 characters. A 1:1 chat titled `Alice` with the body
  `Reminder: meeting at 5` is therefore split into sender `Reminder` and body
  `meeting at 5`. This gate is weaker than the WhatsApp one and needs a real
  device fixture before it is trusted.
* **Call bodies are dropped, not represented.** The shared notice rule applies
  `TextHeuristics.looksLikeSystemNotice` to the body, so a plain `Missed call` or
  `未接來電` body with no `category` yields zero messages from all five adapters,
  where `StandardParser` keeps one message. `MessageKind.CALL` exists in the model
  and may be the better home for these; that decision is still open.
* **Placeholder suffixes.** A genuine message ending in `sent you a message`
  (someone quoting the phrase) is marked `PREVIEW_RESTRICTED_SUSPECTED`. The body
  is still stored unchanged, so the cost is a soft-quality flag, not data loss.
* **Telegram `sending`.** A one-word message body `Sending` in a notification with
  no structured items would be classified as a transfer notice and dropped.

## Tests

`parsers/apps/src/test/kotlin/dev/quietinbox/parsers/apps/` — one Kotest `FunSpec`
per adapter plus `AppParsersRegistryTest`, all built from `Fixtures`. Several cases
assert the adapter's output *and* `StandardParser`'s output on the same snapshot,
so a rule that stopped doing anything would fail rather than silently pass.

```
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :parsers:apps:test --console=plain
```
