# Semantic Search Architecture Contract

Status: authoritative development contract, 2026-08-14.

This document is required context for changes to embedding-cache reads, semantic conversation search,
RAG ranking, or the search eligibility query. Semantic search must remain bounded by one database
page plus the requested top candidates; corpus growth must not translate into Android heap growth.

## 1. Observable behavior

- Query embedding generation, model selection, API-key resolution, and user-visible tool behavior
  remain owned by the existing RAG/provider path.
- Searchable sources exclude Task conversations, non-USER/non-MODEL rows, blank or short source
  text, and synthetic tool/result/Compact rows.
- Similarity is cosine similarity. Candidates must be strictly above the configured RAG threshold,
  ordered by descending score, and limited to the requested count.
- Stable embedding row id is the deterministic tie-breaker for equal scores.
- Invalid dimensions, malformed byte lengths, and non-finite scores are skipped without aborting
  the remaining corpus and without logging message content.

## 2. Bounded data flow

1. ChatDao reads a minimal projection containing only embedding row id, message id, embedding
   bytes, and declared dimension.
2. The DAO uses stable keyset pagination (id > afterId, ORDER BY id, bounded LIMIT). It never
   materializes the complete model corpus for semantic search.
3. The selector decodes and scores one page at a time.
4. A bounded worst-first top-K heap retains at most the requested result count across all pages.
5. Only the final bounded message-id set is expanded into complete searchable MessageEntity rows.
6. The final expansion revalidates search visibility and minimum source length before returning.

Peak application memory is therefore proportional to one configured page, one decoded vector, the
bounded top-K heap, and the final bounded message set. It is not proportional to embedding-row
count or total cached text.

## 3. Ownership

| Owner | Responsibility | Prohibited responsibility |
|---|---|---|
| ChatDao | Eligibility join, minimal projection, deterministic keyset page. | Full-corpus semantic list or score/ranking policy. |
| ConversationRepository | Pass through the bounded page contract. | Reassembling pages into one collection. |
| BoundedSemanticEmbeddingSelector | Vector validation, page-by-page scoring, strict threshold, bounded top-K, stable ranking. | Room access, Provider calls, message visibility policy, or cache mutation. |
| RagToolProvider | Query embedding, selector orchestration, final bounded message expansion, tool result projection. | Full-corpus materialization or a second ranking implementation. |

## 4. Failure and concurrency behavior

- A malformed cache row cannot fail the whole search.
- A page must be strictly ordered and advance the keyset; a broken loader fails instead of looping.
- Message deletion or visibility changes between scoring and final expansion may only remove a
  candidate. They must not expose a hidden row.
- Search is read-only. It must not delete/rebuild cache rows, increase the heap limit, or retry the
  complete scan as a correctness mechanism.
- Logs may contain aggregate row counts, invalid-row counts, dimensions, and scores, but no source
  message text, embedding bytes, credentials, or conversation content.

## 5. Required verification

Focused verification must cover multiple pages, ranking across page boundaries, strict threshold
exclusion, bounded retained candidates, deterministic equal-score ordering, empty results,
dimension/byte-shape corruption, non-finite vectors, and a source/DAO contract preventing the
unbounded full-list hot path from returning.
