---
name: execute-crag-plan
description: Use when a user asks to execute, continue, resume, implement, or repair a CRAG-Demo Plan, including short requests such as “执行 plan7”, “继续 plan_7”, and fixes returned by an independent Plan acceptance session.
---

# Execute CRAG Plan

Execute exactly one CRAG-Demo Plan as an implementation session. Treat `constraints/plan-workflow.md` as the sole authority; this Skill fixes the operating sequence, not the workflow rules.

## Hard boundaries

- Act as the execution session, never the independent acceptance session.
- Never mark a task or Plan `completed`.
- Never edit implementation code while acting on an acceptance-only request.
- Never combine the implementation commit with the handoff bookkeeping commit.
- Never push, open a PR, merge, rewrite history, or modify unrelated work unless explicitly requested.
- Preserve pre-existing workspace changes.

## 1. Resolve and read

1. Resolve `plan7`, `Plan 7`, or `plan_7` to one Plan file under `plan/`.
2. Read, in order:
   - `AGENTS.md`
   - `constraints/plan-workflow.md`
   - `constraints/test-workflow.md`
   - `plan/index/README.md`
   - the target Plan and relevant Hotfixes
   - constraints routed by the target files and behavior
3. Inspect `git status --short`, recent commits, declared implementation hashes, and relevant diffs.
4. Reconstruct progress from repository facts. Do not trust status text alone.

Stop if the target is ambiguous, required files are missing, or existing user changes overlap the task.

## 2. Select the legal path

| Plan state | Action |
| --- | --- |
| `ready` | Start the first valid unfinished task and move Plan/task to `in_progress`. |
| `in_progress` | Resume only unfinished or acceptance-returned tasks. Preserve accepted/completed work. |
| `verifying` | Do not implement. Tell the user to start a fresh independent acceptance session. |
| `blocked` | Resume only when the recorded unblock condition is demonstrably satisfied; record the transition reason. |
| `completed` / `abandoned` | Do not implement under this Plan. Apply the Hotfix/new-Plan rules from the authority document. |
| `draft` | Do not code. Complete planning and reach a committed `ready` state first. |

If scope, acceptance criteria, module boundaries, or a key decision must change, update and commit the Plan plus index before touching implementation.

## 3. Implement one recoverable unit

1. Identify the exact task, acceptance criteria, file boundary, prerequisites, and required tests.
2. Make the smallest in-scope implementation and test changes.
3. Follow every routed project constraint.
4. Run the task’s required validation. Required Docker HTTP or external-provider checks may not be replaced by lighter tests.
5. If a required check is blocked, record the blocker according to `plan-workflow.md`; do not claim success.
6. Check the diff for unrelated files and secrets.

For an acceptance-returned defect, repair the recorded findings and add an appropriate regression check where the project test rules require one. Do not silently discard prior failure evidence.

## 4. Commit implementation

Create an implementation commit containing the implementation, tests, and directly affected product/constraint documentation.

Do not include Plan progress, implementation hashes, handoff status, or index queue changes in this commit.

After commit, capture its real short hash and verify that `git show --stat <hash>` belongs to the target task.

## 5. Create the independent handoff

In a separate bookkeeping change:

1. Append the implementation hash to every task it actually serves.
2. Mark implemented tasks `verifying / 待验收`; leave completion dates empty.
3. Append concise, factual self-test evidence to the Plan.
4. Move the whole Plan to `verifying` only when every remaining effective task is `verifying`, `completed`, or `abandoned` as allowed by the authority document.
5. Synchronize `plan/index/README.md`:
   - remove a handed-off Plan from the execution queue;
   - add it to the acceptance queue;
   - keep dependent Plans blocked from execution.
6. Create a separate `docs(...)` handoff commit.

The handoff commit is never implementation evidence and must not be written into a task’s implementation-hash column.

## 6. Finish the session

Report:

- task(s) implemented;
- implementation commit hash(es);
- handoff commit hash;
- tests actually run and any skipped/blocked checks;
- that a new agent session must perform independent acceptance.

Do not say the Plan is complete. The terminal outcome for this Skill is either:

- clean handoff to independent acceptance;
- an accurately recorded `in_progress` or `blocked` state;
- no action because the requested transition is illegal.

## Before yielding

- [ ] Target Plan, index, constraints, Git state, and declared hashes were read.
- [ ] Only legal unfinished work was modified.
- [ ] Required validation was run or explicitly recorded as blocked.
- [ ] Implementation and handoff are separate commits.
- [ ] Real implementation hashes are in the Plan.
- [ ] Plan and index queues agree.
- [ ] Nothing was marked completed by this execution session.
