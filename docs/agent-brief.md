# Taxi Inspector — Agent Brief

> **Required every session:** read this file after root `AGENTS.md`, then read `build-status.md`. This brief is deliberately compact and stable; do not put phase progress, test counts, or next tasks here.

## What this project is

Taxi Inspector is a small, offline Android app that helps a rider independently estimate a taxi fare. It is never a certified or regulated taximeter. A user supplies the initial, distance, and waiting tariffs in any consistent local unit; the app has no currency identity, conversion, or exchange-rate feature.

The safety principle is simple: uncertain GPS freezes billing. The app never estimates a fare across missing, stale, weak, or ambiguous location data.

## Non-negotiable guardrails

- Money and tariff units are exact, non-negative decimals. Do not use `Double` or `Float` for them; use those types only for GPS measurements and geometric distance.
- The `ride` package is pure Kotlin: no Android, Room, Compose, or service imports.
- A running ride has one writer: the foreground tracking service. UI observes state and sends explicit commands only, and holds no `Context`, `Location`, or service reference in a ViewModel.
- Keep no route or raw location history. An active snapshot may retain only a temporary baseline point for the current calculation.
- Do not add networking, analytics, accounts, maps, cloud sync, advertising identifiers, or currency features without explicit approval.

Exact product behaviour and acceptance criteria live in `taxi-inspector-design.md`; this is only the startup summary.

## Where to trust each fact

| Need | Read |
| --- | --- |
| Current implementation, evidence, risks, next gate | `build-status.md` |
| Task-to-source map | `project-index.md` |
| Product rules, GPS/fare semantics, UI flows | `taxi-inspector-design.md` |
| Target architecture, persistence, service boundaries | `code-structure.md` |
| Phase order and completion criteria | `implementation-plan.md` |
| Durable decisions and verified local tooling facts | `project-memory.md` |

`build-status.md` wins for current-state conflicts. The design document wins over architecture if a target design contradicts a product rule.

## Session protocol

1. Read `AGENTS.md`, this brief, and `build-status.md`.
2. Identify the active phase and its next gate. Do not skip essential phases.
3. Use `project-index.md` to find the real source and read the task-routed specification. For any code, test, build, or refactoring task, read `implementation-plan.md` before editing.
4. Make the smallest change that satisfies the current gate; add or update tests for behaviour.
5. Run the relevant verification. If the phase materially advances, completes, or blocks, update `build-status.md` in the same change.
6. Update `project-memory.md` only for a durable decision or verified environment fact; update design/structure documents when their contract changes.

## Local verification

The verified local setup is Android Studio Panda’s JBR with Android API 35. The detailed paths are in `development-environment.md` and `project-memory.md`.

```bash
GRADLE_USER_HOME=/tmp/taxi-inspector-gradle \
JAVA_HOME=/opt/android-studio-for-platform/jbr \
./gradlew --no-daemon test
```

`local.properties` is machine-specific and ignored; never commit it.
