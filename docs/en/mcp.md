# MCP Servers

Agora can connect to [Model Context Protocol](https://modelcontextprotocol.io) servers and let the model call the tools they expose — search engines, databases, home automation, internal company APIs, or anything else you or a third party has built an MCP server for.

!!! note
    Agora supports MCP **tools** and **resources**. Prompts and sampling are not yet implemented.

## How It Works

```text
Agora (Android)  ──HTTPS (Streamable HTTP transport)──▶  MCP Server
                                                          │
                                                          ├── initialize
                                                          ├── tools/list
                                                          └── tools/call

Agora (Android)  ──stdin/stdout (sandbox subprocess)───▶  MCP Server
                                                          │
                                                          ├── initialize
                                                          ├── tools/list
                                                          └── tools/call
```

Agora speaks two MCP transports:

- **Streamable HTTP** — a single HTTP endpoint (not the older HTTP+SSE transport). This is the default and works on every build.
- **Stdio** — a local command (e.g. `npx -y @modelcontextprotocol/server-filesystem /home/agora`) launched as a subprocess inside Agora's on-device Alpine sandbox, communicating over stdin/stdout. This only works on **F-Droid and GitHub-release builds** (it needs the [Local Sandbox](sandbox.md), which Google Play doesn't ship); the Play build always falls back to the HTTP fields for that server. Command output/env vars are process-local and never leave the sandbox except over whatever network calls the command itself makes.

Either way, on first use Agora opens a session with `initialize`, lists the server's tools with `tools/list`, and lets the model invoke them with `tools/call`. The tool list is cached for about 30 seconds per server so repeated messages don't re-handshake every time; if an HTTP server ends the session (or a stdio subprocess dies), Agora reconnects automatically on the next call.

The model decides when to use an MCP tool on its own, the same way it decides to use web search or the shell — there's no manual trigger.

## Resources

For servers that advertise the `resources` capability, Agora adds two extra read-only tools automatically: `list_resources` and `read_resource`. There's no separate resource-browsing UI — the model can call these the same way it calls any other tool, deciding on its own when a resource is relevant to what you asked. `list_resources` also surfaces any [resource templates](https://modelcontextprotocol.io/specification/server/resources#resource-templates) the server defines, so the model knows what URI patterns it can fill in before calling `read_resource`.

## Security

MCP servers you add are arbitrary code you're choosing to trust with tool access, so Agora treats them cautiously by default:

- **Read-only tools run without asking.** If a server marks a tool with `readOnlyHint`, Agora calls it automatically.
- **Everything else asks for confirmation.** If a tool isn't marked read-only, Agora treats it as potentially destructive — including tools that simply don't declare a `destructiveHint` at all — and shows a confirmation dialog with the tool name and arguments before running it.
- **"Always allow this server"** lets you skip the prompt for the rest of the session. This resets when Agora restarts.
- **Authentication is sent only to the server you configured.** A Bearer token, OAuth access token, or custom header you add is sent solely to that server's URL.

Agora supports different authentication methods.

| Type             | Best for                                                       |
| ---------------- | --------------------------------------------------------------- |
| **None**         | Public MCP servers that don't require authentication           |
| **Bearer Token** | API keys or personal access tokens                              |
| **OAuth 2.0**    | Servers that support OAuth, including automatic token refresh  |

!!! warning
    If a server's URL uses plain `http://` instead of `https://`, any Bearer token or header you configure travels unencrypted. Prefer `https://` endpoints, especially over untrusted networks.

## Setup

### Step 1: Get an MCP server

