# Android

Agora can securely integrate with Android — both as **tools the model can call** (location, contacts, calendar, device state, installed apps) and as **system-level entry points** into the app itself (digital assistant, share target). This page covers the tools; see [Conversations](conversations.md#starting-a-conversation-from-outside-agora) for the assistant/share-target entry points.

## Available Tools

| Tool               | Purpose                                                        | Permission needed                            |
| ------------------ | -------------------------------------------------------------- | -------------------------------------------- |
| **Location**       | Retrieve the device's approximate or precise location          | Runtime (location)                           |
| **Contacts**       | Search and read contacts stored on the device                  | Runtime (contacts)                           |
| **Calendar**       | Read upcoming events and create new calendar entries           | Runtime (calendar)                           |
| **Alarms & Timers**| Set alarms and timers, and dismiss/snooze them                 | None (normal permission)                     |
| **Media Control**  | See what's playing and control playback (play/pause/skip)      | None (notification access)                   |
| **Torch**          | Turn the flashlight on or off                                  | None                                         |
| **Weather**        | Current conditions and forecast for a location                 | Runtime (location), only if auto-detecting |
| **Device Info**    | Battery, ringer mode, network, storage, and other device state | None                                         |
| **Installed Apps** | List apps installed on the device                              | None (fdroid/GitHub builds only — see below) |
| **Calculator**     | Evaluate math expressions precisely                             | None                                         |

The model automatically discovers enabled tools and decides when they are useful during a conversation.

## Privacy & Permissions

Location, Contacts, and Calendar require standard Android runtime permissions — the first time the model attempts to use one, Agora requests the appropriate permission, and only when that tool is first needed.

Alarms & Timers doesn't use a runtime permission either: setting an alarm/timer is a "normal" Android permission that's granted automatically at install, and the action itself is handed off to whatever clock app is installed on the device (Google Clock, Samsung Clock, etc.) rather than touching any protected data.

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
   - **Alarms & Timers**
   - **Media Control**
   - **Torch**
   - **Weather**
   - **Calculator**
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

## Alarms & Timers

The Alarms & Timers tool lets the model set a device alarm or start a countdown timer, open the clock app's alarm list, and — on Android 10+ with a supporting clock app — dismiss or snooze an alarm.

Typical uses include:

- "Wake me up at 7am"
- "Set a repeating alarm for weekdays at 6:30"
- "Start a 10 minute timer for the pasta"
- "What alarms do I have set?"
- "Dismiss the alarm" / "Snooze it for 5 more minutes"

Unlike Calendar/Contacts, this doesn't read or write any personal data — it just hands the request to your device's clock app, the same as a voice assistant would. By default, a confirmation prompt still appears before an alarm or timer is actually set, dismissed, or snoozed; you can turn this off in **Settings → Android → Alarms & Timers**.

## Torch

The Torch tool lets the model turn your device's flashlight on or off, or toggle it if it isn't sure of the current state.

Typical uses include:

- "Turn on the flashlight"
- "Turn off the torch"
- "Toggle the flashlight" / checking whether it's currently on

It doesn't require any permission — Android exposes torch control to any app — so there's no permission prompt and no confirmation gate, the same treatment as Media Control. If another app currently has the camera open, the model is told the torch can't be controlled right now rather than the request silently failing.

## Calculator

The Calculator tool lets the model evaluate math expressions itself instead of computing them by hand, which avoids transcription and arithmetic slips on longer or more precise calculations.

Typical uses include:

- "What's 18% of $284.50?"
- "Convert 98.6°F to Celsius"
- Any multi-step calculation embedded in a larger question

It supports `+ - * / % ^`, parentheses, the constants `pi`/`e`, and common functions (`sqrt`, `abs`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `ln`, `log10`, `log2`, `exp`, `floor`, `ceil`, `round`). Expressions are evaluated by a small built-in parser — never `eval`-style code execution — so it only ever produces a number. It doesn't touch any device state or personal data, so like Device Info and Torch, there's no permission prompt and no confirmation gate.

## Weather

The Weather tool gives the model current conditions and a multi-day forecast, via the free [Open-Meteo](https://open-meteo.com) API.

The model can pick a location three ways:

- Nothing at all — for "the weather" or anywhere the user means their current location, it automatically uses your device's location, without needing a separate location lookup first
- A free-text place name it names itself (e.g. "weather in Lisbon")
- Coordinates it already has (e.g. from the Location tool), for a specific place other than where the user is now

For automatic location, it uses the exact same permission request and "confirm before sharing location" flow as the Location tool (fine location if granted and available, else coarse) — it does not have a separate no-permission fallback, so the first time it needs to auto-detect, expect the same permission prompt the Location tool would show.

You can set your preferred units (metric/imperial) in **Settings → Android → Weather**. Open-Meteo is also self-hostable, so the forecast and geocoding server URLs can be overridden there too, though the defaults work for virtually everyone.

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
