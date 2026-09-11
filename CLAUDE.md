@AGENTS.md

## Why this file exists

Claude Code reads `CLAUDE.md`, not `AGENTS.md`. The import above is what makes this
repository's startup reading, document-authority table, and non-negotiable product rules
reach a Claude Code session and every custom subagent, without a second copy of the text.
`AGENTS.md` stays the single home for those rules; do not restate them here.

Note that the built-in `Explore` and `Plan` agents skip this file by design. Delegate
architecture and planning work to the `software-architect` agent in `.claude/agents/`
instead, which inherits it.
