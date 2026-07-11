# Android Tools

Agora can securely access selected Android system features when the model needs them. These tools let the model retrieve your current location, read contacts, or interact with your calendar while respecting Android's permission system.

## Available Tools

| Tool         | Purpose                                               |
| ------------ | ----------------------------------------------------- |
| **Location** | Retrieve the device's approximate or precise location |
| **Contacts** | Search and read contacts stored on the device         |
| **Calendar** | Read upcoming events and create new calendar entries  |

The model automatically discovers enabled tools and decides when they are useful during a conversation.

## Privacy & Permissions

Android Tools require standard Android runtime permissions.

The first time the model attempts to use one of these tools, Agora will request the appropriate Android permission. Permissions are only requested when a tool is first needed.

!!! note
    You can revoke permissions at any time from your device's Android Settings.

## Setup

1. Go to **Settings → Android**
2. Enable the tools you want the model to use:
   - **Location**
   - **Contacts**
   - **Calendar**
3. Grant the requested Android permissions when prompted.

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

## Security

Android Tools use Android's built-in permission system.

- Runtime permission prompts before first use
- Permissions can be revoked at any time
- Disabled tools cannot be accessed
- All access occurs locally through Android's permission framework

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
