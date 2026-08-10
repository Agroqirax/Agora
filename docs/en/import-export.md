# Import & Export

Open **Settings → Import & Export** to move or back up Agora data.

## Agora archives

A `.agora` file is a versioned ZIP archive (currently format version 4). Depending on the selected categories it can contain:

- conversations, runs, messages, tasks, loops, and related graph data
- attachments, tool media, and draft media
- memories and system prompts
- application settings and an imported custom font
- provider API keys and other secrets, only when explicitly selected

!!! warning "Protect archives that contain secrets"
    Included secrets are stored unencrypted inside the archive. Store and transfer that file as carefully as the original credentials.

On import, category conflicts can be handled with merge, replace, or skip behavior. Review the selected categories before confirming.

## Third-party imports

ChatGPT and Claude export ZIP files can be imported directly. Claude attachment records may contain metadata without the original attachment bytes, depending on the source export.

## Automatic backup

WorkManager can create periodic backups with a selected destination, schedule, category set, and retention policy. Android background scheduling is best-effort; battery policy and storage access can affect timing.

See [Privacy & Security](privacy.md).
