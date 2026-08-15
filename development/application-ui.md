# Application UI Contract

Status: authoritative development contract, 2026-08-15.

This document owns durable application-level UI behavior that is not part of message generation,
citations, semantic search, or Web Search. Current explicit user requirements override older
presentation code and translations.

## 1. Motion ownership and accessibility

Application UI motion consumes the shared Agora motion policy. Spatial press, size, and scale motion
must snap to the stable resting presentation when Reduced Motion disables spatial transitions.
Opacity-only transitions may remain only where their owning component contract allows them.

A screen may reuse an established motion language directly without creating another global animation
owner. Interaction state stays local to the interactive control and must not alter navigation,
validation, persistence, or completion semantics.

## 2. Onboarding primary action

The onboarding Continue/Get Started action preserves its full-width role, page validation, paging,
completion callback, enabled state, colors, and label semantics.

Its press response matches the Documentation FAB motion language exactly:

- use one local press interaction source;
- use spring stiffness `400f` and damping ratio `0.25f`;
- reserve one fixed 56 dp outer-height slot so surrounding onboarding content does not jump;
- animate horizontal inset from 32 dp at rest to 12 dp while pressed, making the full-width action
  exactly 40 dp wider;
- animate height from 48 dp to 56 dp and content scale from 1f to 1.1f while pressed;
- when spatial transitions are disabled, keep 32 dp inset, 48 dp height, and 1f content scale.

## 3. Settings category copy

The Generation Settings category description names only its actual category content and is the direct
localized equivalent of `LLM parameters`. It must not mention the context window. This copy change
does not remove or relocate Context Settings, alter the Generation Settings destination, or change
any stored generation parameter.

The default resource and every supported locale must define the same key set. App-owned strings are
localized in the current Android locale; hard-coded English must not replace resource-backed UI copy.

## 4. Chat composer dropdown icon parity

The chat-bottom attachment `+` dropdown and tools `...` dropdown use explicit 24 dp leading
icons/images in every menu row, matching the Material default size used by the user-message
long-press dropdown. Their 16 dp trigger icons remain unchanged. Menu shape, row geometry, 12 dp
icon-label gap, labels, badges, switches, ordering, enablement, and click behavior remain unchanged.

## 5. Verification

Focused verification must cover the exact onboarding spring constants, rest/pressed dimensions,
motion-policy snap, unchanged action semantics, Generation Settings description, locale key parity,
absence of the removed context-window wording, and 24 dp leading-icon parity across both chat-bottom
dropdowns without resizing their triggers. The project-defined full build gate remains required
after final code or resource changes.
