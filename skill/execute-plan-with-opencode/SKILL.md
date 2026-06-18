---
name: execute-plan-with-opencode
description: Use when the user explicitly invokes $execute-plan-with-opencode to execute one CRAG-Demo Plan through isolated SubAgents that drive the external OpenCode CLI.
---

# Execute Plan with OpenCode

## Purpose

Execute one CRAG-Demo Plan as a controlled state machine. Keep planning, Review, test execution, Plan progress, and final acceptance with the ParentAgent; delegate code changes to isolated SubAgents that operate OpenCode.

Never invoke this Skill implicitly.

## Required Inputs

Require one Plan directory or one Plan file under `plan/`.

- Resolve `plan/plan_N/` to its unique `plan_N.md`.
- Use an explicit Plan file directly.
- Read related hotfix files only when the target Plan references them or they constrain unfinished work.
- Reject paths outside `plan/`, ambiguous directories, missing files, and requests containing multiple main Plans.

## State Machine

Run these phases in order:

1. Preflight
2. Plan gate
3. Model selection
4. Task loop
5. Plan regression
6. Final acceptance

Do not skip a phase because code already appears partially implemented. Reconstruct evidence first.

## Phase 1: Preflight

### Check OpenCode

Run:

```bash
command -v opencode
opencode --version
```

If OpenCode is absent, stop immediately and tell the user that OpenCode must be installed before this Skill can continue. Do not create a SubAgent or modify the Plan.

### Record the workspace baseline

Record:

- current branch;
- `git status --short`;
- the initial diff;
- existing untracked files relevant to the target.

Preserve all pre-existing user changes. Never stash, reset, checkout, clean, delete, or overwrite them. Continue past unrelated changes only after defining them as protected files. If existing changes overlap files likely owned by the target task, stop and ask the user how to proceed.

Review later changes relative to this baseline.

### Read project constraints

Read:

- `AGENTS.md`;
- `constraints/plan-workflow.md`;
- `constraints/test-workflow.md`;
- constraints relevant to the target, including code style, package structure, or Docker structure;
- `plan/index/README.md`;
- the resolved Plan and required hotfixes.

## Phase 2: Plan Gate

Require all nine conditions:

1. Goals and non-goals are explicit.
2. Tasks and execution order are explicit.
3. Affected modules, interfaces, and data structures are identified.
4. Every task has acceptance criteria.
5. Test scope and recommended commands are defined.
6. Effects on project constraint documents are addressed.
7. Risks, compatibility concerns, and decisions are resolved.
8. The required CRAG-Demo progress table exists.
9. No blocking TODO, unresolved decision, or contradiction remains.

If any condition fails:

- do not create a SubAgent or invoke OpenCode;
- invoke `$grill-me` and resolve one decision at a time with the user;
- let the ParentAgent update the Plan and required index files;
- rerun all nine checks.

Proceed only when every condition passes.

## Phase 3: Model Selection

Run `opencode models` and present the configured candidates to the user. If the command requires system authorization, let the ParentAgent request it and retry.

Wait for an explicit `provider/model` choice. Use that model for every implementation, test-completion, and repair session in this Plan. Do not ask again per task unless the selected model becomes unavailable or the user requests a change.

Do not persist models, providers, or credentials in this Skill.

## Phase 4: Task Loop

Execute unfinished tasks in Plan order. Fully close one task before starting the next.

### Start one implementation SubAgent

For every task:

- create one new isolated implementation SubAgent;
- give it ownership only of that task's implementation and test files;
- require it to read `references/implementation-prompt.md`;
- fill every placeholder with the Plan path, task, acceptance criteria, selected model, workspace, protected files, and relevant constraints;
- require it to use a PTY and run `opencode run -i -m <provider/model>`;
- prohibit parallel code-writing SubAgents.

The SubAgent is an OpenCode controller, not an alternative implementer. It must not silently replace OpenCode by writing the requested production change itself.

### Double-check inside the SubAgent

Before modifying files, require the SubAgent to verify:

- `command -v opencode`;
- `opencode --version`;
- project configuration is readable;
- required credentials and selected model are usable;
- its isolated workspace contains the expected baseline.

If any check fails, return diagnostics without modifying code.

### Review the test workflow

Require the implementation SubAgent to return:

- test files and cases;
- acceptance criterion or risk covered by each case;
- exact reproducible commands;
- unit, integration, architecture, smoke, or end-to-end scope;
- external dependencies and data setup;
- expected results;
- tests OpenCode actually ran and raw outcomes;
- skipped tests, reasons, and residual risks.

The ParentAgent reviews this workflow against the Plan and `constraints/test-workflow.md`.

