// Client for the subset of the GitHub REST + Git Data API the pipeline needs.
// fetchFn is injectable for tests; baseUrl for pointing at a fake server.

import { b64DecodeUtf8 } from './utils.js';

export class ApiError extends Error {
  constructor(code, body, message) {
    super(message);
    this.code = code;
    this.body = body;
  }
}

export class RateLimitError extends ApiError {
  constructor(code, body, retryAfterSeconds, message) {
    super(code, body, message);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class GitHubClient {
  constructor(token, { baseUrl = 'https://api.github.com', fetchFn = globalThis.fetch.bind(globalThis) } = {}) {
    this.token = token;
    this.base = baseUrl.replace(/\/+$/, '');
    this.fetchFn = fetchFn;
  }

  async #exec(method, path, body) {
    const resp = await this.fetchFn(`${this.base}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${this.token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
    const text = await resp.text();
    if (!resp.ok) {
      // GitHub signals rate limiting via 429, or 403 with a drained quota header.
      const remaining = resp.headers.get('x-ratelimit-remaining');
      const isRateLimit = resp.status === 429 ||
        (resp.status === 403 && (remaining === '0' || /rate limit/i.test(text)));
      if (isRateLimit) {
        const retryAfterHeader = resp.headers.get('retry-after');
        let retryAfter = retryAfterHeader ? Number(retryAfterHeader) : null;
        if (retryAfter === null) {
          const reset = resp.headers.get('x-ratelimit-reset');
          if (reset) retryAfter = Math.max(0, Number(reset) - Math.floor(Date.now() / 1000));
        }
        throw new RateLimitError(
          resp.status, text, retryAfter,
          `GitHub rate limit hit (${resp.status}); retry after ${retryAfter ?? 'unknown'}s`,
        );
      }
      throw new ApiError(resp.status, text, `GitHub ${method} ${path.split('?')[0]} → ${resp.status}`);
    }
    return text ? JSON.parse(text) : {};
  }

  #get(path) { return this.#exec('GET', path); }
  #post(path, body) { return this.#exec('POST', path, body); }
  #patch(path, body) { return this.#exec('PATCH', path, body); }
  #delete(path) { return this.#exec('DELETE', path); }

  // ---- Public API ----

  getAuthenticatedUser() {
    return this.#get('/user');
  }

  getRepo(owner, name) {
    return this.#get(`/repos/${owner}/${name}`);
  }

  async listOpenIssues(owner, name, perPage = 50) {
    const issues = await this.#get(
      `/repos/${owner}/${name}/issues?state=open&per_page=${perPage}&sort=updated&direction=desc`,
    );
    return issues.filter((i) => !i.pull_request);
  }

  getIssue(owner, name, number) {
    return this.#get(`/repos/${owner}/${name}/issues/${number}`);
  }

  /** Ensure the authenticated user has a usable fork; verifies any same-named repo really is one. */
  async ensureFork(owner, name, myLogin, baseBranch) {
    const existing = await this.getRepo(myLogin, name).catch(() => null);
    if (existing) {
      this.#requireIsForkOf(existing, owner, name, myLogin, name);
      return myLogin;
    }
    await this.#post(`/repos/${owner}/${name}/forks`, {});
    // Poll until the fork's metadata AND git data are queryable.
    for (let i = 0; i < 20; i++) {
      await sleep(2000);
      const repo = await this.getRepo(myLogin, name).catch(() => null);
      if (repo) {
        this.#requireIsForkOf(repo, owner, name, myLogin, name);
        const ready = await this.getBranchHeadSha(myLogin, name, baseBranch).then(() => true).catch(() => false);
        if (ready) return myLogin;
      }
    }
    throw new ApiError(504, '', `Fork of ${owner}/${name} did not become available in time`);
  }

  /** Verify a user-specified fork: must exist, be a fork, and have exactly this upstream as parent. */
  async verifyFork(forkOwner, forkName, upstreamOwner, upstreamName) {
    const repo = await this.getRepo(forkOwner, forkName).catch(() => {
      throw new ApiError(404, '', `Configured fork ${forkOwner}/${forkName} is not accessible with this token`);
    });
    this.#requireIsForkOf(repo, upstreamOwner, upstreamName, forkOwner, forkName);
  }

  #requireIsForkOf(repo, upstreamOwner, upstreamName, forkOwner, forkName) {
    const expected = `${upstreamOwner}/${upstreamName}`.toLowerCase();
    const actualParent = (repo.parent?.full_name ?? '').toLowerCase();
    if (!repo.fork || actualParent !== expected) {
      throw new ApiError(
        409, '',
        `Repo ${forkOwner}/${forkName} exists but is not a fork of ${upstreamOwner}/${upstreamName} ` +
        `(fork=${Boolean(repo.fork)}, parent=${actualParent || 'none'}). Refusing to touch it.`,
      );
    }
  }

  /** Fast-forward the fork's branch to upstream. Best-effort. */
  async syncForkBranch(forkOwner, name, branch) {
    await this.#post(`/repos/${forkOwner}/${name}/merge-upstream`, { branch }).catch(() => {});
  }

  async getBranchHeadSha(owner, name, branch) {
    const ref = await this.#get(`/repos/${owner}/${name}/git/ref/heads/${encodeURIComponent(branch)}`);
    return ref.object.sha;
  }

  async getCommitTreeSha(owner, name, commitSha) {
    const commit = await this.#get(`/repos/${owner}/${name}/git/commits/${commitSha}`);
    return commit.tree.sha;
  }

  async createBranch(forkOwner, name, newBranch, sha) {
    await this.#post(`/repos/${forkOwner}/${name}/git/refs`, {
      ref: `refs/heads/${newBranch}`,
      sha,
    });
  }

  /** Source-file paths in the tree at `sha`, filtered to editable text files and capped. */
  async listSourcePaths(owner, name, sha, cap = 400) {
    const tree = await this.#get(`/repos/${owner}/${name}/git/trees/${sha}?recursive=1`);
    return tree.tree
      .filter((e) => e.type === 'blob')
      .map((e) => e.path)
      .filter((p) => EDITABLE_EXT.some((ext) => p.endsWith(ext)))
      .filter((p) => !IGNORED_DIRS.some((dir) => p.includes(dir)))
      .slice(0, cap);
  }

  async getFileContent(owner, name, path, ref) {
    const encPath = path.split('/').map(encodeURIComponent).join('/');
    const content = await this.#get(`/repos/${owner}/${name}/contents/${encPath}?ref=${encodeURIComponent(ref)}`);
    if (content.encoding !== 'base64' || !content.content) return '';
    return b64DecodeUtf8(content.content);
  }

  async createBlob(forkOwner, name, content) {
    const resp = await this.#post(`/repos/${forkOwner}/${name}/git/blobs`, {
      content,
      encoding: 'utf-8',
    });
    return resp.sha;
  }

  /** entries: [{path, blobSha}]. Creates a tree layered on baseTreeSha. */
  async createTree(forkOwner, name, baseTreeSha, entries) {
    const resp = await this.#post(`/repos/${forkOwner}/${name}/git/trees`, {
      base_tree: baseTreeSha,
      tree: entries.map(({ path, blobSha }) => ({
        path, mode: '100644', type: 'blob', sha: blobSha,
      })),
    });
    return resp.sha;
  }

  async createCommit(forkOwner, name, { message, treeSha, parentSha, authorName, authorEmail, isoDate }) {
    const identity = { name: authorName, email: authorEmail, date: isoDate };
    const resp = await this.#post(`/repos/${forkOwner}/${name}/git/commits`, {
      message,
      tree: treeSha,
      parents: [parentSha],
      author: identity,
      committer: identity,
    });
    return resp.sha;
  }

  async updateRef(forkOwner, name, branch, sha) {
    await this.#patch(`/repos/${forkOwner}/${name}/git/refs/heads/${encodeURIComponent(branch)}`, {
      sha,
      force: false,
    });
  }

  async deleteRef(owner, name, branch) {
    await this.#delete(`/repos/${owner}/${name}/git/refs/heads/${encodeURIComponent(branch)}`);
  }

  async listBranches(owner, name) {
    const branches = await this.#get(`/repos/${owner}/${name}/branches?per_page=100`);
    return branches.map((b) => b.name);
  }
}

const EDITABLE_EXT = [
  '.py', '.md', '.txt', '.rst', '.toml', '.cfg', '.ini', '.yaml', '.yml',
  '.json', '.ts', '.tsx', '.js', '.jsx', '.css', '.scss', '.html', '.mdx',
  '.sh', '.env.example', '.gitignore', 'Dockerfile',
];

const IGNORED_DIRS = [
  'node_modules/', 'dist/', 'build/', '.next/', 'vendor/', '__pycache__/',
  'site-packages/', '.venv/', 'migrations/',
];
