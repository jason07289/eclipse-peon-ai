---
title: Commands
description: Custom slash commands for Eclipse Peon AI
---

# Commands

Commands allow you to define reusable slash commands (e.g., `/review`, `/plan`) that can be invoked in the chat interface. These commands are loaded from `.md` files in a configured directory and provide a way to standardize or automate common tasks.

Commands are basically `SKILLS` light for common stuff you don't want repeat yourself each time. Ask the LLM to create/extract them if needed.

> DISK Tools needed!

## Configuration

### Directory Setup
1. Configure the commands directory in the Peon AI preferences:
   - Navigate to **Window > Preferences > Peon AI**.
   - Set the "Commands directory" field to the path containing your command files.
   - Path resolution matches the existing skills directory behavior, including support for workspace-relative paths.

### Command Files
- Each command is defined in a separate `.md` file (e.g., `review.md`, `plan.md`).
- The filename (without the `.md` extension) becomes the command name (e.g., `/review`, `/plan`).
- Files in subdirectories or hidden files (starting with `.`) are ignored.
- the content is just he command
- header is optional

### Optional Frontmatter

### `review.md`

```markdown
Review the code and report any issues.
```


### `foo.md`

```markdown
---
name: review
---
Review the code and report any issues.
```

## Usage

1. In the chat interface, type `/` to see a list of available commands.
2. Select a command to insert its name into the chat input.
3. The command body replaces the system prompt for that turn. Standing orders (project context, AGENTS.md) and the skill catalog are still appended after it.

## Effect

- Commands provide a quick way to insert predefined prompts or instructions into the chat.
- They help standardize workflows and reduce repetitive typing.
- The LLM processes the command body as the system prompt for that single turn, ensuring consistent responses.

## Notes

- Commands are case-insensitive.
- Tool rules remain per mode (PLAN/DEV/AGENT) as before.
