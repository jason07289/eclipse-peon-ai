# Chat Sessions — Persistence & Resume Design

Status: **draft, not yet implemented** (design v3, lifecycle and history UI updated 2026-10-13)

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
  returns. Tools run **before** their results are added to memory.
- **Two distinct compaction paths**: `compressContext()` does `memory.clear()`
  and replaces everything with one `[Context summary]` `AiMessage`. The
  LLM-callable `CompactSessionTool` does NOT call `compressContext()` — it
  replaces memory with a user message and returns the summary as a tool
  result. Both must be treated as compaction events.
- **Memory changes outside `call()`**: `clear()` (Clear button),
  `startImplementation()` (PLAN→DEV handoff: clears DEV memory and seeds the
  plan), tool-driven compaction.
- **UI is display-only**: `ChatMarkdownWidget` (SWT Browser + chat.html);
  `AIChatView.refreshChat()` re-renders from `getActiveService().getMessages()`.
  PROBLEM/TOOL notices appended directly to the widget are lost on refresh.
- **Mode switching has side effects**: `setPeonMode(AGENT)` resets phase and
  re-injects the project's plan overview file; entering QUERY_TO_SOURCE resets
  (clears) its memory. `setProject()` retargets file-write/disk tools, and is
  called from UI selection changes (`AIChatView`) whenever the project is not
  pinned.
- **UI-thread hazards**: tools such as `EclipseWorkspaceWriteFileTool` call
  back into the UI thread via `Display.syncExec()` (through `EclipseUtil`), so
  the UI thread must never block waiting for a running turn Job.
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
    <wsName>-<sha256(wsRealPath)[0:16]>/ # human-readable + 64-bit hash
      store.json                         # store identity: full workspace path
      .lock                              # java.nio FileLock, instance ownership
      index.json                         # rebuildable cache, NOT authoritative
      corrupt/                           # quarantined unreadable files
      <sessionId>/
        transcript.jsonl                 # user-facing history + WAL, append-only
        checkpoints/
          ckpt-000017.json               # immutable checkpoint (meta + memories)
          ckpt-000016.json               # previous good revision (keep 2)
        CURRENT                          # tiny pointer file: "17"
```

- `wsName` = sanitized last segment of the workspace location; the hash is
  computed over the **normalized real path** (`Path.toRealPath()`, symlinks
  resolved). 16 hex chars (64 bits) — 8 was not collision-free.
- **Store identity check**: `store.json` records the full normalized
  workspace path. On open, a mismatch with the current workspace means a hash
  collision or a copied store → the store is **refused** (never silently
  shared), and the user is told which path owns it.
- Windows-forbidden characters (`\ / : * ? " < > |`) sanitized to `_`.
- `<base>` and all files created user-only; the same applied to custom paths.

### 2. One checkpoint file, not meta + memory (commit protocol)

v2 kept `meta.json` and `memory.json` as two separately-replaced files — a
crash between the two writes leaves them pointing at different revisions, and
no index rebuild can repair that. v3 therefore uses **one immutable checkpoint
file per revision** plus a commit pointer:

```json
// ckpt-000017.json — written once, never modified
{
  "schemaVersion": 1,
  "messageFormatVersion": "lc4j-1.16",
  "revision": 17,
  "meta": { ...see §5... },
  "aggregate": {
    "activeMode": "DEV",
    "devMemory":  [ ...lc4j-serialized messages... ],
    "planMemory": [ ...lc4j-serialized messages... ]
  },
  "lastTranscriptSeq": 412,
  "checksum": "sha256 of the canonical payload"
}
```

