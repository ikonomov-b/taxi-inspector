# Contributing

Taxi Inspector is being developed in phases. Keep changes focused on the active phase and preserve the product guardrails documented in `AGENTS.md` and `docs/`.

## Before opening a change

1. Read `AGENTS.md`, `docs/agent-brief.md`, and `docs/build-status.md`.
2. Read the task-specific product or architecture document.
3. Keep the `ride` domain free of Android, Room, Compose, and service dependencies.
4. Add or update deterministic tests for changed behaviour.
5. Run:

   ```bash
   ./gradlew --no-daemon test lintDebug
   ```

Do not add networking, accounts, analytics, maps, cloud sync, advertising identifiers, route history, or currency conversion without explicit product approval.

## Change guidance

- Use a short, imperative commit subject.
- Keep tariff and fare values exact; never use floating-point values for them.
- Update `docs/build-status.md` when a phase materially advances, completes, or becomes blocked.
- Keep `local.properties`, SDK paths, signing files, and other machine-specific secrets out of commits.
