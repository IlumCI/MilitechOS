import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Store, MemoryStorage } from '../js/store.js';
import { DraftStatus } from '../js/models.js';

function draft(overrides = {}) {
  return {
    id: crypto.randomUUID(),
    repoFullName: 'up/repo',
    forkFullName: 'me/repo',
    baseBranch: 'main',
    headBranch: 'surgeon/issue-1-x',
    issueNumber: 1,
    issueTitle: 't',
    issueUrl: 'u',
    isPrivateRepo: false,
    status: DraftStatus.DRAFT,
    createdAt: Date.now(),
    ...overrides,
  };
}

test('processed memory persists across instances', () => {
  const storage = new MemoryStorage();
  const store = new Store(storage);
  assert.equal(store.isProcessed('up/repo', 7), false);
  store.markProcessed('up/repo', 7);
  assert.equal(store.isProcessed('up/repo', 7), true);
  assert.equal(new Store(storage).isProcessed('up/repo', 7), true);
});

test('skipped memory is separate and clearable', () => {
  const storage = new MemoryStorage();
  const store = new Store(storage);
  store.markSkipped('up/repo', 9);
  assert.equal(store.isSkipped('up/repo', 9), true);
  assert.equal(store.isProcessed('up/repo', 9), false);
  assert.equal(store.skippedCount(), 1);
  assert.equal(new Store(storage).isSkipped('up/repo', 9), true);

  store.clearSkipped();
  assert.equal(store.isSkipped('up/repo', 9), false);
  assert.equal(store.skippedCount(), 0);
  assert.equal(new Store(storage).isSkipped('up/repo', 9), false);
});

test('draft upsert and status update persist', () => {
  const storage = new MemoryStorage();
  const store = new Store(storage);
  const d = draft();
  store.upsertDraft(d);
  assert.equal(store.getDrafts().length, 1);

  store.updateDraftStatus(d.id, DraftStatus.SUBMITTED);
  assert.equal(store.getDrafts()[0].status, DraftStatus.SUBMITTED);
  assert.equal(new Store(storage).getDrafts()[0].status, DraftStatus.SUBMITTED);
});

test('draftsToday excludes discarded, failed, and yesterday', () => {
  const store = new Store(new MemoryStorage());
  store.upsertDraft(draft({ status: DraftStatus.DRAFT }));
  store.upsertDraft(draft({ status: DraftStatus.SUBMITTED }));
  store.upsertDraft(draft({ status: DraftStatus.DISCARDED }));
  store.upsertDraft(draft({ status: DraftStatus.FAILED }));
  store.upsertDraft(draft({ createdAt: Date.now() - 48 * 3600 * 1000 }));
  assert.equal(store.draftsToday(), 2);
});

test('config round-trips and merges defaults', () => {
  const storage = new MemoryStorage();
  const store = new Store(storage);
  const cfg = store.getConfig();
  assert.equal(cfg.authorName, 'Ilum');
  assert.equal(cfg.authorEmail, 'Ilum@linux.org');
  assert.equal(cfg.ollamaModel, 'deepseek-v4-flash:cloud');
  assert.equal(cfg.repos.length, 7);

  store.saveConfig({ ...cfg, githubToken: 'tok' });
  assert.equal(new Store(storage).getConfig().githubToken, 'tok');
});

test('logs are capped', () => {
  const store = new Store(new MemoryStorage());
  for (let i = 0; i < 350; i++) store.log('INFO', `m${i}`);
  assert.equal(store.getLogs().length, 300);
  assert.equal(store.getLogs()[0].message, 'm349');
});
