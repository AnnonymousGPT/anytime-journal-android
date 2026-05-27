# Anytime Journal Cloud Chat

Use this when Daksh and Sid are on different networks.

1. Open Supabase Dashboard and create a free project.
2. Open SQL Editor and run `supabase/anytime_collab_schema.sql`.
3. Copy the Project URL and anon/public key from Project Settings > API.
4. Configure both connected phones:

```powershell
.\scripts\configure-cloud-chat.ps1 `
  -ProjectUrl "https://PROJECT_REF.supabase.co" `
  -AnonKey "YOUR_ANON_OR_PUBLIC_KEY"
```

The app still keeps LAN relay available for same-network testing, but cloud sync becomes active on any network once both devices have the same Project URL and key.

Current cloud chat architecture:

- `collab_entries`: shared chat/note messages.
- `collab_presence`: online/away status.
- `collab_profiles`: registered usernames shown in the user list.

The Android app uses Supabase REST polling so it works across different networks without the phones being on the same Wi-Fi. Active chat checks for new messages about every second; true push notifications while the app is fully stopped would need FCM later.
