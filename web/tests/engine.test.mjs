import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { AutomationEngine } from '../js/engine.js';
import { GitHubClient } from '../js/github.js';
import { OllamaClient } from '../js/ollama.js';
import { SurgicalAgent } from '../js/agent.js';
import { Store, MemoryStorage } from '../js/store.js';
import { defaultConfig } from '../js/models.js';

// End-to-end pipeline tests: fake GitHub and Ollama fetch routers drive the REAL engine,
// REAL clients, and REAL agent through the complete issue → draft workflow.

let flags;
let recorded; // [{method, path}]
let bodies;   // "METHOD path" -> parsed body
let ollamaCalls;
let store;

const readmeB64 = Buffer.from('# Hello\nworld\n', 'utf-8').toString('base64');

function issueJson(state) {
  return {
    number: 7,
    title: 'Fix the docs',
    body: 'Please change world to universe in README',
    html_url: 'http://gh/up/repo/issues/7',
    assignees: [],
    labels: flags.issueLabel ? [{ name: flags.issueLabel }] : [],
    state,
    locked: false,
  };
}

function ghRoute(method, path) {
  const json = (body, status = 200, headers = {}) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });

  if (method === 'GET' && path === '/user') return json({ login: 'tester' });

  if (method === 'GET' && path === '/repos/up/repo') {
    if (flags.rateLimitRepoMeta) {
      return json({ message: 'API rate limit exceeded' }, 403, { 'x-ratelimit-remaining': '0' });
    }
    return json({ full_name: 'up/repo', private: false, default_branch: 'main', fork: false });
  }

  if (method === 'GET' && path.startsWith('/repos/up/repo/issues/7')) return json(issueJson(flags.recheckState));
  if (method === 'GET' && path.startsWith('/repos/up/repo/issues')) return json([issueJson('open')]);

  if (method === 'GET' && path === '/repos/tester/repo') {
    return json({
      full_name: 'tester/repo', private: false, default_branch: 'main',
      fork: true, parent: { full_name: 'up/repo' },
    });
  }

  if (method === 'POST' && path === '/repos/tester/repo/merge-upstream') return json({});
  if (method === 'GET' && path === '/repos/up/repo/git/ref/heads/main') return json({ object: { sha: 'AAA' } });
  if (method === 'GET' && path === '/repos/tester/repo/git/ref/heads/main') {
    return json({ object: { sha: flags.forkHeadSha } });
  }
  if (method === 'GET' && path.startsWith('/repos/tester/repo/git/trees/AAA')) {
    return json({ tree: [{ path: 'README.md', type: 'blob' }], truncated: false });
  }
  if (method === 'GET' && path.startsWith('/repos/tester/repo/contents/README.md')) {
    return json({ content: readmeB64, encoding: 'base64' });
  }
  if (method === 'POST' && path === '/repos/tester/repo/git/refs') return json({}, 201);
  if (method === 'GET' && path === '/repos/tester/repo/git/commits/AAA') return json({ tree: { sha: 'TTT' } });
  if (method === 'POST' && path === '/repos/tester/repo/git/blobs') return json({ sha: 'BLOB1' });
  if (method === 'POST' && path === '/repos/tester/repo/git/trees') return json({ sha: 'TREE2' });
  if (method === 'POST' && path === '/repos/tester/repo/git/commits') return json({ sha: 'COMMIT1' });
  if (method === 'PATCH' && path.startsWith('/repos/tester/repo/git/refs/heads/surgeon')) return json({});

  if (method === 'GET' && path.startsWith('/repos/tester/repo/branches')) {
    // Includes the branch a happy-path draft would create, so the janitor's
    // "spare live drafts" behaviour is actually exercised.
    return json([
      { name: 'main' },
      { name: 'surgeon/issue-99-orphan' },
      { name: 'surgeon/issue-7-fix-the-docs' },
    ]);
  }
  if (method === 'DELETE' && path.startsWith('/repos/tester/repo/git/refs/heads/surgeon')) {
    return new Response(null, { status: 204 });
  }

  return new Response(JSON.stringify({ message: `unrouted: ${method} ${path}` }), { status: 404 });
}

const planContent = JSON.stringify({
  abstain: false,
  reason: '',
  commit_message: 'Fix docs',
  edits: [{ path: 'README.md', is_new: false, old_string: 'world', new_string: 'universe' }],
});

