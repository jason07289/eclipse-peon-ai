# Query-to-Source Mode

## Why Query-to-Source?

Query-to-Source is for **repeatable, team-defined workflows** — most often
**SQL or MyBatis XML → layered Java sources** (standardize query → mapper/DTO/DAO → review →
service layer → review again).

The same chat and workspace tools as **dev** mode apply: Peon can read and write project files,
show changes in **Undo / Keep**, and run a compile check after generation steps. The difference is
**not** capability — it is **intent**:

| | **dev** | **Query-to-Source** |
|---|---------|---------------------|
| **Goal** | General development; open-ended tasks | Run a **fixed pipeline** your team defined |
| **How rules are applied** | Conversation, AGENTS.md, skills, slash commands | **Per-step command/skill** selected in settings |
| **Guidance** | You steer each turn | **Step buttons** show order; completed steps are marked ✓ |
| **Typical input** | Anything | SQL, XML, notes, selections — per step |
| **Peon's role** | Implement what you ask | **Orchestrate** the pipeline; execute one step at a time |

Peon only **orchestrates** — naming, packages, file layout, and framework rules live in the
**command/skill prompts** you attach to each step. Encode your team's process once; reuse the
same wizard for every new query.

### When to use which mode

- **Query-to-Source** — you already know the steps (표준 적용 → DAO 생성 → 검토 → …) and want
  everyone to follow them the same way.
- **dev** — exploratory work, one-off refactors, or tasks that do not match a fixed pipeline.

### How team rules are fixed

1. **Pipeline order** (⚙ settings) — mirrors your team's process (e.g. transform → generate →
   review).
2. **Prompt per step** — each step points at a command or skill `.md` file with that step's rules.
3. **Swap prompts, same UI** — different projects or teams change settings only; Peon still runs
   steps in sequence.

---

Query-to-Source is a guided wizard that turns SQL, MyBatis XML, and related inputs into source
code using that pipeline. The sections below describe how to switch modes, use the chat input,
configure steps, and track session progress.

## Switching to the mode

Select **`query-to-source`** in the mode dropdown of the chat action bar. The normal **chat input**
stays visible (same as dev/plan), and a row of **step buttons** appears below it (one button per
configured pipeline step).

## Input

Use the chat input for each step — not a separate query editor:

- **Plain SQL** — e.g. `SELECT user_id, email FROM users WHERE ...`
- **MyBatis mapper XML** — e.g. `<select id="findById">...</select>`
- **Notes or instructions** for that step
- **Editor selections** (appended automatically when the input has no text of its own, same as dev mode)
- **Follow-up messages** in the conversation between steps

You can also send free-form messages with Enter (like dev/plan) without pressing a step button.
Step buttons run the configured prompt for that pipeline step against your chat input and the
conversation history.

## The step pipeline

The workflow is fully configurable. Open **⚙ Query-to-Source Settings** to define an ordered
list of steps. Each step has:

| Field | Meaning |
|-------|---------|
| **Label** | Button text in the wizard bar |
| **Kind** | What happens after the AI call (see below) |
| **Prompt** | Command or skill to run (from your loaded `.md` files) |

### Step kinds

- **Transform (질의 변환)** — runs the prompt against your input; the result stays in the chat
  (and in conversation memory for later steps).
- **Generate (코드 생성)** — runs the prompt to create or update source files via workspace
  edit tools; a **compile check** runs afterward; changed files appear in the **Undo / Keep**
  review bar.
- **Review (검토)** — runs the prompt to review inputs and generated sources; the result
  stays in the chat (no automatic file changes).

### Default example pipeline

A fresh install ships with this example order (prompts are empty until you assign them):

1. 표준 적용 — Transform
2. DAO 생성 — Generate
3. 표준 검토 — Review
4. Service 생성 — Generate
5. 표준 검토 — Review

You can add, remove, reorder (**Up / Down**), or change any step to match your team's process.

Generate and Review steps require an **open project** to be selected (workspace file access).
Transform steps only need chat input or prior conversation context.

## Session progress

Within a session Peon remembers which pipeline steps you already completed:

- Finished steps show a **✓** prefix on their button and are **disabled** so you cannot
  accidentally re-run them.
- Steps you have not run yet stay enabled (you may skip ahead if your process allows it).
- Progress is stored in memory only — it is **not** written to preferences.

Progress resets when you:

- Press **Clear** in the action bar
- Switch away from `query-to-source` and back (mode re-entry)
- Change the pipeline in **⚙ Query-to-Source Settings** (add/remove/reorder steps)

To start the same workflow from scratch, use **Clear** and run the steps again.

## Configuring steps (⚙)

Press **⚙** in the wizard bar to open **Query-to-Source Settings**:

- Use **Add / Edit / Remove** to manage steps.
- Use **Up / Down** to change execution order (button order left-to-right matches table order).
- Pick a **Prompt** from all loaded **commands and skills** (see
  [Commands](/setup/commands) and [Agents & Skills](/setup/agents-and-skills)).
  Each entry is tagged with its source: `[Skill] name`, `[Command] name`, `[Skill+Command] name`
  when both exist under that name, or `[?] name` for a configured prompt that is no longer loaded.
  Only the plain name is stored — the tag is display only.

Settings are stored in Eclipse preferences and reused across sessions.

Keep **naming conventions, package layout, SQL standards, and framework rules** inside the step
prompts (commands/skills), not in Peon itself. The wizard UI stays the same; your team owns the
process by maintaining those `.md` files and the step list in ⚙ settings.

## Example mapper XML

A **Generate** step might produce MyBatis mapper XML like the following. Exact names and
structure depend on the prompt you select.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="com.example.user.UserMapper">

    <select id="findById" parameterType="long" resultType="com.example.user.User">
        SELECT user_id, email, user_name
          FROM users
         WHERE user_id = #{id}
    </select>

</mapper>
```
