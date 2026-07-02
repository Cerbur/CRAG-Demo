/**
 * Unit tests for the bounded concurrency worker pool.
 *
 * Asserts:
 *  - At most `concurrency` tasks run simultaneously.
 *  - Results are returned in input order.
 *  - A rejecting task becomes a `{ ok: false, error }` entry; it never aborts
 *    the other tasks (partial-failure contract for the API Keys index page).
 *  - Default concurrency is 4.
 */
import { describe, it, expect } from 'vitest';
import { runWithPool } from './worker-pool';

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe('runWithPool', () => {
  it('runs all tasks and preserves order', async () => {
    const tasks = [() => Promise.resolve('a'), () => Promise.resolve('b'), () => Promise.resolve('c')];
    const results = await runWithPool(tasks, 2);
    expect(results.map((r) => (r.ok ? r.value : null))).toEqual(['a', 'b', 'c']);
  });

  it('never exceeds the concurrency cap', async () => {
    let active = 0;
    let peak = 0;
    const tasks = Array.from({ length: 10 }, (_, i) => async () => {
      active += 1;
      peak = Math.max(peak, active);
      await delay(10);
      active -= 1;
      return i;
    });
    await runWithPool(tasks, 4);
    expect(peak).toBeLessThanOrEqual(4);
  });

  it('isolates a rejected task as a PoolOutcome error without aborting others', async () => {
    const tasks = [
      () => Promise.resolve('ok-1'),
      () => Promise.reject(new Error('boom')),
      () => Promise.resolve('ok-3'),
    ];
    const results = await runWithPool(tasks, 2);
    expect(results[0]!.ok).toBe(true);
    expect(results[1]!.ok).toBe(false);
    if (!results[1]!.ok) {
      expect((results[1]!.error as Error).message).toBe('boom');
    }
    expect(results[2]!.ok).toBe(true);
  });

  it('default concurrency is 4', async () => {
    let active = 0;
    let peak = 0;
    const tasks = Array.from({ length: 8 }, () => async () => {
      active += 1;
      peak = Math.max(peak, active);
      await delay(5);
      active -= 1;
    });
    await runWithPool(tasks);
    expect(peak).toBeLessThanOrEqual(4);
  });

  it('handles an empty task list', async () => {
    const results = await runWithPool([], 4);
    expect(results).toEqual([]);
  });

  it('clamps concurrency below 1 to 1', async () => {
    let active = 0;
    let peak = 0;
    const tasks = Array.from({ length: 3 }, () => async () => {
      active += 1;
      peak = Math.max(peak, active);
      await delay(5);
      active -= 1;
    });
    await runWithPool(tasks, 0);
    expect(peak).toBe(1);
  });
});