function makeEngine() {
  const ghFetch = async (url, init = {}) => {
    const u = new URL(url);
    const method = init.method ?? 'GET';
    const path = u.pathname + u.search;
    recorded.push({ method, path });
    if (init.body) bodies[`${method} ${u.pathname}`] = JSON.parse(init.body);
    return ghRoute(method, path);
  };

  const ollamaFetch = async (url, init = {}) => {
    if (String(url).endsWith('/api/chat')) {
      ollamaCalls++;
      const body = init.body ?? '';
      let content;
      if (body.includes('code navigator')) {
        content = '{"files":["README.md"],"reason":"r"}';
      } else if (body.includes('strict reviewer')) {
        content = flags.criticApprove
          ? '{"approve":true,"reason":"ok"}'
          : '{"approve":false,"reason":"not minimal"}';
      } else {
        content = planContent;
      }
      return new Response(JSON.stringify({ message: { role: 'assistant', content }, done: true }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      });
    }
    return new Response('{}', { status: 404 });
  };

  const cfg = {
    ...defaultConfig(),
    githubToken: 't',
    ollamaBaseUrl: 'http://ollama.fake',
    ollamaModel: 'test-model',
    repos: [{ owner: 'up', name: 'repo', forkOwner: null, forkName: null }],
  };
  return new AutomationEngine(cfg, store, {
    github: new GitHubClient('t', { baseUrl: 'https://gh.fake', fetchFn: ghFetch }),
    agent: new SurgicalAgent(new OllamaClient(cfg.ollamaBaseUrl, cfg.ollamaModel, '', { fetchFn: ollamaFetch })),
  });
}

const paths = (method) => recorded.filter((r) => r.method === method).map((r) => r.path);

beforeEach(() => {
  flags = {
    forkHeadSha: 'AAA',
    recheckState: 'open',
    criticApprove: true,
    issueLabel: null,
    rateLimitRepoMeta: false,
  };
  recorded = [];
  bodies = {};
  ollamaCalls = 0;
  store = new Store(new MemoryStorage());
});

// ---- the full happy path ----

test('happy path produces a correct draft', async () => {
  const result = await makeEngine().runOnce();

  assert.equal(result.kind, 'drafted');
  const draft = result.draft;

  assert.equal(draft.headBranch, 'surgeon/issue-7-fix-the-docs');
  assert.equal(draft.forkFullName, 'tester/repo');
  assert.equal(draft.repoFullName, 'up/repo');
  assert.equal(draft.commitSha, 'COMMIT1');
  assert.equal(draft.commitMessage, 'Fix docs');
  assert.ok(draft.compareUrl.includes('up/repo/compare/main...tester:repo:surgeon/issue-7'));

  // The blob really carries the surgically-edited content.
  const blobBody = bodies['POST /repos/tester/repo/git/blobs'];
  assert.ok(blobBody.content.includes('universe'));
  assert.ok(!blobBody.content.includes('world'));

  // The commit is authored/committed as the configured identity, on the right parent.
  const commitBody = bodies['POST /repos/tester/repo/git/commits'];
  assert.equal(commitBody.author.name, 'Ilum');
  assert.equal(commitBody.author.email, 'Ilum@linux.org');
  assert.equal(commitBody.committer.email, 'Ilum@linux.org');
  assert.deepEqual(commitBody.parents, ['AAA']);

  // Exactly three model calls: locate, plan, critic.
  assert.equal(ollamaCalls, 3);

  assert.equal(store.isProcessed('up/repo', 7), true);
  assert.equal(store.getDrafts().length, 1);
});

// ---- safety gates ----

test('discards work when the issue closes mid-run', async () => {
  flags.recheckState = 'closed';
  const result = await makeEngine().runOnce();

  assert.equal(result.kind, 'skipped');
  assert.equal(result.reason, 'Issue no longer workable');
  assert.ok(paths('POST').every((p) => !p.endsWith('/git/refs')));
  assert.equal(paths('PATCH').length, 0);
  assert.equal(store.isProcessed('up/repo', 7), true);
  assert.equal(store.getDrafts().length, 0);
});

