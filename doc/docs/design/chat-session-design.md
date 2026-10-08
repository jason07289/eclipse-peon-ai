# Chat Sessions — Persistence & Resume Design

Status: **draft, not yet implemented** (design v2, after adversarial review 2026-10-02)

Peon AI currently keeps conversations only in memory: each chat service holds a
langchain4j `MessageWindowChatMemory` (`AbstractChatService`, no `ChatMemoryStore`),
so closing Eclipse loses everything. This document specifies a session feature:
save, list, resume, and switch conversations.

---

## Current state (what the design must work with)

- **Five memories, not one conversation**: DEV, PLAN, QUERY_TO_SOURCE
  (`PeonAiService`) plus the AGENT mode's internal planner/developer pair
  (`AgentModeService`). `getActiveService()` picks by `PeonMode`.
- **Turn mechanics**: `AbstractChatService.call()` adds the `UserMessage`, then
  `ToolService.executeLoop()` sends `staticMessages + memory.messages()` each
  iteration and appends AI/tool-result messages to memory *after* the
  `onChatResponse` UI callbacks. Memory is only complete once `executeLoop()`
  returns.
- **Compression is destructive**: `compressContext()` does `memory.clear()` and
  replaces everything with one `[Context summary]` `AiMessage`. The
  LLM-callable `CompactSessionTool` does the same.
- **UI is display-only**: `ChatMarkdownWidget` (SWT Browser + chat.html);
  `AIChatView.refreshChat()` re-renders from `getActiveService().getMessages()`.
  PROBLEM/TOOL notices appended directly to the widget are lost on refresh.
- **Mode switching has side effects**: `setPeonMode(AGENT)` resets phase and
  re-injects the project's plan overview file; entering QUERY_TO_SOURCE resets
  (clears) its memory. `setProject()` retargets file-write/disk tools.
- **Available infra**: Jackson is bundled and used for JSON-in-preferences;
  langchain4j ships unused `ChatMessageSerializer`/`ChatMessageDeserializer`.
  No Activator, no state-location usage today.
