> 繁體中文：[docs/zh-Hant/PRIVACY.md](docs/zh-Hant/PRIVACY.md)

# Privacy

QuietInbox is designed so that your message copies never leave your device unless **you** move them.

## What the app can access
- The text and metadata of notifications posted by the apps you enable, through Android's
  `NotificationListenerService`. Nothing is read from the source apps' own storage.
- Images referenced by those notifications (`content://` URIs or embedded bitmaps), only while
  "Copy images" is on. QuietInbox never downloads anything; a source app's content provider may
  fetch data itself when a URI is read, which the app discloses before enabling the feature.

## What the app never does
- No `INTERNET` permission; no analytics, advertising or crash-reporting SDK.
- No automatic system backup or device-to-device transfer of the vault (`allowBackup=false`,
  data-extraction rules exclude everything).
- No acting on notifications: no `contentIntent`, `deleteIntent`, `RemoteInput`, no cancelling,
  no marking as read.
- No logging of message bodies, titles, URIs or URLs; local diagnostics contain codes and counts only.

## Where data lives
- An SQLCipher database and encrypted media blobs in the app's private storage, keyed by a random
  per-installation key wrapped by the Android Keystore.
- Non-sensitive preferences in DataStore.

## What leaves the device
- Only an encrypted backup you export to a location you choose (which may be a cloud folder),
  readable only with your recovery key; or content you explicitly share or copy.

## Retention
- Copies expire after the retention period you set (default 30 days). Deletion removes rows and
  files but cannot promise physical overwrite of flash storage; "Delete all data" also destroys the keys.