**Commit protocol** (all steps on the session's writer, §6):

1. Write `ckpt-<rev>.json` to a temp file, fsync, rename into `checkpoints/`.
2. Replace `CURRENT` via temp-file + rename with the new revision number.
3. Delete checkpoints older than the previous one (keep 2).

**Recovery** (on open): read `CURRENT` → load that checkpoint → verify
checksum and parse. On failure, fall back to the previous checkpoint; move
unreadable files to `corrupt/` (never delete). A truncated last line in
`transcript.jsonl` (torn append) is dropped by a tolerant line parser.
`index.json` is rebuilt from checkpoint headers whenever missing or stale —
losing it can never lose a session.

### 3. Transcript as event log AND write-ahead log

`transcript.jsonl` is an append-only, type-tagged event stream serving two
purposes: rendering the user-visible history, and detecting interrupted turns
after a hard kill (a crash runs no exception handler, so turn state must be
durable *before* side effects, not after).

Event envelope: `{ "seq": n, "turnId": "...", "mode": "DEV", "ts": ...,
"type": ..., ...payload }`. Event types:

| Type | Written | Purpose |
|---|---|---|
| `TURN_STARTED` | flushed before the first model call | crash detection |
| `USER`, `AI`, `THINK` | streamed during the turn | UI history |
| `TOOL_INTENT` | **flushed before each tool executes** (tool-call id, name, args digest) | side-effect audit |
| `TOOL_RESULT` | after the tool returns | side-effect audit |
| `PROBLEM` | as emitted | UI history (survives refresh) |
| `COMPACTED` | on **both** compaction paths — `compressContext()` and `CompactSessionTool` | originals preserved |
| `TURN_COMMITTED` | after the checkpoint commit, with the revision | confirmation marker |

- **Interrupted-turn detection**: on open, a `TURN_STARTED` without a matching
  `TURN_COMMITTED` (or with `seq > lastTranscriptSeq` of `CURRENT`'s
  checkpoint) marks the session **interrupted**, listing which tools have a
  `TOOL_INTENT` without / with a `TOOL_RESULT`. External side effects whose
  outcome is unknown are shown to the user; nothing auto-reruns.
- Because the WAL role makes the transcript load-bearing, `TURN_STARTED` and
  `TOOL_INTENT` appends are flushed to disk before proceeding; other events
  may be buffered.
- Events after the checkpoint's `lastTranscriptSeq` are rendered as
  "unconfirmed" in the UI (present in history, absent from the resumable
  model memory).
- LLM resume loads the checkpoint's memory lists verbatim into fresh
  in-memory `MessageWindowChatMemory` instances (KV-cache safe).

### 4. Checkpoint commit points

Snapshots are taken in the service layer where memory is complete and on the
executing thread — never from `onChatResponse` (it fires mid-tool-loop,
before memory is updated):

- **End of `AbstractChatService.call()`**, right after `executeLoop()`
  returns: commit with `status: completed`.
- **On cancel** (`monitor.isCanceled()`): commit with `status: canceled`.
- **On exception**: commit with `status: interrupted`.
- **Hard kill**: no commit happens — detected via the WAL (§3).
- **Every memory mutation outside `call()` also commits a revision**:
  `clear()`, `compressContext()`, `CompactSessionTool`, and
  `startImplementation()` (PLAN→DEV handoff). The clear+add pair inside a
  compaction reaches disk only as one completed revision.

### 5. Session unit (aggregate) — phased

A Phase 1 session is the pair `{devMemory, planMemory}` plus `activeMode`,
stored together in every checkpoint (§2) — restoring only the active mode's
memory would lose the other conversation or leak a previous session's state
into reused service instances. Transcript events carry `mode` so the UI can
filter per mode.

- **Phase 1 scope**: DEV + PLAN. AGENT and QUERY_TO_SOURCE memories are out
  of scope (switching sessions while in those modes just informs the user).
- **Phase 2: AGENT as an aggregate**
  `{plannerMemory, developerMemory, phase, retryCount,
  implementationRequested, plan-overview file (project-relative path)}`;
  QUERY_TO_SOURCE adds `{pipeline version, current step, per-step completion}`.
- **Restore is a dedicated path, not `setPeonMode()`**: `restoreSession()`
  replaces both memories and the active mode atomically, with no reset, no
  overview re-injection, no auto-run. Mode switching logic is never reused
  for restore.

Checkpoint `meta`:

```json
{
  "id": "<uuid>",
  "title": "first user message, 60 chars",
  "status": "completed | canceled | interrupted",
  "createdAt": "...", "updatedAt": "...",
  "workspacePath": "/abs/normalized/path",
  "project": { "name": "...", "location": "/abs/path" },
  "planOverviewFile": "relative/path (AGENT, phase 2)",
  "tokens": { "context": 45000 }
}
```

### 6. Concurrency: per-session writer + generation

Generation checks alone do not serialize two same-generation saves, and the
send Job is not the only writer (the compress action runs its own Job).
Therefore:

- **Every session mutation goes through a single-threaded per-session
  executor** ("the writer"): turn commits, compaction, clear, restore,
  delete, retention affecting that session. The writer owns the
  check-revision → snapshot → commit sequence, making it one exclusive
  critical section.
- `PeonAiService` keeps `currentSessionId` + an `AtomicLong generation`. A
  send Job captures `(service, sessionId, generation)` at start. Generation
  is validated **both when enqueuing to the writer and again when the queued
  work executes**; stale work is dropped. UI callbacks drop stale renders the
  same way.
- Switching/creating sessions: request cancel of the running Job, `generation++`,
  enqueue the switch on the writer (it runs after in-flight commits drain).
- **Shutdown must never block the UI thread**: running turns use
  `Display.syncExec()` (e.g. editor saves in write tools), so a blocking wait
  in `dispose()` deadlocks. `dispose()` only signals cancel and flushes the
  transcript buffer. No final "parting" snapshot is needed: every turn and
  every mutation already committed at its boundary, and an in-flight turn is
  recovered as interrupted via the WAL on next start.
- Cross-process: the per-workspace `.lock` (FileLock) is acquired on first
  use; if unavailable the store opens read-only. Retention deletes only run
  while holding the lock.
- Write conflict guard (second process, shared custom path): if the on-disk
  `CURRENT` revision is newer than the writer's base revision, do not
  overwrite — write a `*.conflict-<ts>.json` copy and surface it.

### 7. Project binding — enforced per tool execution, not just at restore

`setProject()` retargets edit/disk tools and is invoked by ordinary UI
selection changes, so validating the project only at restore time is not
enough: restoring project A's session and then clicking project B would aim
A's conversation at B.

- The session aggregate carries the **project binding** (§5 meta), distinct
  from the transient UI selection.
- A send Job captures the binding at turn start; **tool execution validates
  the captured binding** against the tools' current target and refuses to run
  on mismatch.
- UI selection changes do not silently mutate a session's binding.
  **Re-binding is an explicit user action**, executed on the session writer
  (serialized with running work, §6).
- On restore, the bound project's existence and location are verified; on
  mismatch the session opens **read-only** with a banner until the user
  re-binds.

### 8. Versioning & migration

- `schemaVersion` (this store format) and `messageFormatVersion` (the
  langchain4j serialization) are **separate** fields; a langchain4j upgrade
  can change the message format without a store-schema change.
- The loader keeps a table of supported combinations. Older supported
  versions are converted forward losslessly on load (written back only on the
  next regular commit). Unknown/unsupported files are kept untouched and
  listed as "incompatible" — never deleted, never overwritten.
- Test fixtures: real serialized message samples (user, AI with tool calls,
  tool results, multi-content, compaction summary) per supported version;
  round-trip and forward-read tests run in `org.sterl.llmpeon.core` unit
  tests.

### 9. Lifecycle, retention, preferences

- **Start fresh on reopen**: launching Eclipse or closing and reopening the
  Peon AI view starts a new, empty session. Previously saved sessions are
  never automatically resumed, including after a crash. Merely focusing,
  hiding, or revealing an existing view does not start a new session.
- **Resume is explicit**: users select a saved session from History
  (§9.1) and choose Resume. Store recovery may repair or classify saved
  sessions at startup, but does not activate them in the chat view.
- **New Session preserves history**: the New Session action starts a separate empty
  DEV/PLAN aggregate using the session-switch protocol (§6); it does not clear
  or delete the previous session. Users need not press Clear on startup or
  before starting another conversation. Closing the view follows the same
  non-blocking cancel and stale-callback protection as shutdown (§6).
- **Do not persist empty sessions**: opening the view or pressing New Session
  creates only an in-memory session. On the first submitted user message,
  materialize its initial checkpoint and index entry, then durably append
  `TURN_STARTED` before calling the model (§3). Until then, no session
  directory or history entry is created; clearing an untouched empty session
  also creates no persisted revision. Normal mutation commits apply once the
  session has been persisted. A first turn that is canceled or interrupted
  remains discoverable in history.
- **Clear keeps the session**: on a persisted session, Clear commits an
  empty-memory revision and appends a `CLEARED` transcript event — the
  history entry, title, and full transcript remain; only the model context is
  dropped (same as today's behavior, now recorded). Clear never deletes a
  session; deletion happens only through History. On an unpersisted empty
  session Clear is a no-op on disk (§ lazy persistence above).
- Titles: first user message truncated to 60 chars; optional LLM-generated
  titles later (Continue-style, off by default).
- Retention (all preferences): maxAge default 30 days, maxCount default 50,
  **minRetention 1 day** (nothing younger is ever auto-deleted). The
  currently-open session is always protected. Sessions in `interrupted`
  status are protected from maxCount/size eviction but still expire by
  maxAge — since resume is explicit, an interrupted session may never be
  revisited, and unlimited protection would make crash leftovers immortal.
  Opening a session touches `updatedAt` before any cleanup can see it.
  Cleanup runs at store-open under the lock; backups, conflict copies and
  quarantined files count toward size.
- **Size limits**: a per-session transcript cap (default 50 MB → warn, stop
  appending transcript detail but keep checkpoints working) and a total store
  cap (default 1 GB → oldest unprotected sessions evicted first). A single
  long-lived session must not grow unbounded just because it stays "fresh".
- **Disk-full / write failure**: the last good checkpoint is never touched by
  a failing commit (immutable files + pointer switch); the failure is
  surfaced in the status line and persistence pauses until a write succeeds.
- "Disable persistence" preference: stops new writes only; existing data is
  kept; a separate explicit "delete all sessions" action.
- **Base-path change** is two distinct operations:
  1. *Open a different store* (default): release the old lock, lock and open
     the new path; old data stays where it is and reappears when the path is
     set back.
  2. *Migrate*: offered via dialog on path change; runs only when no turn is
     executing; copy → verify (checksums) → switch pointer/preference →
     delete source. On any failure the source is kept and the preference is
     not switched. *(Implementation status: not built yet — a path change
     currently applies on the next Eclipse start as "open a different store";
     the preference page says so.)*
  The path preference lives in `ConfigurationScope` (per installation, shared
  across workspaces); invalid/unwritable paths are rejected in the preference
  page validator.
- Prerequisite fix: `ToSimpleMessage` uses `UserMessage.singleText()`, which
  throws on the multi-content messages `call()` builds when context info is
  attached — must handle multi-content before transcript/restore rendering.

### 9.1. Session history UI

- Place **New Session (+)** and **History** actions at the top of the chat
  view. Give the icon actions tooltips explaining that New Session preserves
  saved conversations and History lets users resume them.
- History opens a session-list dialog for the current workspace store,
  ordered by `updatedAt` descending (most recently used first).
- Each row shows the **title, bound project, and last-used time**, with a
  distinct indicator for interrupted sessions. Sessions without a project
  binding show "—" in the project column. Incompatible sessions remain
  listed with their status (§8).
- Selecting a row does not restore it immediately. **Resume** explicitly
  activates the selected compatible session through `restoreSession()`;
  **Delete** removes the selected session through its writer, after a
  confirmation. Deleting the currently active session first switches the view
  to a fresh empty in-memory session (via the switch protocol, §6), then
  deletes. The dialog also offers **New Session**. Browsing or dismissing
  history leaves the current conversation unchanged.
- After resume, render the transcript, including unconfirmed records and an
  interrupted-session banner where applicable. Project binding validation
  and read-only handling remain as specified in §7. No model call or tool
  execution starts merely because a session was resumed.
- Empty history displays a short explanation that conversations appear after
  the first message is sent. This UI initially covers DEV/PLAN only (§5).

### 10. Implementation order

1. `SessionStore` in core: checkpoint commit protocol, WAL transcript,
   recovery, lock, store identity, schema — unit-testable without UI
   (crash-injection tests between every write step).
2. Turn-boundary commit hooks in `AbstractChatService` (end of `call()`,
   cancel, exception) + all out-of-call mutation hooks (clear, both
   compaction paths, `startImplementation`).
3. `PeonAiService`: per-session writer, sessionId/generation,
   switch & `restoreSession()` (DEV/PLAN aggregate), project-binding capture
   & per-tool validation; fresh sessions on startup/view reopen and lazy
   persistence on first submission.
4. UI: top-level New Session / History actions and session-list dialog
   (§9.1); explicit resume, delete, and new session; render restore from
   transcript incl. "unconfirmed" markers; interrupted-session banner.
5. Retention/size caps + preference page (base path with open-vs-migrate,
   disable toggle).
6. Phase 2: AGENT / QUERY_TO_SOURCE aggregates.

### Verification scenarios

- Restart Eclipse or close and reopen the Peon AI view after a conversation
  → empty new session; the previous conversation remains available through
  History. Focusing or revealing the existing view preserves its session.
- Open the view or press New Session repeatedly without sending a message
  → no empty session directories or history rows. Submit the first message
  → a persisted session exists before the model call, even if the turn fails.
- Browse or dismiss History → no active-session change. Explicitly
  resume a row → its DEV/PLAN state and transcript return without an AI call;
  title, project, last-used ordering, and interrupted indicators are correct.
- Press New Session, Resume (another session), or close/reopen the view while
  a turn is running → cancel is requested without blocking the UI; late
  callbacks cannot populate the new/resumed session, and the old session's
  saved history remains available.
- Clear a persisted session → history row and transcript survive; resume
  shows the full transcript with a cleared-context marker and empty model
  memory. Delete the active session from History → view switches to a fresh
  empty session, files are removed.
- Interrupted sessions older than maxAge are cleaned up; fresher ones survive
  maxCount/size eviction.
- Two Eclipse instances creating the same workspace store concurrently; a
  copied store opened from a different workspace (identity refusal).
- Kill between each pair of commit-protocol steps (checkpoint write, CURRENT
  switch, transcript append) → previous revision restores, torn JSONL line
  dropped.
- Hard kill right after a file-modifying tool ran → session flagged
  interrupted with that tool listed as intent-without-result; no auto-rerun.
- Concurrent send + compress on one session → serialized by the writer;
  stale generation dropped at execution time.
- Kill during compression (either path) → previous revision intact; both
  paths emit `COMPACTED` when they succeed.
- Switching sessions while a turn is running → no cross-session bleed.
- Restoring with a different project selected → read-only + explicit re-bind;
  selecting another project mid-session does not retarget tools.
- Shutdown while a tool is inside `Display.syncExec()` → no deadlock; next
  start recovers the turn as interrupted.
- Retention run while an old session is being opened → protected; disk-full
  during commit → last good checkpoint intact, failure surfaced.

---

## Review history

- 2026-10-13 — user-approved lifecycle and UI decisions: always start fresh
  after Eclipse restart or Peon AI view reopen; resume only from history;
  top-level New Session / History actions; recent-first session rows with
  title, project, last-used time, and interrupted status; persist new sessions
  only on the first submitted message.
- 2026-10-13 (follow-up review) — closed four gaps in the lifecycle update:
  interrupted sessions now expire by maxAge (protection limited to
  maxCount/size eviction, preventing immortal crash leftovers); Clear
  redefined as context-drop that keeps the session, transcript, and history
  row (`CLEARED` event); deleting the active session from History switches to
  a fresh session first; resume-while-running and no-binding display ("—")
  specified. Confirmed intent: restart never loses conversations — a fresh
  in-memory session is shown, previous ones resume from History.
- v1 (state location, `ChatMemoryStore` injection, save on `onChatResponse`,
  name-only directories) — rejected by adversarial review (Codex,
  2026-10-02), 7 high findings: wrong save point, no transactional boundary
  in `ChatMemoryStore`, incomplete aggregate, stale-callback contamination,
  missing project binding, storage ownership races, compression destroying
  saved originals.
- v2 — second review (Codex rescue, 2026-10-08), 11 findings, led to v3:
  crash-safe interrupted detection via WAL transcript (was: exception-hook
  only), single checkpoint file + `CURRENT` pointer (was: separate
  meta/memory files), explicit `{dev, plan, activeMode}` aggregate in every
  checkpoint, project binding enforced per tool execution (was: restore-time
  only), non-blocking shutdown (was: dispose joins the Job — deadlock via
  `Display.syncExec`), per-session writer serializing all mutations, both
  compaction paths emitting `COMPACTED` (CompactSessionTool does not call
  `compressContext()`), split schema/message-format versioning with
  fixtures, size caps + active-session protection in retention, open-vs-
  migrate semantics for base-path changes, 16-char hash + store identity
  verification.