- **KV-cache constraint** (issue #60): a resumed session must replay the exact
  stored message list; static messages are rebuilt per call anyway.

## Prior art (survey summary)

Continue, Cline, Roo Code, Eclipse AssistAI, Codex CLI, Gemini CLI, OpenCode,
Claude Code and Aider were surveyed (2026-10-02). They converge on:

1. One file (or directory) per session + a small index; metadata readable
   without parsing full sessions (Codex/Claude Code put it in JSONL line 1).
2. Separate "what the LLM sees" from "what the UI shows" (Cline/Roo two-file
   split; Codex type-tagged events in one log).
3. Resume = full local replay; nobody relies on provider-side state. Roo
   repairs dangling `tool_use` calls before the first request.
4. Titles: truncated first user message; optionally LLM-generated later.
5. Auto-save at turn boundaries with atomic writes (Cline lost whole histories
   to JSON corruption before adding safe writes — issue cline#7101).
6. Compaction recorded as an event, never by rewriting history (Codex
   `Compacted`; Roo's non-destructive `condenseId`/`condenseParent`).
7. Retention policy with sane defaults (Gemini CLI: maxAge 30d / maxCount /
   minRetention).
8. Storage in the user home dir, not the workspace (Continue `~/.continue`,
   Cline `~/.cline/data`, Claude Code `~/.claude/projects/<cwd-slug>`), with
   the workspace/project binding stored as metadata.

Anti-patterns: Aider's display-format-as-store (lossy re-parse); thousands of
tiny JSON files without an index (OpenCode paid for a SQLite migration);
AssistAI's index-less full-directory scan.

---

## Design

### 1. Storage layout

Sessions live **outside the workspace** (requirement: nothing may remain in
`.metadata`), under a configurable base path:

```
<base>/                                  # preference, default: ~/.peon-ai
  sessions/
    <wsName>-<sha256(wsPath)[0:8]>/      # human-readable AND collision-free
      .lock                              # java.nio FileLock, instance ownership
      index.json                         # rebuildable cache, NOT authoritative
      <sessionId>/
        meta.json                        # authoritative session metadata
        transcript.jsonl                 # user-facing history, append-only
        memory.json                      # model-resume snapshot, atomic replace
        memory.json.bak                  # previous good revision
```

- `wsName` = sanitized last segment of
  `ResourcesPlugin.getWorkspace().getRoot().getLocation()`; the hash suffix of
  the normalized full path removes same-name-workspace races (a check-then-
  suffix scheme has a TOCTOU race and was rejected).
- Windows-forbidden characters (`\ / : * ? " < > |`) sanitized to `_`.
- `~/.peon-ai` and temp files created user-only; the same applied to custom
  base paths.

### 2. Transcript vs. memory snapshot (the core split)

Persisting the `ChatMemory` alone is wrong twice over: compression would
destroy the saved originals, and a file-backed `ChatMemoryStore` writes on
every `add`/`clear` with no transactional boundary (langchain4j 1.16.1:
`add` → `updateMessages`, `clear` → `deleteMessages`; the tool loop appends
assistant message and each tool result separately; compress is clear-then-add).
**Do not inject a `ChatMemoryStore`.** Instead:

| File | Content | Written | On compress |
|---|---|---|---|
| `transcript.jsonl` | type-tagged events: USER, AI, THINK, TOOL, PROBLEM, COMPACTED, one JSON per line | streamed append during the turn | append a `COMPACTED` event; originals preserved |
| `memory.json` | langchain4j-serialized `memory.messages()` + `revision` + turn `status` | turn boundary, temp-file + rename, previous revision kept as `.bak` | replaced (revision++) |
| `meta.json` | see §5 | with `memory.json` | updated |

- UI restore renders from `transcript.jsonl`; LLM resume loads `memory.json`
  verbatim into a fresh in-memory `MessageWindowChatMemory` (KV-cache safe).
- Side benefit: PROBLEM/TOOL notices land in the transcript and survive
  `refreshChat()`, fixing today's notice loss.
- A lost transcript append is cosmetic; `memory.json` is the recovery point.

### 3. Save points (never `onChatResponse`)

`onChatResponse` fires mid-tool-loop and *before* memory is updated — it is
not a valid save hook. Snapshots are taken in the service layer where memory
is complete and on the executing thread:

- **End of `AbstractChatService.call()`**, right after `executeLoop()`
  returns: snapshot `memory.messages()` (immutable copy) with
  `status: completed`.
- **On cancel** (`monitor.isCanceled()`): snapshot with `status: canceled`.
- **On exception**: snapshot with `status: interrupted` — a turn whose tools
  already ran must not be silently dropped.
- **After `compressContext()`**: commit the clear+add pair as ONE new
  revision; a crash between the two in-memory steps never reaches disk, the
  previous revision file survives.
- Restore of a session whose last `status != completed` shows an
  "interrupted" banner; tool outcomes are treated as unknown and nothing
  auto-reruns.

### 4. Session unit (aggregate) — phased

- **Phase 1: a session covers the DEV and PLAN conversations only.** AGENT
  and QUERY_TO_SOURCE memories are out of scope (switching sessions while in
  those modes just informs the user). This ships the common case safely.
- **Phase 2: AGENT as an aggregate**
  `{plannerMemory, developerMemory, phase, retryCount,
  implementationRequested, plan-overview file (project-relative path)}`;
  QUERY_TO_SOURCE adds `{pipeline version, current step, per-step completion}`.
- **Restore is a dedicated path, not `setPeonMode()`**: `restoreSession()`
  injects state with no reset, no overview re-injection, no auto-run. Mode
  switching logic is never reused for restore.

### 5. Metadata (`meta.json`)

```json
{
  "schemaVersion": 1,
  "lc4jVersion": "1.16.1",
  "id": "<uuid>",
  "title": "first user message, 60 chars",
  "mode": "DEV",
  "status": "completed | canceled | interrupted",
  "revision": 17,
  "createdAt": "...", "updatedAt": "...",
  "workspacePath": "/abs/path/to/workspace",
  "project": { "name": "...", "location": "/abs/path" },
  "planOverviewFile": "relative/path (AGENT, phase 2)",
  "tokens": { "context": 45000 }
}
```

- **Project binding is mandatory** (not just workspace): `setProject()`
  retargets edit/disk tools, so restoring project A's conversation while
  project B is selected would aim A's plan at B. On restore the project's
  existence and location are verified; on mismatch the session opens
  **read-only** with a banner, and tools are enabled only after an explicit
  re-bind by the user.
- Loader behavior for unknown `schemaVersion`: keep the file, never delete,
  list it as "incompatible".

### 6. Concurrency

- `PeonAiService` gains `currentSessionId` + an `AtomicLong generation`. A
  send `Job` captures `(service, sessionId, generation)` at start; every UI
  callback and every save compares the captured generation with the current
  one and drops stale work.
- Switching/creating sessions while a Job runs: request cancel, await
  termination (or keep the switch disabled), then `generation++`.
- `AIChatView.dispose()` awaits the running Job before the final snapshot,
  using the same serialization boundary.
- Cross-process: the per-workspace `.lock` (FileLock) is acquired on first
  use; if unavailable the store is opened read-only. Retention deletes only
  run while holding the lock.
- Write conflict guard: if the on-disk `revision` is newer than the
  in-memory one, do not overwrite — write a `*.conflict-<ts>.json` copy.
- `index.json` is a cache rebuilt from `meta.json` scans whenever missing or
  stale; losing it can never lose a session.

### 7. Lifecycle, retention, preferences

- Titles: first user message truncated to 60 chars; optional LLM-generated
  titles later (Continue-style, off by default).
- Retention: by `meta.json.updatedAt`, defaults 30 days / max N sessions,
  both preferences.
- "Disable persistence" preference: stops new writes only; existing data is
  kept; a separate explicit "delete all sessions" action.
- Prerequisite fix: `ToSimpleMessage` uses `UserMessage.singleText()`, which
  throws on the multi-content messages `call()` builds when context info is
  attached — must handle multi-content before transcript/restore rendering.

### 8. Implementation order

1. `SessionStore` in core (file IO, lock, revision, schema) — unit-testable
   without UI.
2. Turn-boundary snapshot hook in `AbstractChatService` (end of `call()`,
   after `compressContext()`) + transcript appends.
3. `PeonAiService`: sessionId/generation, switch & `restoreSession()` paths
   (DEV/PLAN only).
4. UI: session list dialog (list, resume, delete, new session); render
   restore from transcript.
5. Retention + preference page (base path, disable toggle).
6. Phase 2: AGENT / QUERY_TO_SOURCE aggregates.

### Verification scenarios (from adversarial review)

- Two Eclipse instances creating the same workspace store concurrently.
- Concurrent saves to one session (stale generation must lose).
- Kill during compression → previous revision restores.
- Crash after tool side effects → `interrupted` checkpoint, no auto-rerun.
- Switching sessions while a turn is running → no cross-session bleed.
- Restoring with a different project selected → read-only + re-bind.

---

## Review history

- v1 (state location, `ChatMemoryStore` injection, save on `onChatResponse`,
  name-only directories) was rejected by an adversarial review (Codex,
  2026-10-02) with 7 high findings: wrong save point, no transactional
  boundary in `ChatMemoryStore`, incomplete aggregate (5 memories + mode side
  effects), stale-callback contamination, missing project binding, storage
  ownership races, and compression destroying saved originals. v2 above
  addresses each.
