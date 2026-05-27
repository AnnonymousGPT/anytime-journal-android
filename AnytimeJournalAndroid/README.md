# Anytime Journal Android

Minimal Android journal, task, idea, bubble input, reminders, and live collab chat app.

## Features

- Quick capture for journal, ideas, and tasks.
- Persistent notification input with direct reply.
- Lockscreen-friendly focus prompt notification.
- Floating bubble input and chat/call popup.
- Journal timeline, task reminders, and mention routing.
- Supabase-backed live collab notes/chat with online users.
- WebRTC call flow with live transcription state.

## Build

Open this folder in Android Studio, or build from PowerShell:

```powershell
gradle testDebugUnitTest assembleDebug --console=plain
```

If using the local Gradle install from this machine:

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat" testDebugUnitTest assembleDebug --console=plain
```

## Install

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

## Cloud Collab

Run the SQL in:

```text
supabase/anytime_collab_schema.sql
```

Then set the same Supabase project URL and anon/public key inside the app on every device.

More details:

```text
supabase/SETUP.md
```

## Android Permissions

For full experience, allow:

- Notifications
- Display over other apps
- Microphone
- Exact alarms, if the phone asks
- Battery/background exceptions, if the OEM kills background services

Secure Android lockscreens may require unlock before allowing direct reply. The app uses the most reliable allowed approach: high-priority lockscreen RemoteInput notification.
