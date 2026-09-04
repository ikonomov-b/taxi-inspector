# Taxi Inspector — Agent Startup Guide

## Mandatory startup reading

Every agent working in this repository must read these files, in order, before inspecting or changing code:

1. `docs/agent-brief.md` — the compact, stable project context and session protocol.
2. `docs/build-status.md` — the live phase status, active risks, and next gate.

Before making a change, use `docs/project-index.md` to locate the actual source and read the task-specific documents it routes to. Read `docs/project-memory.md` when the task could affect a durable product, architecture, or environment decision. Do not assume that a planned component is already implemented.

For every coding, refactoring, test, or build task, `docs/implementation-plan.md` is also mandatory reading before changing code.

## Quick orientation

Use this short route when you need the project basics quickly:

- Product rules and acceptance criteria: `docs/taxi-inspector-design.md`
- Current implementation and next gate: `docs/build-status.md`
- Actual source paths and task routing: `docs/project-index.md`
- Target architecture and boundaries: `docs/code-structure.md`
- Ordered phase work: `docs/implementation-plan.md`

The project index is the navigation hub; it points to the relevant document and source area for the task. Keep live progress in `build-status.md` rather than copying it into this guide.

## Document authority

When documents overlap, use this order rather than guessing which one is current:

| Fact needed | Authoritative source |
| --- | --- |
| Product behaviour, fare/GPS rules, and acceptance criteria | `docs/taxi-inspector-design.md` |
| Target architecture, dependencies, and package boundaries | `docs/code-structure.md` |
| Required implementation order and phase exit criteria | `docs/implementation-plan.md` |
| What is implemented, verified, blocked, or next | `docs/build-status.md` |
| Durable condensed decisions and environment facts | `docs/project-memory.md` |
| Compact every-session orientation and work protocol | `docs/agent-brief.md` |
| Navigation and task routing only | `docs/project-index.md` |

If a current-state statement conflicts with `build-status.md`, `build-status.md` wins. If a target-architecture statement conflicts with a product rule, the design document wins until both documents are deliberately updated.

## Non-negotiable product rules

- Taxi Inspector is an offline fare **estimate**, never a certified taximeter.
- A tariff is an exact, non-negative decimal in a user-defined tariff unit. There is no currency code, currency label, conversion, or exchange rate.
- Never use `Double` or `Float` for tariffs, fare components, or totals. `Double` is permitted only for GPS coordinates, accuracy, speed, and geometric distance.
- The pure `ride` domain must not import Android, Room, Compose, or service APIs.
- Weak, stale, missing, out-of-order, or ambiguous GPS data must freeze fare billing; never infer a charge across an unobserved gap.
- The tracking service is the only writer of a running active ride. UI code observes state and sends explicit commands.
- Persist no route or location history. An active snapshot may retain only the temporary baseline point required for the live calculation.
- Do not add networking, analytics, accounts, advertising identifiers, maps, cloud sync, or currency features without explicit product approval.

## Working rules

- Follow `docs/implementation-plan.md` in phase order. Do not begin a later essential phase before recording the preceding phase’s exit criteria in `docs/build-status.md`.
- Keep the build status truthful: use **In progress** until the documented verification is complete.
- Before editing, inspect the relevant current source named by `project-index.md`; the architecture tree is a target, not proof that a file already exists.
- When a change alters a product rule, state model, dependency direction, persistence schema, or verification procedure, update the relevant design/structure document and `docs/project-memory.md` in the same change.
- When a change finishes, blocks, or materially advances a phase, update `docs/build-status.md` in the same change. Do not duplicate live progress in the index or memory.
- Preserve existing user changes. Do not reset, checkout, or delete unrelated work.
- After a material implementation phase, run the relevant tests and update `docs/build-status.md` with the command and result.

## Local verification

The verified local environment uses Android Studio Panda’s bundled JBR and the API 35 SDK. See `docs/development-environment.md` for paths and setup.

```bash
GRADLE_USER_HOME=/tmp/taxi-inspector-gradle \
JAVA_HOME=/opt/android-studio-for-platform/jbr \
./gradlew --no-daemon test
```

`local.properties` is intentionally ignored and machine-specific. Never commit it.
