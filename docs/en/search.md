# Conversation Search

Open **Settings → Conversation Search** to configure semantic access to previous conversations.

## Access

**Access Past Conversations** is enabled by default. Disable it to prevent the model/tool path from retrieving prior conversation content. Active memory and saved-memory permissions are configured separately.

## Retrieval

Configure:

- a context range from 4–32 conversation steps
- 5–30 returned results
- a similarity threshold from 0–1 (default 0.5)
- the embedding provider/model

Retrieved content is stored locally in the search index, but remote embedding models receive text that must be embedded. Relevant results can also be included in a configured model request.

See [Embedding / RAG](embedding.md), [Memory & Cache](memory.md), and [Privacy & Security](privacy.md).
