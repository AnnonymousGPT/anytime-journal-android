# Anytime Journal Lockscreen Input Design

## Goal

Build the first Android MVP for Daksh's anytime journal: a persistent notification with a reply input that can be used from the notification shade or lockscreen to save a timestamped journal or idea entry.

## Scope

This first slice only includes journal/idea capture. Task scheduling, checklist blocks, and Notion-like organization are later layers. The MVP must make capture fast and reliable before adding structure.

## Architecture

Create a new native Android app in `AnytimeJournalAndroid`. The app uses Kotlin, a single Activity, Room for local timestamped entries, and a BroadcastReceiver for notification replies. A helper builds one stable ongoing notification with a RemoteInput action, and the receiver saves the reply then reposts the notification so it stays ready.

## User Flow

1. User opens the app.
2. On Android 13+, the app requests notification permission.
3. The app posts an ongoing "Anytime Journal" notification.
4. User taps Reply from notification shade or lockscreen, types any idea/log, and submits.
5. The app saves non-empty text with the current device time.
6. The app list shows newest entries first.

## Data Model

Each entry has an auto id, raw text, and `createdAtMillis`. Empty or whitespace-only replies are ignored.

## Error Handling

If notification permission is denied, the app explains that the lockscreen input needs notification permission. If a reply is blank, the app keeps the notification alive and does not save an entry. Receiver work uses `goAsync()` and a short IO coroutine so the broadcast is not blocked.

## Testing

JVM unit tests cover the small input preparation logic: trimming text, rejecting blank replies, and stamping accepted replies with the provided clock value. Build verification compiles the app and runs unit tests when Gradle is available.
