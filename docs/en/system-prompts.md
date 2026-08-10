# System Prompts

Open **Settings → System Prompts** to manage reusable prompt configurations.

## Create and edit

New prompts start from either **Blank** or **Default**. The editor contains ordered segments for:

- System
- User Prefix
- User Suffix

Segments can contain plain text and supported variables, and can be reordered. Preview the resolved prompt before saving.

Current variables include `{time}`, `{date}`, `{sent_time}`, `{sent_date}`, `{active_memory}`, and `{model_id}`. Values are resolved when the prompt is used.

## Manage and select

Prompts can be edited, duplicated, deleted, and marked as the global default. A conversation can inherit that default or select another saved prompt.

The built-in Default template is defined by the app and can change with product behavior; it is not one of several fictional category libraries.
