# Android

Agora can securely integrate with Android — both as **tools the model can call** (location, contacts, calendar, device state, installed apps) and as **system-level entry points** into the app itself (digital assistant, share target). This page covers the tools; see [Conversations](conversations.md#starting-a-conversation-from-outside-agora) for the assistant/share-target entry points.

## Available Tools

| Tool               | Purpose                                                        | Permission needed                            |
| ------------------ | -------------------------------------------------------------- | -------------------------------------------- |
| **Location**       | Retrieve the device's approximate or precise location          | Runtime (location)                           |
| **Contacts**       | Search and read contacts stored on the device                  | Runtime (contacts)                           |
| **Calendar**       | Read upcoming events and create new calendar entries           | Runtime (calendar)                           |
| **Device Info**    | Battery, ringer mode, network, storage, and other device state | None                                         |
| **Installed Apps** | List apps installed on the device                              | None (fdroid/GitHub builds only — see below) |

The model automatically discovers enabled tools and decides when they are useful during a conversation.

## Privacy & Permissions

Location, Contacts, and Calendar require standard Android runtime permissions — the first time the model attempts to use one, Agora requests the appropriate permission, and only when that tool is first needed.

Device Info and Installed Apps don't use runtime permissions at all: the values they read (battery, ringer mode, network type, storage, build info, installed package list) are exposed by Android without a permission dialog. Installed Apps has a separate, build-level restriction instead — see [Installed Apps](#installed-apps).

!!! note
    You can revoke runtime permissions at any time from your device's Android Settings.

## Setup

1. Go to **Settings → Android**
2. Enable the tools you want the model to use:
   - **Digital Assistant** _(system integration, not a model tool — see [Conversations](conversations.md#starting-a-conversation-from-outside-agora))_
   - **Device Info Tool**
   - **Installed Apps Tool** _(only shown as usable on fdroid/GitHub builds)_
   - **Location**
   - **Contacts**
   - **Calendar**
3. Grant the requested Android permissions when prompted (Location/Contacts/Calendar only).

Once enabled, the model can access these tools automatically whenever they're helpful during a conversation.

## Location

The Location tool allows the model to determine your device's current location.

Typical uses include:

- Finding nearby places
- Providing local weather information
- Location-aware recommendations
- Estimating travel times
- Answering questions about your current area

Depending on your device settings and granted permissions, the location may be approximate or precise.

## Contacts

The Contacts tool allows the model to search contacts stored on your device.

Typical uses include:

- Looking up phone numbers
- Finding email addresses
- Identifying saved contacts
- Selecting contacts for messaging or communication tasks

The model only accesses the contact information needed to fulfill your request.

## Calendar

The Calendar tool allows the model to read your calendar and create events.

Typical uses include:

- Checking your schedule
- Listing upcoming events
- Finding available time
- Creating appointments
- Reviewing meeting details

Creating or modifying events requires calendar write permission.

## Device Info

The Device Info tool reports the current state of your device in a single call:

- **Battery** — level %, charging state, status (charging/discharging/full/not charging), charge method (AC/USB/wireless/none), temperature
- **Ringer mode** — normal, vibrate, or silent
- **Network** — connection type (Wi-Fi, cellular, ethernet, VPN, other, none) and whether it's currently connected
- **Storage** — free and total internal storage
- **Device facts** — model, manufacturer, Android version, locale, timezone, current time, uptime

Typical uses include:

- "How much battery do I have left?"
- "Am I connected to Wi-Fi right now?"
- "How much free storage is left on my phone?"
- Any question where the model benefits from knowing your device's live state rather than guessing

Enabled by default — nothing on this list requires a runtime permission, so there's no permission prompt the first time it's used.

## Installed Apps

The Installed Apps tool lets the model list apps installed on your device (package name, app label, version, whether it's a system app) — useful for questions like "do I have Signal installed?" or "what apps do I have for editing PDFs?"

!!! warning "Not available on Google Play builds"
    This tool only works on **fdroid and GitHub-release builds** of Agora. It relies on Android's `QUERY_ALL_PACKAGES` permission, which Google Play only allows for apps whose _core purpose_ is inspecting installed apps (launchers, antivirus, file managers, device management) and requires a separate declared-use justification to Google for. Rather than pursue that for a single tool among many, the Play build simply never declares the permission — the toggle for this tool still appears in Settings, but greyed out with a "not supported in the current build" label, same treatment as the Local Sandbox setting.

If you installed Agora from F-Droid or a GitHub release, this tool works out of the box once enabled (opt-in, off by default — it does reveal your installed-apps list to the model). If you installed from Google Play, this tool is not available; switching to a non-Play build is the only way to use it.

## Security

Location, Contacts, and Calendar use Android's built-in runtime permission system:

- Runtime permission prompts before first use
- Permissions can be revoked at any time
- Disabled tools cannot be accessed
- All access occurs locally through Android's permission framework

Device Info and Installed Apps don't request runtime permissions, but are still gated by their own Settings toggle — disabling the toggle removes the tool from what the model can call, the same as any other tool here.

Agora cannot access protected data without your permission.

## Troubleshooting

### Permission denied

If the model reports it cannot access a tool:

- Verify the tool is enabled in **Settings → Android**
- Confirm the required Android permission has been granted
- If necessary, revoke and re-grant the permission in Android Settings

### Location unavailable

- Ensure Location Services are enabled on your device
- Move to an area with better GPS or network coverage
- Grant precise location if higher accuracy is required

### Calendar or Contacts are empty

Verify that your device contains calendar events or contacts and that the corresponding Android permission has been granted.

### Installed Apps toggle is greyed out

This is expected on Google Play builds — see [Installed Apps](#installed-apps) above. It's not a bug and there's no permission to grant; the feature simply isn't present in that build.
