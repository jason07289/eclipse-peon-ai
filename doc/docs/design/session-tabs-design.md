# Session Tabs — Parallel Conversations Design

Status: **draft, not yet implemented** (2026-10-13). Builds on
[Chat Sessions](./chat-session-design.md) (Phase 1 implemented).

Goal: Cursor-style "+ new agent" tabs — multiple conversations open side by side in the Peon AI
view, each a session, switchable instantly, eventually running turns in parallel.

---

## What KiloCode does (source-verified, commit 27a9662)

KiloCode's tab feature is not inherited from its Roo Code origin — it is a ground-up rebuild
around one architectural decision that everything else falls out of:

> **The agent loop lives behind a session-id API, not in the UI.** `promptAsync(sessionId, …)`
> returns immediately; the turn runs in a local server process; all progress arrives as an SSE
> event stream keyed by sessionID. A tab is just a viewer bound to a sessionId.

Findings that transfer to us:

1. **Tab = sessionId, plus draft tabs**: "+" creates a UI-only draft (`PENDING_TAB_PREFIX+uuid`,
   `webview-ui/src/utils/local-tabs.ts`); the real session is created on the first send and the
   draft id is swapped in place. (We already built exactly this as "lazy persistence".)
2. **Per-session FIFO prompt queue** (`kilocode/session/prompt-queue.ts`): a prompt to a busy
   session queues ("Queued" badge); different sessions run genuinely in parallel with no extra
   code.
3. **Closing a tab never deletes the session** — it only leaves the tab list; a `closedSessions`
   LRU (1000) stops restored state from resurrecting closed tabs; history rows reopen any session
   as a tab; opening an already-open session focuses its tab (tab id = session id).
4. **Background stream throttling is the key performance trick**
   (`session-stream-scheduler.ts`): token deltas are coalesced per session — ~16 ms flushes for
   the focused tab, 150–400 ms adaptive for background tabs — so ten streaming tabs don't freeze
   the renderer.
5. **Activity state machine + attention**: per-tab
   `waiting | busy | error | done(-unseen) | idle` drives spinners/badges; sessions visible
   nowhere raise OS notifications (visibility ref-counting across surfaces).
6. **Per-session abort**, optionally `scope:"tree"` for subtasks.
7. **Parallel edits to one workspace are unsolved by tabs alone** — KiloCode's answer is opt-in
   git-worktree isolation with hard caps (4 multi-versions, 20 orchestrated tasks). Tabs alone
   get last-write-wins plus "which session touched which files" visibility.
8. Tab list persisted separately from session storage (webview state / `.kilo/agent-manager.json`),
   with pins and drag order.

## Gap analysis — peon-ai today

Already in place (chat sessions Phase 1): session = checkpoint aggregate with lazy persistence;
per-turn transcript routing (`SessionManager.TurnContext` keyed by turnId); generation-based
stale-callback guard; per-session writer; History dialog.

Missing for tabs:

| Gap | Today | Needed |
|---|---|---|
| Runtime state | ONE `{AiDeveloperService, AiPlannerService}` pair in `PeonAiService` | one pair **per tab** (precedent exists: AGENT mode already instantiates its own pair) |
| Open sessions | `SessionManager` holds one current `{dir, transcript, meta}` | a map `tabId → OpenSession`, recorders bound per (tab, mode) |
| Event routing | the view itself is the `AiMonitor`; one global `monitorRef`, one `isWorking` | per-tab monitor + per-tab progress monitor (cancel), events routed to the tab that started the turn |
| Chat rendering | one `ChatMarkdownWidget` (SWT Browser) | one widget per tab (scroll/stream state survives switching) |
| Tool targeting | `setProject()` mutates shared tool singletons | per-turn captured binding, validated at tool execution (already specified in chat-session design §7 — becomes mandatory for parallelism) |

## Design

### Tab model

- **Tab id = session id**; a "+" tab is a draft (`draft-<uuid>`) holding only in-memory state.
  The session materializes on the first send (existing lazy-persistence path) and the tab is
  re-keyed to the session id.
- Closing a tab never deletes the session (History remains the only delete surface). Closing the
  last tab leaves one fresh draft tab.
- Opening a History session that is already a tab focuses that tab; otherwise it opens a new tab
  (Resume semantics unchanged: verbatim restore, no auto-run).
- **No tab restore across restarts** — keeps the user-approved "start fresh on reopen" rule.
  Sessions survive in History; only the tab arrangement is ephemeral. (Revisit later if wanted;
  then a KiloCode-style separate tab-list store + closed-LRU is the pattern.)
- Scope: DEV/PLAN sessions only, like chat sessions Phase 1. AGENT and QUERY_TO_SOURCE stay
  single-instance; entering them hides the tab strip (or pins to the active tab) — exact UX
  decided during implementation.

### Per-tab runtime

New `SessionTab` aggregate owned by a `SessionTabManager` (Eclipse side):

```java
SessionTab {
  String tabId;                 // == sessionId once materialized
  AiDeveloperService dev;       // own memory, shared ConfiguredModel/ToolService
  AiPlannerService plan;
  PeonMode mode;                // DEV or PLAN, per tab
  ChatMarkdownWidget widget;    // lazily created, disposed on tab close
  IProgressMonitor runningTurn; // null when idle
  TabActivity activity;         // IDLE | BUSY | DONE_UNSEEN | ERROR
}
```

