import { describe, it, expect } from 'vitest';
import { consoleKeys, openKeys } from './query-keys';

describe('consoleKeys', () => {
  it('knowledge.list uses [console, knowledge, list, tenantId, pageToken]', () => {
    expect(consoleKeys.knowledge.list('2001')).toEqual([
      'console',
      'knowledge',
      'list',
      '2001',
      '',
    ]);
    expect(consoleKeys.knowledge.list('2001', 'page2')).toEqual([
      'console',
      'knowledge',
      'list',
      '2001',
      'page2',
    ]);
  });

  it('knowledge.detail uses [console, knowledge, detail, tenant, kb]', () => {
    expect(consoleKeys.knowledge.detail('2001', '3001')).toEqual([
      'console',
      'knowledge',
      'detail',
      '2001',
      '3001',
    ]);
  });

  it('apiKeys.list includes pageToken last for safe invalidation', () => {
    expect(consoleKeys.apiKeys.list('2001', '3001', 'p2')).toEqual([
      'console',
      'api-keys',
      'list',
      '2001',
      '3001',
      'p2',
    ]);
  });

  it('all() prefixes enable prefix invalidation', () => {
    const knowledgeAll = consoleKeys.knowledge.all();
    expect(consoleKeys.knowledge.list('2001').slice(0, knowledgeAll.length)).toEqual(knowledgeAll);
    expect(consoleKeys.knowledge.detail('2001', '3001').slice(0, knowledgeAll.length)).toEqual(
      knowledgeAll,
    );
  });
});

describe('openKeys', () => {
  it('query keys are namespaced under open', () => {
    expect(openKeys.query('sess-1')).toEqual(['open', 'query', 'sess-1']);
  });
});
