# Anytime Journal Architecture

Keep the app simple by giving each layer one job.

## Ownership

- `MainActivity`: lifecycle, permissions, root wiring, and screen state.
- `MainScreenRenderer`, `PageRenderer`, `EntryListRenderer`, `CaptureController`, `CollabUiController`: UI rules and rendering helpers.
- `AppPrefs`: one source for saved username, Supabase config, relay URL, and foreground chat state.
- `EntryRepository`, `AppDatabase`, DAOs: local Room storage.
- `CollabSyncManager`: chat entries, profiles, presence, and typing sync.
- `VoiceCallManager`, `CallTranscriptionManager`: WebRTC call state and live transcript sync.
- `BubbleInputService`: overlay bubble, background popup/reply, and background collab polling.
- `NotificationHelper`, `TaskReminderScheduler`, `ScheduledCollabScheduler`: Android notifications and alarms.

## Screen Flow

1. First launch opens username setup.
2. App shell shows one of five pages: All, Journal, Ideas, Tasks, Collab.
3. Bottom capture bar owns input, kind, selected time, and CTA.
4. Entries always save locally first.
5. Collab entries also broadcast to Supabase when cloud config exists.
6. Background bubble reads the same `AppPrefs` foreground state to avoid duplicate popups while chat is open.

## Next Refactor Rule

Do not add new prefs/constants inside Activity or services. Add them to `AppPrefs`.

Do not put new networking in UI files. Add it to `CollabSyncManager` or a new manager.

Do not add more rendering logic to `MainActivity`. Move page-specific UI into renderer/controller files.
