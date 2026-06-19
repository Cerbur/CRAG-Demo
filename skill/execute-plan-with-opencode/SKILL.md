---
name: execute-plan-with-opencode
description: Use when the user explicitly invokes $execute-plan-with-opencode to execute one CRAG-Demo Plan. codex does light orchestration and acceptance; OpenCode does the actual coding non-interactively. Designed to minimize codex token consumption.
---

# Execute Plan with OpenCode

## Purpose

Execute one CRAG-Demo Plan as a controlled state machine. **codex is the orchestrator + light acceptor; OpenCode is the non-interactive implementer.** codex never replays OpenCode's process output, never does line-by-line code review, and never re-runs tests per task. The expensive work (implement, self-check, run tests, report) is pushed down to OpenCode so codex token stays low.

Never invoke this Skill implicitly.

`constraints/plan-workflow.md` is the highest authority for Plan states, commits, completion, index updates, and validation. If this Skill conflicts with that document, stop and follow the constraint document.

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

## Token discipline (the whole point of this Skill)

Six rules, each tied to a concrete saving:

1. **No SubAgent, no PTY relay.** Call OpenCode once via `opencode run --format json` and let codex parse only key fields. Never relay OpenCode's streaming output into codex context.
2. **Rules live in config, not in prompts.** Implementation rules are固化 in `opencode-config/agents/crag-plan-implementer.md`. codex's per-task prompt carries only task context — rules are never re-sent through codex.
3. **No line-by-line review by codex.** Use `git diff --stat` boundary checks + audit OpenCode's test report instead. codex does not re-read full diffs.
4. **No per-task test re-run by codex.** OpenCode runs tests inside the task and reports pass/fail counts. codex audits the report; only the final Plan regression re-runs a scripted check.
5. **Short structured reports only.** OpenCode returns the 6-block contract (STATUS / CHANGED-FILES / TESTS-RUN / SKIPPED-OR-BLOCKED / SCOPE-CLAIM / NOTES). codex `tail`s or `jq`s these; no 9-paragraph narratives.
6. **Continue the session on repair.** Failed acceptance reuses the same OpenCode session (`-c --session`) with only findings; never spin a fresh implementer for repair.

## Phase 1: Preflight

### Check OpenCode and the non-interactive path

Run:

```bash
command -v opencode
opencode --version
```

Then verify the non-interactive path and config injection work end-to-end with one smoke call:

```bash
OPENCODE_CONFIG_DIR="<SKILL_DIR>/opencode-config" \
opencode run --agent crag-plan-implementer -m <provider/model> --dir . --format json \
  "reply with: SMOKE OK" 2>&1 | tee build/opencode-smoke.log
```

Replace `<SKILL_DIR>` with this Skill's absolute directory and `<provider/model>` with any model the user already authed.

