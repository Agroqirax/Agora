# Android

Agora can securely integrate with Android — both as **tools the model can call** (location, contacts, calendar, device state, apps, notifications, media, and more) and as **system-level entry points** into the app itself (digital assistant, share target). This page covers the tools; see [Conversations](conversations.md#starting-a-conversation-from-outside-agora) for the assistant/share-target entry points.

## Available Tools

| Tool                | Purpose                                                        | Permission needed                                                   |
| ------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------- |
| **Location**        | Retrieve the device's approximate or precise location          | Runtime (location)                                                  |
| **Contacts**        | Search and read contacts stored on the device                  | Runtime (contacts)                                                  |
| **Calendar**        | Read upcoming events and create new calendar entries           | Runtime (calendar)                                                  |
| **Alarms & Timers** | Set alarms and timers, and dismiss/snooze them                 | None (normal permission)                                            |
| **Media Control**   | See what's playing and control playback (play/pause/skip)      | None (notification access)                                          |
| **Notifications**   | Read, reply to, dismiss, and create notifications              | None (notification access, except creating Agora's own — see below) |
| **Torch**           | Turn the flashlight on or off                                  | None                                                                |
| **Weather**         | Current conditions and forecast for a location                 | Runtime (location), only if auto-detecting                          |
| **Device Info**     | Battery, ringer mode, network, storage, and other device state | None                                                                |
| **Apps**            | List installed apps and open one by package name               | None                                                                 |
| **Open URL**        | Open a URL in the browser (or whichever app handles it)        | None                                                                |
| **Calculator**      | Evaluate math expressions precisely                            | None                                                                |

The model automatically discovers enabled tools and decides when they are useful during a conversation.

## Privacy & Permissions

Location, Contacts, and Calendar require standard Android runtime permissions — the first time the model attempts to use one, Agora requests the appropriate permission, and only when that tool is first needed.

Alarms & Timers doesn't use a runtime permission either: setting an alarm/timer is a "normal" Android permission that's granted automatically at install, and the action itself is handed off to whatever clock app is installed on the device (Google Clock, Samsung Clock, etc.) rather than touching any protected data.

Device Info and Apps don't use runtime permissions at all: the values they read (battery, ringer mode, network type, storage, build info, installed package list) are exposed by Android without a permission dialog — Apps only ever sees apps with a home-screen icon, via a permission-free package-visibility exception, so there's no Google Play restriction either. Open URL and Media Control likewise don't request a runtime permission — Media Control uses the same notification-access grant as [Notifications](#notifications) below.

!!! note
You can revoke runtime permissions at any time from your device's Android Settings.

## Setup

1. Go to **Settings → Android**
2. Enable the tools you want the model to use:
   - **Digital Assistant** _(system integration, not a model tool — see [Conversations](conversations.md#starting-a-conversation-from-outside-agora))_
   - **Device Info Tool**
   - **Apps Tool** _(list + open apps, one switch)_
   - **Open URL Tool**
   - **Location**
   - **Contacts**
   - **Calendar**
   - **Alarms & Timers**
   - **Media Control**
   - **Notifications Tool**
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

## Apps

The Apps tool lets the model list apps installed on your device (package name, app label, version, whether it's a system app) and open one — useful for questions like "do I have Signal installed?" or "open Spotify". Listing and opening share a single **Apps Tool** switch in Settings, since discovering a `package_name` (list) and acting on it (open) are two halves of the same capability.

Typical uses include:

- "Do I have Signal installed?"
- "What apps do I have for editing PDFs?"
- "Open Spotify" / "Open com.spotify.music"

It only ever sees apps with a home-screen (launcher) icon — the same set you could tap from your home screen — via Android's package-visibility exception for `ACTION_MAIN`/`CATEGORY_LAUNCHER` apps, which needs no permission dialog and no `QUERY_ALL_PACKAGES` declaration. That's why this tool works identically, with no restriction, on every build including Google Play — it never touches the broader "every installed package" permission other apps sometimes need.

Opening an app isn't gated behind a confirmation prompt — switching the foreground app is the same as tapping its home-screen icon, not a destructive action.

This tool is opt-in and off by default — it does reveal your list of installed apps to the model.

## Open URL

The Open URL tool lets the model open a link in your browser (or whichever app is registered to handle it — e.g. a `market://` or deep link opening its own app), the same as tapping the link yourself.

Typical uses include:

- "Open the GitHub page for this project"
- Opening a link the model found via web search
- Handing off to another app that registers its own URL scheme

Like Apps, this isn't gated behind a confirmation prompt — it's the same trust boundary as switching the foreground app.

## Media Control

The Media Control tool lets the model see what's currently playing (app, track title, artist, album, position) and control playback — play, pause, skip, seek, etc. — on whatever music, podcast, or video app currently holds the active media session.

Typical uses include:

- "What's playing right now?"
- "Skip this song" / "Pause the music"

It needs the same **notification access** grant as [Notifications](#notifications) below (Android exposes the active media session through that API), so enabling either one prompts you to grant it once, covering both.

## Notifications

The Notifications tool lets the model read, tap, dismiss, or snooze notifications from any app, and create Agora's own notifications. It's really two capabilities bundled into one tool because they share a single mental model ("notifications on this device"):

- **Reading/acting on other apps' notifications** — listing what's currently showing, getting one notification's full details and available actions, tapping an action or reply field, dismissing, or snoozing — needs the same **notification access** grant as [Media Control](#media-control).
- **Creating Agora's own notifications** only needs the ordinary Android 13+ notification permission (auto-granted on older versions) — it works even if you never grant notification-listener access, since it never needs to read anything.

Typical uses include:

- "What notifications do I have right now?"
- "Reply 'on my way' to that message notification"
- "Dismiss all my notifications from that app"
- "Notify me when this task is done" (Agora's own notification)

Listing/reading a notification isn't gated, the same as any other read-only tool — but tapping an action, replying, or dismissing another app's notification does show a confirmation prompt by default (it's acting on something you haven't necessarily seen yet), which you can turn off in **Settings → Android → Notifications**.

## Security

Location, Contacts, and Calendar use Android's built-in runtime permission system:

- Runtime permission prompts before first use
- Permissions can be revoked at any time
- Disabled tools cannot be accessed
- All access occurs locally through Android's permission framework

Device Info, Apps, and Open URL don't request runtime permissions, but are still gated by their own Settings toggle — disabling the toggle removes the tool from what the model can call, the same as any other tool here. Media Control and the notification-reading half of Notifications instead require the notification-listener grant described above.

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

### Apps tool doesn't show an app I have installed

The Apps tool only sees apps with a home-screen (launcher) icon — see [Apps](#apps) above. A background service or component-only package with no launcher entry won't appear, by design.
