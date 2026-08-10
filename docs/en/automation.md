# Automation

Agora provides saved **Tasks** and conversation-scoped **Loops**. The Automation settings page controls whether the model may manage them and how precisely Android schedules them.

## Tasks

Open **Tasks** from the app navigation to create and manage saved prompts.

A Task contains:

- a name and prompt;
- an optional model override;
- a manual, one-time, daily, weekly, monthly, yearly, or custom five-field cron schedule;
- an enabled switch for scheduled execution.

Use **Run now** to execute a Task without waiting for its schedule. Each execution creates history with its status and generated conversation. Disabling scheduled execution keeps the Task available for manual runs.

A one-time schedule must be in the future. Expired dates are rejected instead of silently moving to another year.

## Conversation Loops

A Loop belongs to one conversation and runs another generation after a configured interval. It may inject a prompt on each cycle and has a maximum-cycle safety limit. Only one active Loop can belong to a conversation.

Loops are started and stopped through Agora's automation tools, so **Access Tasks and Loops** must be enabled before a model can control them.

## Access Tasks and Loops

This setting is disabled by default. When enabled, the model can use tools to:

- create, list, and delete Tasks;
- start and stop the current conversation's Loop.

The tool is checked again when it executes, so turning the setting off prevents a previously proposed automation call from changing automation state.

!!! warning
    Enable automation access only when you want the model to change persistent schedules. A scheduled prompt can call providers and enabled tools in the background.

## Scheduling Precision

By default Agora uses battery-friendly inexact alarms, so Android may delay a run.

Enable **Exact Execution** to request exact alarms for Tasks and Loops. On Android 12 and newer, Android may open the system **Alarms & reminders** access screen. If access is denied or later revoked, Agora turns Exact Execution off and safely falls back to inexact scheduling.

Exact alarms improve timing but do not override Android battery, background, network, or vendor restrictions.

## Reliability

Tasks and Loops share the normal conversation generation pipeline and do not create a second writer when a conversation is busy. Running automation displays an ongoing notification. If a target conversation is already generating, the automation reports a busy outcome instead of creating a competing run.
