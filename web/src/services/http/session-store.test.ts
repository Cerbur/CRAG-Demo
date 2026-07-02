import { describe, it, expect } from 'vitest';
import { createSessionStore } from './session-store';

describe('sessionStore', () => {
  it('starts empty', () => {
    const s = createSessionStore();
    expect(s.getAccessToken()).toBe(null);
  });

  it('set/get/clear round-trip', () => {
    const s = createSessionStore();
    s.setAccessToken('a');
    expect(s.getAccessToken()).toBe('a');
    s.clear();
    expect(s.getAccessToken()).toBe(null);
  });

  it('notifies subscribers on set and clear', () => {
    const s = createSessionStore();
    const events: string[] = [];
    const unsub = s.subscribe(() => events.push('x'));
    s.setAccessToken('a');
    s.setAccessToken('b');
    s.clear();
    expect(events).toEqual(['x', 'x', 'x']);
    unsub();
    s.setAccessToken('c');
    expect(events).toEqual(['x', 'x', 'x']);
  });

  it('clear on an already-empty store does not notify', () => {
    const s = createSessionStore();
    const events: string[] = [];
    s.subscribe(() => events.push('x'));
    s.clear();
    expect(events).toEqual([]);
  });
});