If the workflow is insufficient, send a follow-up to the same SubAgent and continue the same OpenCode session with `opencode run -i --session <session-id> -m <provider/model>`. Do not create a new SubAgent. Repeat until the proposed workflow is adequate or a real blocker is found.

### Review the code

After the test workflow is adequate, review the code relative to the recorded baseline on four axes:

1. Plan compliance and scope.
2. Project constraints.
3. Correctness, failure paths, transactions, concurrency, compatibility, and security.
4. Test credibility.

The ParentAgent may directly fix only Plan progress, index, or other bookkeeping documentation. It must not directly repair production or test code found defective during Review.

### Repair failed Reviews

If code Review fails:

- create a new isolated repair SubAgent and a new OpenCode session;
- require it to read `references/repair-prompt.md`;
- provide the Plan, current code, relevant test results, and only the current Review findings;
- do not pass the previous repair session or its reasoning;
- re-review after the repair.

Use a new repair SubAgent for every failed Review round. Stop automatic repair after three rounds and report the root causes, remaining findings, and recommended next action.

### Run tests as ParentAgent

After code Review passes, the ParentAgent runs the approved test workflow itself.

- Unit tests may run through Gradle normally.
- Interface, integration, end-to-end, smoke, manual integration, PostgreSQL, pgvector, Spring Boot, and Sidecar checks must run through Docker Compose.
- Never directly start Java or Python services for non-unit validation.

A failed test returns the task to code Review. Treat the failure as a new finding and create a new repair SubAgent, subject to the same three-round total limit.

### Accept the task

Mark a task complete only when its acceptance criteria, Review, and required tests have evidence. The ParentAgent alone updates the Plan progress table. Use commit hash `—` until a real commit exists.

Then start the next task with a fresh implementation SubAgent and fresh OpenCode session.

## Permission Protocol

Never use `--dangerously-skip-permissions`.

The SubAgent may approve ordinary, Plan-scoped OpenCode requests interactively, including:

- reading and editing owned project files;
- compilation, unit tests, formatting, and safe build output;
- downloading dependencies already declared by the project.

Escalate to the ParentAgent:

- any system or Codex sandbox authorization;
- new or upgraded dependencies;
- credentials or secrets;
- system environment changes;
- Docker infrastructure changes;
- Git writes, branch changes, commits, pushes, reset, checkout, clean, or stash;
- deletions, destructive commands, Plan expansion, or protected-file changes.

Return an escalation with the exact command, purpose, scope, failure or prompt, risk, and safe alternatives. Resume the same SubAgent and OpenCode session after approval when consistency is preserved.

## SubAgent Return Contract

Require this Markdown structure:

```markdown
## Status
completed | blocked | authorization-required

## OpenCode Session
<session id>

## Changed Files
- <path>: <reason>

## Implementation
<summary and notable decisions>

## Test Workflow
<cases, coverage, commands, setup, expected results>

## Executed Tests
<commands and raw outcomes>

## Skipped Tests and Risks
<items or none>

## Authorization Request
<command, purpose, scope, risk, alternatives, or none>

## Scope Check
<whether task scope changed>
```

If fields are missing, ask the same SubAgent to complete its report. Do not create a replacement solely for incomplete reporting.

## Phase 5: Plan Regression

After all tasks close, run the complete Plan regression workflow as ParentAgent. Re-review cross-task integration and verify that every acceptance criterion still has current evidence.

Do not accept skipped required tests, environmental blockers, or undocumented residual risks as completion.

## Phase 6: Final Acceptance

Declare the Plan complete only when:

- all tasks and acceptance criteria have evidence;
- final code Review passes;
- the ParentAgent has run all required tests successfully;
- non-unit validation used Docker Compose;
- no required test is skipped and no blocker remains;
- the Plan progress table and `plan/index/README.md` are current;
- the final report maps every acceptance criterion to code and test evidence.

Otherwise report partial completion or blocked status and do not mark the Plan complete.

Do not commit unless the user separately requests a commit.

## Recovery

On interruption:

1. reread the Plan and constraints;
2. reconstruct the workspace baseline and current diff;
3. identify accepted tasks from code and test evidence, not only `🔄` or `✅` markers;
4. locate the selected model, current phase, and OpenCode session from conversation or ignored runtime logs;
5. continue a session only when its workspace and code state provably match;
6. otherwise create a fresh repair SubAgent from current code.

Keep run-specific state out of Git. Store session IDs, model choice, stage, and runtime logs only in the conversation or ignored `.opencode/` / `build/` paths.

## Prompt Resources

- Read `references/implementation-prompt.md` before creating an implementation SubAgent or continuing it for test completion.
- Read `references/repair-prompt.md` before creating every repair SubAgent.
