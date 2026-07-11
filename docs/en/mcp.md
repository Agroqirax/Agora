# MCP Servers

Agora can connect to [Model Context Protocol](https://modelcontextprotocol.io) servers and let the model call the tools they expose — search engines, databases, home automation, internal company APIs, or anything else you or a third party has built an MCP server for.

!!! note
    Agora currently supports MCP **tools** only. Resources, prompts, and sampling are not yet implemented.

## How It Works

```text
Agora (Android)  ──HTTPS (Streamable HTTP transport)──▶  MCP Server
                                                          │
                                                          ├── initialize
                                                          ├── tools/list
                                                          └── tools/call
```

Agora speaks the MCP **Streamable HTTP** transport (a single HTTP endpoint, not stdio or the older HTTP+SSE transport). On first use it opens a session with `initialize`, lists the server's tools with `tools/list`, and lets the model invoke them with `tools/call`. The tool list is cached for about 30 seconds per server so repeated messages don't re-handshake every time; if the server ends the session, Agora reconnects automatically on the next call.

The model decides when to use an MCP tool on its own, the same way it decides to use web search or the shell — there's no manual trigger.

## Security

MCP servers you add are arbitrary code you're choosing to trust with tool access, so Agora treats them cautiously by default:

- **Read-only tools run without asking.** If a server marks a tool with `readOnlyHint`, Agora calls it automatically.
- **Everything else asks for confirmation.** If a tool isn't marked read-only, Agora treats it as potentially destructive — including tools that simply don't declare a `destructiveHint` at all — and shows a confirmation dialog with the tool name and arguments before running it.
- **"Always allow this server"** lets you skip the prompt for the rest of the session. This resets when Agora restarts.
- **Authentication is sent only to the server you configured.** A Bearer token or custom header you add is sent solely to that server's URL.

!!! warning
    If a server's URL uses plain `http://` instead of `https://`, any Bearer token or header you configure travels unencrypted. Prefer `https://` endpoints, especially over untrusted networks.

## Setup

### Step 1: Get an MCP server

This can be a public MCP server, one your organization runs internally, or one you host yourself. It must expose the **Streamable HTTP** transport at a single URL (commonly ending in `/mcp`).

### Step 2: Add It in Agora

1. Go to **Settings → MCP Servers**
2. Enable **Enable MCP Tools**
3. Tap **Add Server**
4. Fill in the server details:

| Field             | Description                                                                                           | Example                           |
| ----------------- | ----------------------------------------------------------------------------------------------------- | --------------------------------- |
| **Name**          | Display name for this server                                                                          | `Home Assistant`                  |
| **Description**   | Optional note about what it's for. If left blank, the server's host is shown instead.                 | `Controls lights and thermostats` |
| **Server URL**    | The MCP Streamable HTTP endpoint                                                                      | `https://example.com/mcp`         |
| **Bearer Token**  | Optional — sent as `Authorization: Bearer <token>`                                                    | Your server's API token           |
| **Extra Headers** | Optional — one per line, as `Name: value`, for servers that expect auth or routing by a custom header | `X-Api-Key: secret`               |
| **Timeout**       | Per-request timeout, 5–120 seconds                                                                    | `30`                              |

5. Tap **Test Connection** to verify Agora can reach the server and see how many tools it exposes, before saving.
6. Tap **Save**.

Once a server connects successfully — either from Test Connection or from actual use in a chat — its name and version (as reported by the server) appear as a small badge next to its entry in the server list.

### Step 3: Use

Send a message that could use one of the server's tools. If the tool isn't read-only, you'll be prompted to allow it the first time; after that, it's remembered for the rest of the session (or until you deny it).

## Multi-Server Support

Add as many servers as you like — a search API, an internal ticketing system, a home automation hub. Each is configured and authenticated independently, and their tools are automatically namespaced (e.g. `mcp__home_assistant__turn_on_light`) so identically-named tools from different servers never collide.

Disabling **Enable MCP Tools**, or a single server's own **Enabled** checkbox, removes its tools from what the model can see without deleting its configuration.

## Confirming Destructive Tool Calls

You can turn confirmation prompts off entirely with **Confirm destructive MCP tool calls** in **Settings → MCP Servers** — read-only tools always run without asking regardless of this setting. Turning it off means every tool from every enabled server runs immediately with no prompt, so only disable it for servers you fully trust.

## Troubleshooting

### Test Connection fails

- Double-check the **Server URL** — it should be the full endpoint (e.g. `.../mcp`), not just the host
- If the server requires auth, verify the **Bearer Token** or **Extra Headers** are correct
- Confirm the server implements the Streamable HTTP transport, not stdio or the legacy HTTP+SSE transport
- Check that the URL is reachable from your device (not just from your desktop's network)

### The model never calls the tool

- Confirm **Enable MCP Tools** and the specific server's **Enabled** checkbox are both on
- Try **Test Connection** to confirm the server currently lists that tool
- Some models are more reluctant to call unfamiliar tools without a clear reason in the conversation — try being explicit about what you want done

### Requests keep timing out

- Increase the server's **Timeout** if its tools are slow (e.g. long-running searches or automations)
- A slow or overloaded server is retried at most once every 30 seconds rather than on every message, so a temporary outage won't repeatedly stall your conversation

### Confirmation prompt shows unexpected arguments

The confirmation dialog shows the exact arguments the model is about to send. If they look wrong, deny the call — the model will usually see the denial and adjust its next attempt.
