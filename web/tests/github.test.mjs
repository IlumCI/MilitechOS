import { test } from 'node:test';
import assert from 'node:assert/strict';
import { GitHubClient, ApiError, RateLimitError } from '../js/github.js';

/** Client whose fetch returns queued responses and records requests. */
function clientWithResponses(...responses) {
  const queue = [...responses];
  const requests = [];
  const fetchFn = async (url, init) => {
    requests.push({ url, method: init.method, headers: init.headers, body: init.body });
    const next = queue.shift();
    if (!next) throw new Error('no response queued');
    return next();
  };
  return { client: new GitHubClient('test-token', { baseUrl: 'https://gh.fake', fetchFn }), requests };
}

const json = (body, status = 200, headers = {}) => () =>
  new Response(typeof body === 'string' ? body : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json', ...headers },
  });

test('decodes base64 file content with line breaks', async () => {
  const text = '# Hello\nworld\n';
  const raw = Buffer.from(text, 'utf-8').toString('base64');
  const withBreak = raw.slice(0, 8) + '\n' + raw.slice(8);
  const { client } = clientWithResponses(json({ content: withBreak, encoding: 'base64' }));
  assert.equal(await client.getFileContent('o', 'r', 'README.md', 'main'), text);
});

test('listOpenIssues filters out pull requests', async () => {
  const { client } = clientWithResponses(json([
    { number: 1, title: 'real issue', state: 'open' },
    { number: 2, title: 'a PR', state: 'open', pull_request: { url: 'x' } },
  ]));
  const issues = await client.listOpenIssues('o', 'r');
  assert.equal(issues.length, 1);
  assert.equal(issues[0].number, 1);
});

test('sends bearer token and api version headers', async () => {
  const { client, requests } = clientWithResponses(json({ login: 'me' }));
  await client.getAuthenticatedUser();
  assert.equal(requests[0].headers.Authorization, 'Bearer test-token');
  assert.equal(requests[0].headers['X-GitHub-Api-Version'], '2022-11-28');
});

test('classifies 403 with drained quota as rate limit', async () => {
  const { client } = clientWithResponses(json(
    { message: 'API rate limit exceeded' }, 403, { 'x-ratelimit-remaining': '0' },
  ));
  await assert.rejects(() => client.getAuthenticatedUser(), (e) => {
    assert.ok(e instanceof RateLimitError);
    assert.equal(e.code, 403);
    return true;
  });
});

test('classifies 429 as rate limit with retry-after', async () => {
  const { client } = clientWithResponses(json(
    { message: 'too many requests' }, 429, { 'retry-after': '60' },
  ));
  await assert.rejects(() => client.getAuthenticatedUser(), (e) => {
    assert.ok(e instanceof RateLimitError);
    assert.equal(e.retryAfterSeconds, 60);
    return true;
  });
});

test('ordinary 403 is not a rate limit', async () => {
  const { client } = clientWithResponses(json(
    { message: 'Resource not accessible by integration' }, 403, { 'x-ratelimit-remaining': '4999' },
  ));
  await assert.rejects(() => client.getAuthenticatedUser(), (e) => {
    assert.ok(e instanceof ApiError);
    assert.ok(!(e instanceof RateLimitError));
    assert.equal(e.code, 403);
    return true;
  });
});

test('verifyFork accepts a proper fork', async () => {
  const { client } = clientWithResponses(json(
    { full_name: 'me/r', fork: true, parent: { full_name: 'up/r' }, default_branch: 'main' },
  ));
  await client.verifyFork('me', 'r', 'up', 'r'); // must not throw
});

test('verifyFork rejects wrong parent', async () => {
  const { client } = clientWithResponses(json(
    { full_name: 'me/r', fork: true, parent: { full_name: 'other/thing' }, default_branch: 'main' },
  ));
  await assert.rejects(() => client.verifyFork('me', 'r', 'up', 'r'), /Refusing/);
});

test('verifyFork rejects a non-fork repo', async () => {
  const { client } = clientWithResponses(json(
    { full_name: 'me/r', fork: false, default_branch: 'main' },
  ));
  await assert.rejects(() => client.verifyFork('me', 'r', 'up', 'r'), /Refusing/);
});

test('createCommit sends identity and parent', async () => {
  const { client, requests } = clientWithResponses(json({ sha: 'C1' }));
  const sha = await client.createCommit('me', 'r', {
    message: 'msg', treeSha: 'T1', parentSha: 'P1',
    authorName: 'Ilum', authorEmail: 'Ilum@linux.org', isoDate: '2026-01-01T00:00:00Z',
  });
  assert.equal(sha, 'C1');
  const body = JSON.parse(requests[0].body);
  assert.equal(body.author.name, 'Ilum');
  assert.equal(body.author.email, 'Ilum@linux.org');
  assert.equal(body.committer.email, 'Ilum@linux.org');
  assert.deepEqual(body.parents, ['P1']);
});

test('deleteRef sends DELETE to the ref path', async () => {
  const { client, requests } = clientWithResponses(() => new Response(null, { status: 204 }));
  await client.deleteRef('me', 'r', 'surgeon/issue-1-x');
  assert.equal(requests[0].method, 'DELETE');
  assert.ok(requests[0].url.includes('/repos/me/r/git/refs/heads/surgeon'));
});

test('listBranches parses names', async () => {
  const { client } = clientWithResponses(json([{ name: 'main' }, { name: 'surgeon/issue-2-y' }]));
  assert.deepEqual(await client.listBranches('me', 'r'), ['main', 'surgeon/issue-2-y']);
});