- If OpenCode is absent → stop, tell the user it must be installed.
- If the smoke call errors on the `crag-plan-implementer` agent or `OPENCODE_CONFIG_DIR` → that opencode version has a known config-dir bug (refs: opencode #3610/#4399). Stop and instruct the user to either upgrade opencode or fall back to `OPENCODE_CONFIG_CONTENT` inline JSON (see Recovery).
- If `--format json` is unsupported or the schema differs → proceed using `tail` on the log + `git diff --stat` as the source of truth; do not depend on a precise JSON schema.

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

Require the target Plan to use the current workflow version and be `ready` before implementation. The eleven checks below were previously done by hand; now run the validator first and only inspect manually what it cannot see:

```bash
python3 scripts/validate_plans.py --strict <plan-path>
```

Then confirm the validator cannot verify these:

1. Goals, scope, and non-goals are explicit.
2. Tasks, prerequisites, execution order, and file boundaries are explicit.
3. Every task has the required fixed fields and acceptance criteria.
4. Test scope and reproducible commands are defined.
5. Effects on project constraint documents are addressed.
6. Risks, rollback, compatibility concerns, and decisions are resolved.
7. No blocking TODO, unresolved decision, or contradiction remains.
8. The ready Plan and index have already been committed.

If any condition fails:

- do not invoke OpenCode;
- invoke `$grill-me` and resolve one decision at a time with the user;
- update the Plan and required index files;
- rerun the validator and the manual checks.

Proceed only when every condition passes.

## Phase 3: Model Selection

Run `opencode models` and present the configured candidates to the user. If the command requires system authorization, request it and retry.

Wait for an explicit `provider/model` choice. Use that model for every implementation and repair session in this Plan. Do not ask again per task unless the selected model becomes unavailable or the user requests a change.

Provider credentials live on the target machine's global OpenCode config (e.g. `~/.config/opencode/`). This Skill carries no credentials. `OPENCODE_CONFIG_DIR` supplements the global config (per opencode #9062) — global credentials are not lost.

## Phase 4: Task Loop

Execute unfinished tasks in Plan order. Fully close one task before starting the next.

### Run one task (codex light orchestration)

For every task codex:

1. Reads `references/implementation-prompt.md` and fills the placeholders (Plan path, task, acceptance criteria, owned scope, protected files, constraint paths).
2. Issues **one** `opencode run` call (see the invocation convention below), piping to a per-task log under `build/`.
3. Parses only the structured report block and session id from the log (`tail`/`jq`). Does **not** read the full log.
4. Runs the light acceptance checklist (boundary check + test-report audit + scope claim + Plan validator).
5. On pass → creates the implementation commit, marks the task `verifying`, then backfills the commit hash to `completed` in a separate bookkeeping commit.
6. On fail → repairs via `references/repair-prompt.md`, continuing the same session, up to 3 rounds.

### Light acceptance checklist (per task)

This replaces the old four-axis line-by-line review. codex does only:

1. **STATUS** == `completed`? If not → repair.
2. **Boundary check**: `git diff --stat` file list ⊆ owned scope? Out-of-scope → fail.
3. **Test audit**: TESTS-RUN covers the acceptance-required scope? Any SKIPPED without a stated reason → fail.
4. **Scope claim**: SCOPE-CLAIM confirms in-scope and flags no decision needed → else fail.
5. **Plan validator**: `python3 scripts/validate_plans.py --strict <plan-path>` (format only, light).

All pass → accept the task. Any fail → repair (≤3 rounds, same session).

### Accept the task

After the checklist passes:

1. codex creates the task implementation commit.
2. codex marks the task `verifying / 待验收` with commit field `pending`.
3. In a separate Plan bookkeeping commit, codex backfills the implementation short hash and marks the task `completed / 完成`.
4. The bookkeeping commit itself is not task implementation evidence.

Do not use `—`, an empty value, or an uncommitted workspace to mark a task complete. If the user explicitly prohibits commits, stop at `verifying`; the task and Plan cannot be completed.

Then start the next task with a fresh `opencode run` (new session).

## OpenCode invocation convention

This is the single canonical call shape. Fill placeholders, run via Bash, capture the log.

```bash
OPENCODE_CONFIG_DIR="<SKILL_DIR>/opencode-config" \
opencode run \
  --agent crag-plan-implementer \
  -m "<provider/model>" \
  --dir "<repo-root>" \
  --format json \
  "<prompt>" \
  2>&1 | tee "build/opencode-task-<task-id>.log"
```

Extract (parse only these, never the whole log; verified on opencode 1.17.4):

```bash
# final response text (the structured report block)
jq -r 'select(.type=="text") | .part.text' "build/opencode-task-<task-id>.log" | tail -n 80

# session id for repair continuation
jq -r '.sessionID // empty' "build/opencode-task-<task-id>.log" | head -n1

# exit reason and token cost (from the step_finish event)
jq -r 'select(.type=="step_finish") | {reason: .part.reason, tokens: .part.tokens}' "build/opencode-task-<task-id>.log"
```

Repair continues the same session:

```bash
OPENCODE_CONFIG_DIR="<SKILL_DIR>/opencode-config" \
opencode run -c --session "<session-id>" \
  -m "<provider/model>" --dir "<repo-root>" --format json \
  "<repair-prompt>" 2>&1 | tee -a "build/opencode-task-<task-id>.log"
```

Notes:

- Every call sets `OPENCODE_CONFIG_DIR` to this Skill's `opencode-config/` so the `crag-plan-implementer` agent and its permission whitelist load. This directory is committed with the Skill, so the same call works on any machine with opencode + provider auth.
- Never use `--dangerously-skip-permissions`. The whitelist in `opencode-config/` denies git writes, destructive ops, and edits to `plan/`, `constraints/`, `AGENTS.md`, `skill/`.
- If `--format json` output differs from the extractor above, fall back to `tail` on the log + `git diff --stat`. Do not depend on a precise JSON schema.

## Phase 5: Plan Regression

After all tasks close, run the Plan regression as a scripted check (codex reads only exit code + summary lines, not full output):

```bash
python3 scripts/validate_plans.py --strict --verify-git <plan-path>
```

Plus a cross-task boundary check against the recorded baseline:

```bash
git diff --stat <baseline-ref> -- ':!plan' ':!build'
```

Audit that:

- every acceptance criterion still has current evidence;
- no required test is skipped without a stated reason;
- Docker HTTP regression, if required by the risk-trigger rules, was triggered (not silently skipped).

Do not accept skipped required tests, environmental blockers, or undocumented residual risks as completion.

## Phase 6: Final Acceptance

Declare the Plan complete only when:

- all tasks and acceptance criteria have evidence;
- Plan regression passed;
- Docker HTTP regression, if required by risk-trigger rules, used Docker Compose;
- no required test is skipped and no blocker remains;
- every completed task has one or more real implementation commit hashes;
- `python3 scripts/validate_plans.py --strict --verify-git <plan-path>` succeeds;
- the Plan progress table and `plan/index/README.md` are current;
- the workspace has no uncommitted changes owned by the Plan;
- the final report maps every acceptance criterion to code and test evidence (codex composes this from the per-task reports already in context, not from re-reading diffs).

Otherwise report partial completion or blocked status and do not mark the Plan complete.

An explicit request to execute a `ready` Plan authorizes necessary local Plan-scoped commits. It does not authorize push, PR creation, merge, history rewriting, or unrelated changes. If the user explicitly requests no commits, the Plan cannot reach `completed`.

## Recovery

On interruption:

1. reread the Plan and constraints;
2. reconstruct the workspace baseline and current diff;
3. identify accepted tasks from code and test evidence, not only `🔄` or `✅` markers;
4. read the per-task logs under `build/opencode-task-*.log` to recover session ids and last status;
5. continue a session only when its workspace and code state provably match;
6. otherwise start a fresh `opencode run` (new session) from current code.

Keep run-specific state out of Git. Store session IDs, model choice, stage, and logs only in the conversation or git-ignored `build/` paths.

### `OPENCODE_CONFIG_DIR` fallback

If Preflight shows the config-dir path is broken on the installed opencode version, fall back to inline config via `OPENCODE_CONFIG_CONTENT`. Build the JSON from `opencode-config/opencode.json` (embed the `agent.crag-plan-implementer` block) and set:

```bash
OPENCODE_CONFIG_CONTENT='<json from opencode-config/opencode.json>' opencode run ...
```

This keeps the same agent definition and permission whitelist without relying on the directory mechanism.

## Prompt Resources

- Read `references/implementation-prompt.md` before each task's `opencode run`.
- Read `references/repair-prompt.md` before each repair round.
- The implementation rules themselves live in `opencode-config/agents/crag-plan-implementer.md` (loaded by opencode, never re-sent through codex).