- `PeonAiService` delegates `getActiveService()` & SessionSource snapshots to the active tab.
  `SessionSource` becomes per-tab (the manager passes a tab-bound source when creating
  recorders), so a commit always snapshots the right pair.
- `SessionManager` grows multi-open support: `open(tabId, sessionId)` /
  `close(tabId)` keep a `Map<tabId, OpenSession>`; the existing per-turn `TurnContext` routing
  already makes events land in the right transcript even after the user switches tabs.
- The single-threaded writer stays global (it serializes cross-session index/retention writes
  too); per-session ordering continues to hold because each turn's commits are enqueued from
  that turn's thread in order.

### UI (SWT)

- `CTabFolder` replaces the single chat area: one `ChatMarkdownWidget` per tab, created lazily,
  `retainContext` by simply not disposing on switch. Input area, actions bar, status line stay
  single instances bound to the active tab (KiloCode binds mode/model per session — we bind
  `PeonMode` per tab, model stays global).
- Tab label = session title (first message, 60 chars → truncated to ~20 for the tab, full title
  as tooltip); draft tabs show "New". Activity badge: spinner while BUSY, dot for DONE_UNSEEN
  (cleared when the tab is selected), error marker on failure.
- "+" ToolItem creates a draft tab; the existing top toolbar's New Session becomes "+" on the
  tab strip; History stays and gains "open in new tab" as the Resume default.
- **Browser instance cost**: each tab is an SWT Browser (WebKit/Edge). Cap open tabs
  (default 8, preference) and show a hint when hit. Memory, not correctness, is the constraint.

### Concurrency — two stages

**Stage A (first release): parallel-safe UI, one running turn.**
Only one turn runs at a time across tabs (`isWorking` stays global). Switching away from a
running tab is allowed: its output keeps streaming into *its own* widget (per-tab monitor), the
tab shows the busy spinner, and sending from another tab is refused with a "another conversation
is still working" notice. This delivers the Cursor-like workflow (park a conversation, start
another, come back) without touching tool-targeting or rate-limit questions.

**Stage B: true parallel turns.**
- Core: per-session FIFO prompt queue (KiloCode #2); `ToolLoopRequest` carries an immutable
  per-turn `ToolTarget {project, workingDir}` and the shared write/disk tools read it from the
  request instead of mutable singletons — this completes chat-session design §7 and is the
  gating prerequisite.
- UI: background-stream throttling — coalesce `onStreamingChunk` per tab before
  `Display.asyncExec` (active tab ~16 ms, background 150–400 ms adaptive; KiloCode #4);
  per-tab cancel buttons; "Queued" badge when a busy session has stacked prompts.
- Parallel edits to the same workspace stay last-write-wins; the per-tab FileChangeReview shows
  which session touched which files. Worktree isolation is explicitly out of scope.

### What we deliberately copy / skip from KiloCode

Copy: draft tabs, tab=sessionId, close≠delete, focus-existing-on-reopen, activity badges,
background throttling (Stage B), per-session queue (Stage B).
Skip for now: multi-surface visibility ref-counting + OS notifications (single Eclipse view),
drag-reorder/pinning, tab-list persistence across restarts, Agent-Manager-style worktree
orchestration, subagent tabs.

## Implementation order

1. **Core**: `SessionManager` multi-open (`Map<tabId, OpenSession>`), per-tab recorder/source
   binding; unit tests (two tabs committing interleaved, events landing in the right transcript,
   close-tab-while-turn-runs → interrupted-on-reload stays correct).
2. **Eclipse state**: `SessionTabManager` + per-tab service pairs; `PeonAiService` delegation;
   per-tab monitor replacing the view-as-monitor pattern (the view keeps implementing
   `EclipseAiMonitor` as a thin router).
3. **UI**: `CTabFolder` + per-tab widgets; "+" draft tabs; tab titles/activity badges; History
   "open in tab"; tab cap preference.
4. **Stage A polish**: busy-tab send refusal, DONE_UNSEEN badge, close-last-tab → fresh draft.
5. **Stage B**: per-session queue, immutable per-turn ToolTarget (design §7 completion),
   stream throttling, per-tab cancel, parallel verification scenarios.

## Verification scenarios

- Two tabs, interleaved sends (Stage A: second refused while first runs; Stage B: both run,
  transcripts and checkpoints never cross).
- Switch away during streaming → output lands in the originating tab only; badge appears;
  switching back shows the full stream.
- Close a streaming tab → Stage A: turn canceled non-blockingly; session resumable, WAL marks
  it interrupted if the cancel commit lost the race. Close ≠ delete in History.
- Open the same History session twice → second open focuses the first tab.
- 8-tab cap: 9th "+" shows the hint; memory stays bounded (manual check with the platform
  Browser).
- Restart → single fresh draft tab; previous tabs' sessions all present in History.
- Stage B only: two tabs editing different projects → each turn's tools hit only its captured
  project; same-session double prompt queues with a badge.

## Open decisions (need user input)

1. Stage A first, Stage B after it has soaked? (recommended)
2. Tab cap default (8?) and whether AGENT/QUERY_TO_SOURCE hide the tab strip or pin to one tab.
3. Should tabs survive restart later (KiloCode does; our current rule says no)?