test('refuses a diverged fork before spending any tokens', async () => {
  flags.forkHeadSha = 'ZZZ';
  const result = await makeEngine().runOnce();

  assert.equal(result.kind, 'skipped');
  assert.equal(result.reason, 'Fork diverged from upstream');
  assert.ok(paths('GET').every((p) => !p.includes('/git/trees/')));
  assert.equal(ollamaCalls, 0);
});

test('critic rejection is a retryable skip with no writes', async () => {
  flags.criticApprove = false;
  const result = await makeEngine().runOnce();

  assert.equal(result.kind, 'skipped');
  assert.ok(result.reason.includes('Critic rejected'));
  assert.equal(store.isSkipped('up/repo', 7), true);
  assert.equal(store.isProcessed('up/repo', 7), false);
  assert.ok(paths('POST').every((p) => !p.endsWith('/git/refs')));
});

test('skips issues with disqualifying labels', async () => {
  flags.issueLabel = 'wontfix';
  const result = await makeEngine().runOnce();
  assert.equal(result.kind, 'noWork');
  assert.equal(ollamaCalls, 0);
});

test('rate limit surfaces as a clean failure', async () => {
  flags.rateLimitRepoMeta = true;
  const result = await makeEngine().runOnce();
  assert.equal(result.kind, 'failed');
  assert.ok(result.reason.includes('Rate limited'));
});

// ---- manual pick ----

test('manual pick drafts a specific issue', async () => {
  const result = await makeEngine().runOnIssue({ owner: 'up', name: 'repo' }, 7);
  assert.equal(result.kind, 'drafted');
});

test('manual pick bypasses skip memory', async () => {
  store.markSkipped('up/repo', 7);
  const result = await makeEngine().runOnIssue({ owner: 'up', name: 'repo' }, 7);
  assert.equal(result.kind, 'drafted');
});

test('manual pick refuses an already-processed issue', async () => {
  store.markProcessed('up/repo', 7);
  const result = await makeEngine().runOnIssue({ owner: 'up', name: 'repo' }, 7);
  assert.equal(result.kind, 'skipped');
  assert.ok(result.reason.includes('already'));
});

test('skipped issues are not picked automatically', async () => {
  store.markSkipped('up/repo', 7);
  const result = await makeEngine().runOnce();
  assert.equal(result.kind, 'noWork');
});

// ---- fork mapping ----

test('explicit fork mapping is verified and used', async () => {
  const engine = makeEngine();
  // Point the single repo at an explicit fork (same location the router serves).
  engine.config.repos[0].forkOwner = 'tester';
  engine.config.repos[0].forkName = 'repo';
  const result = await engine.runOnce();
  assert.equal(result.kind, 'drafted');
  // ensureFork's auto-create path must NOT have been used (no POST /forks).
  assert.ok(paths('POST').every((p) => !p.endsWith('/forks')));
});

// ---- workable listing ----

test('listWorkableIssues returns open unclaimed issues and hides seen ones', async () => {
  const engine = makeEngine();
  const first = await engine.listWorkableIssues({ owner: 'up', name: 'repo' });
  assert.equal(first.length, 1);
  assert.equal(first[0].number, 7);

  store.markSkipped('up/repo', 7);
  const second = await engine.listWorkableIssues({ owner: 'up', name: 'repo' });
  assert.equal(second.length, 0);
});

// ---- branch janitor ----

test('cleanup deletes orphan surgeon branches but never main', async () => {
  const deleted = await makeEngine().cleanupOrphanBranches();
  assert.equal(deleted, 2); // both surgeon/* branches are orphans with no drafts
  const deletes = paths('DELETE');
  assert.equal(deletes.length, 2);
  assert.ok(deletes.every((p) => p.includes('surgeon')));
});

test('cleanup spares branches of live drafts', async () => {
  const drafted = await makeEngine().runOnce();
  assert.equal(drafted.kind, 'drafted');
  assert.equal(drafted.draft.headBranch, 'surgeon/issue-7-fix-the-docs');

  recorded = [];
  const deleted = await makeEngine().cleanupOrphanBranches();
  assert.equal(deleted, 1);
  const deletes = paths('DELETE');
  assert.ok(deletes.every((p) => p.includes('issue-99-orphan')));
  assert.ok(deletes.every((p) => !p.includes('issue-7')));
});
