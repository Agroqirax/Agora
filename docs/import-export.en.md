# Data Portability

Agora stores all your data on-device and provides full import/export capabilities. You own your data — move it in, move it out, back it up.

## Export

Export your data to a single `.agora` file — a portable archive that contains everything Agora stores.

### What Gets Exported

You choose what to include:

| Category | Contents |
|----------|----------|
| **Conversations & Messages** | All chat history, message trees, branches |
| **Memories** | Active memory and all saved memory files |
| **System Prompts** | All custom system prompt templates |
| **Settings** | App configuration and preferences |
| **API Keys** | All configured API keys |

!!! danger "API Keys Warning"
    API keys are exported in **plain text**. Anyone with the `.agora` file can read your keys. Only enable API key export if you trust the destination and handle the file securely.

### How to Export

1. Go to **Settings → Data Control**
2. Tap **Export Data**
3. Select which categories to include
4. Tap **Export**
5. Choose where to save the `.agora` file

---

## Import

Restore data from a previous `.agora` export.

### Import Strategies

When importing, you choose how Agora handles data that already exists on your device:

| Strategy | Behavior |
|----------|----------|
| **Merge** | Add new items, keep existing ones. If an item with the same ID exists, the import version overwrites it. |
| **Replace** | Clear all existing data in the selected categories, then import. A fresh start. |
| **Skip** | Only import items that have no conflict. Existing items are untouched. |

!!! tip
    Use **Merge** for most cases — it safely adds new data while preserving what's already on your device.

### How to Import

1. Go to **Settings → Data Control**
2. Tap **Import Data**
3. Select a `.agora` file
4. Review the import preview — see what's in the file (export date, version, content counts)
5. Choose an import strategy
6. Tap **Import**

!!! danger "API Keys Warning"
    If the export file contains API keys, Agora warns you before importing. Keys are imported in plain text. Only proceed if you trust the source of the file.

---

## Third-Party Import

Import conversations from other AI chat platforms.

### Import from Claude

Import conversations from a Claude export `.zip` file:

1. Export your data from [Claude](https://claude.ai/) (Settings → Export Data)
2. In Agora, go to **Settings → Data Control → Third Party → Import from Claude**
3. Select the `.zip` file
4. Review the preview — see conversation count and message count
5. Choose **Merge** or **Replace** strategy
6. Tap **Import**

!!! note
    Claude exports with attachments are detected and shown in the preview. Attachments themselves are not imported — only the message text.

### Import from ChatGPT

Import conversations from a ChatGPT export `.zip` file:

1. Export your data from [ChatGPT](https://chatgpt.com/) (Settings → Data Controls → Export)
2. In Agora, go to **Settings → Data Control → Third Party → Import from ChatGPT**
3. Select the `.zip` file
4. Review the preview
5. Choose **Merge** or **Replace** strategy
6. Tap **Import**

!!! note
    Both user and assistant messages are imported. Message roles are preserved.

---

## File Format

The `.agora` file is a JSON-based archive. If you're technically inclined, you can inspect or process it with standard tools. The format is designed for forward and backward compatibility.

---

## Best Practices

- **Export regularly** as a backup — keep the file somewhere safe
- **Don't include API keys** in routine exports — enable key export only for full device migrations
- **Use Merge for incremental imports** — Replace is destructive
- **Preview before importing** — check the export date and content counts to confirm it's the right file
