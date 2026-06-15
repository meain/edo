package com.edo.app.agent

object BuiltinSkills {
    data class Entry(val description: String, val content: String)

    val all: Map<String, Entry> = linkedMapOf(
        "create-skill" to Entry(
            description = "Create a new skill in this project. Use when asked to create, add, or write a skill.",
            content = CREATE_SKILL_MD,
        ),
    )
}

private val CREATE_SKILL_MD = """
---
name: create-skill
description: Create a new skill in this project. Use when asked to create, add, or write a skill.
---

# Create a Skill

Skills follow the Agent Skills format (agentskills.io). Each skill is a folder under `.edo/skills/`
containing a `SKILL.md` file.

## Folder structure

```
.edo/skills/<name>/
├── SKILL.md       ← required
├── scripts/       ← optional: shell/Python helpers the instructions reference
├── references/    ← optional: extra documentation
└── assets/        ← optional: templates, data files
```

## SKILL.md format

```
---
name: my-skill
description: What this skill does and when to activate it (1–2 sentences, be specific)
---

# My Skill

Step-by-step instructions for the agent...
```

## Rules

- `name` must match the folder name: lowercase, hyphens only
- `description` is the discovery trigger — write it so it clearly matches the user requests
  that should activate this skill. This is the only part loaded at startup.
- Keep the body under 500 lines; move heavy docs to `references/`
- The agent reads the full body only when it decides the skill is relevant

## Steps

1. Pick a name (e.g. `deploy`, `run-tests`, `format-code`)
2. Draft the SKILL.md with frontmatter + step-by-step instructions
3. Use `write_file` to save `.edo/skills/<name>/SKILL.md`
4. Optionally create `scripts/` or `references/` alongside it
""".trimIndent()