This can be a public MCP server, one your organization runs internally, one you host yourself, or a local command-line server you run via **stdio** (F-Droid/GitHub builds only — see [How It Works](#how-it-works)). An HTTP server must expose the **Streamable HTTP** transport at a single URL (commonly ending in `/mcp`).

Some common mcp servers include:

| Provider                                                                                                 | URL                                            | Auth method                                                                                                                |
| -------------------------------------------------------------------------------------------------------- | ---------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| [GitHub MCP](https://github.com/github/github-mcp-server)                                                | https://api.githubcopilot.com/mcp              | [PAT](https://github.com/settings/personal-access-tokens) as bearer or [OAuth app](https://github.com/settings/developers) |
| [Notion MCP](https://developers.notion.com/guides/mcp/get-started-with-mcp#connect-through-your-ai-tool) | https://mcp.notion.com/mcp                     | [OAuth](https://developers.notion.com/guides/mcp/build-mcp-client) with DCR                                                |
| [Homeassistant](https://www.home-assistant.io/integrations/mcp_server)                                   | http://homeassistant.local/mcp                 | [profile](https://my.home-assistant.io/redirect/profile/) → Security → Long-lived access tokens as bearer                  |
| [Ha-mcp](https://github.com/homeassistant-ai/ha-mcp)                                                     | http://homeassistant.local/api/webhook/mcp_... | Secret URL instead of auth                                                                                                 |
| [Gmail MCP](https://developers.google.com/workspace/gmail/api/guides/configure-mcp-server)               | https://gmailmcp.googleapis.com/mcp/v1         | OAuth                                                                                                                      |
| [Google Drive MCP](https://developers.google.com/workspace/drive/api/guides/configure-mcp-server)        | https://drivemcp.googleapis.com/mcp/v1         | OAuth                                                                                                                      |
| [Google Calendar MCP](https://developers.google.com/workspace/calendar/api/guides/configure-mcp-server)  | https://calendarmcp.googleapis.com/mcp/v1      | OAuth. Note: you can use Android → Calendar instead.                                                                       |

!!! note
    DCR stands for dynamic client registration. DCR works identical to OAuth but instead of having to register an app yourself Agora can do that for you, meaning you do not need to provide a client id/client secret.

### Step 2: Add It in Agora

1. Go to **Settings → MCP Servers**
2. Enable **Enable MCP Tools**
3. Tap **Add Server**
4. Pick a **Transport**: **HTTP** (default) or **Stdio** (F-Droid/GitHub builds only — the chip still shows on Google Play but falls back to the HTTP fields, since there's no sandbox to run a subprocess in).
5. Fill in the server details:

| Field             | Transport | Description                                                                                           | Example                           |
| ----------------- | --------- | ----------------------------------------------------------------------------------------------------- | --------------------------------- |
| **Name**          | Both      | Display name for this server                                                                          | `Home Assistant`                  |
| **Description**   | Both      | Optional note about what it's for. If left blank, the server's host is shown instead.                 | `Controls lights and thermostats` |
| **Timeout**       | Both      | Per-request timeout, 5–120 seconds                                                                    | `30`                               |
| **Server URL**    | HTTP      | The MCP Streamable HTTP endpoint                                                                      | `https://example.com/mcp`         |
| **Extra Headers** | HTTP      | Optional — one per line, as `Name: value`, for servers that expect auth or routing by a custom header | `X-Api-Key: secret`               |
| **Command**       | Stdio     | The shell command line to launch inside the sandbox                                                   | `npx -y @modelcontextprotocol/server-filesystem /home/agora` |
| **Environment Variables** | Stdio | Optional — one per line, as `NAME=value`, passed to the subprocess                             | `API_KEY=secret`                  |

Depending on the auth method you may need to provide additional details. OAuth and Bearer Token auth are HTTP-only — a stdio server authenticates however its own command line/environment variables tell it to (e.g. an API key baked into **Command** or **Environment Variables**).

When using a bearer token simply enter it in the appropriate field. You do not need to include `Authorization: Bearer`.

When using OAuth servers you can press **Discover** to automatically populate the authorization/token endpoints, the registration endpoint (if the server supports DCR), and the resource indicator.
If a provider supports DCR, press **Register Client** to register and populate the Client ID. Otherwise you'll need to look up how to create an OAuth app with that provider and populate the Client ID, Client Secret & optionally scopes yourself. If discovery didn't find a **Registration Endpoint**, you can also enter one manually to enable DCR.
The **Resource Indicator** field is normally left blank — it's only needed if discovery didn't already resolve it and the server requires a specific `resource` value that differs from the Server URL.
Press **Sign in**, once you've signed in you'll be redirected back to Agora and it should show **Connected**.

6. Tap **Test Connection** to verify Agora can reach the server and see how many tools it exposes, before saving.
7. Tap **Save**.

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

**HTTP servers:**

- Double-check the **Server URL** — it should be the full endpoint (e.g. `.../mcp`), not just the host
- If the server requires auth, verify the **Bearer Token** or **Extra Headers** are correct
- Confirm the server implements the Streamable HTTP transport, not the legacy HTTP+SSE transport
- Check that the URL is reachable from your device (not just from your desktop's network)

**Stdio servers:**

- Confirm you're on an F-Droid or GitHub-release build with the [Local Sandbox](sandbox.md) set up — Google Play builds can't run stdio servers at all
- Double-check **Command** actually runs inside the sandbox (e.g. `npx`/`node` need Node.js installed in the sandbox first — try running the same command from **Settings → Local Sandbox → Run Command**)
- Check **Environment Variables** for typos — a missing API key or config path is a common cause of an otherwise-valid command failing to start

### The model never calls the tool

- Confirm **Enable MCP Tools** and the specific server's **Enabled** checkbox are both on
- Try **Test Connection** to confirm the server currently lists that tool
- Some models are more reluctant to call unfamiliar tools without a clear reason in the conversation — try being explicit about what you want done

### Requests keep timing out

- Increase the server's **Timeout** if its tools are slow (e.g. long-running searches or automations)
- A slow or overloaded server is retried at most once every 30 seconds rather than on every message, so a temporary outage won't repeatedly stall your conversation

### Confirmation prompt shows unexpected arguments

The confirmation dialog shows the exact arguments the model is about to send. If they look wrong, deny the call — the model will usually see the denial and adjust its next attempt.

### OAuth sign-in issues

- **Discover fails or leaves endpoints blank** — not every server publishes the discovery metadata Agora looks for. Enter the **Authorization Endpoint**, **Token Endpoint**, and (if the provider supports DCR) **Registration Endpoint** manually — check the provider's own OAuth/developer docs for these URLs.
- **Register Client fails** — the server may not actually support DCR despite exposing a registration endpoint. Create an OAuth app manually with the provider instead and fill in the **Client ID** and **Client Secret** yourself.
- **Sign in doesn't return to Agora, or shows an error** — this happens in the browser tab AppAuth opens for sign-in, before control returns to Agora; check that you completed the provider's login/consent screen without cancelling, and that the account you signed in with has access to the resource.
- **"Sign-in expired — reconnect to use this server"** — the server's refresh token was rejected (expired, revoked, or the app's access was removed on the provider's side). Press **Sign in again** to redo the OAuth flow.
- **Connected, but tool calls still fail with a 401/authorization error** — the token the server issued may not be scoped to this MCP server. If you entered the **Resource Indicator** manually, double-check it matches what the server expects (or leave it blank so Agora falls back to the Server URL).
