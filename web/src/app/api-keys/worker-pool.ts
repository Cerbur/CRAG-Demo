/**
 * Bounded concurrency worker pool.
 *
 * Used by the standalone API Keys index page ({@link useApiKeyIndex}) to fetch
 * each KnowledgeBase's keys with a hard concurrency cap of 4 (per plan_22
 * §22.6 acceptance: "聚合并发最大 4"). The pool is intentionally tiny and
 * dependency-free — no external scheduler.
 *
 * Design:
 *  - Input: an array of tasks (functions returning promises) and a concurrency
 *    limit.
 *  - Output: a promise of result-objects that preserve the input order and
 *    carry either a `value` (success) or an `error` (failure). A single task
 *    rejecting does NOT abort the others — partial failure is first-class so
 *    the index page can render successful KBs alongside failed ones.
 */
export interface PoolResultOk<T> {
  readonly ok: true;
  readonly value: T;
}
export interface PoolResultErr<E = unknown> {
  readonly ok: false;
  /** The original rejection reason (typed as unknown defensively). */
  readonly error: E;
}
export type PoolOutcome<T, E = unknown> = PoolResultOk<T> | PoolResultErr<E>;

/**
 * Run `tasks` with at most `concurrency` in flight at any time. Returns one
 * outcome per input task, in input order. A rejected task becomes a
 * `{ ok: false, error }` entry; it never rejects the whole pool.
 *
 * `concurrency` is clamped to >=1.
 */
export async function runWithPool<T>(
  tasks: ReadonlyArray<() => Promise<T>>,
  concurrency: number = 4,
): Promise<ReadonlyArray<PoolOutcome<T>>> {
  const cap = Math.max(1, Math.floor(concurrency));
  const results: PoolOutcome<T>[] = new Array(tasks.length);

  let cursor = 0;
  async function worker(): Promise<void> {
    while (true) {
      const index = cursor;
      cursor += 1;
      if (index >= tasks.length) return;
      try {
        const value = await tasks[index]!();
        results[index] = { ok: true, value };
      } catch (error: unknown) {
        results[index] = { ok: false, error };
      }
    }
  }

  const workers: Promise<void>[] = [];
  const workerCount = Math.min(cap, tasks.length);
  for (let i = 0; i < workerCount; i += 1) {
    workers.push(worker());
  }
  await Promise.all(workers);
  return results;
}
