# Models

Open **Settings → Models** to control which models Agora can use.

## Default model

Choose the global default used when a conversation does not have its own selection. A conversation can still select another enabled model from the chat picker.

## Fetched models

Sync models from configured providers, search the results, and enable or disable individual models with their checkboxes. Synchronization and available metadata are provider-dependent.

## Custom models

Add, edit, alias, and delete custom model entries when a provider does not list the desired identifier. Each model belongs to a provider so Agora can resolve the correct protocol, base URL, and credentials.

Disabling or deleting an entry removes it from selection; it does not delete remote provider data. Imported GGUF files have their own deletion behavior under [Local Models](local-model.md).

See [Providers](provider.md) and [Generation](generation.md).
