// The full workflow: pick an issue → prepare the fork branch → surgical agent →
// validate + self-review → commit as the configured identity → record a draft awaiting
// the human PR gate (the one step that stays manual, by design).

import { GitHubClient, ApiError, RateLimitError } from './github.js';
import { OllamaClient } from './ollama.js';
import { SurgicalAgent } from './agent.js';
import { validateEdit } from './validators.js';
import { SKIP_LABELS, normalizeLabel, rankPaths, slugify } from './utils.js';
import { configIsReady, repoFullName, DraftStatus } from './models.js';

export const MAX_ISSUE_BODY_CHARS = 6000;
export const MAX_CANDIDATE_PATHS = 300;
export const MAX_ATTEMPTS_PER_RUN = 3;
export const BRANCH_PREFIX = 'surgeon';

// Result kinds: {kind:'drafted', draft} | {kind:'skipped', reason} | {kind:'noWork'} | {kind:'failed', reason}

export class AutomationEngine {
  constructor(config, store, { github, agent } = {}) {
    this.config = config;
    this.store = store;
    this.github = github ?? new GitHubClient(config.githubToken);
    this.agent = agent ?? new SurgicalAgent(
      new OllamaClient(config.ollamaBaseUrl, config.ollamaModel, config.ollamaApiKey),
    );
  }

  async runOnce() {
    if (!configIsReady(this.config)) return { kind: 'failed', reason: 'Configuration incomplete' };
    const myLogin = await this.#resolveLogin();
    if (!myLogin) return { kind: 'failed', reason: 'GitHub auth failed' };

    // Try repos in random order. A skip burns that issue but shouldn't end the run.
    let attempts = 0;
    let lastSkip = null;
    const repos = [...this.config.repos].sort(() => Math.random() - 0.5);
    for (const repo of repos) {
      if (attempts >= MAX_ATTEMPTS_PER_RUN) break;
      const picked = await this.#pickIssue(repo);
      if (!picked) continue;
      attempts++;
      this.store.log('INFO', `Selected ${repoFullName(repo)}#${picked.number}: ${picked.title}`);
      const result = await this.#guardedProcess(repo, picked, myLogin);
      if (result.kind === 'skipped') { lastSkip = result; continue; }
      return result;
    }
    if (lastSkip) return lastSkip;
    this.store.log('WARN', 'No unprocessed issues found across configured repos');
    return { kind: 'noWork' };
  }

  /**
   * Draft a specific issue chosen by the user. Bypasses the retryable-skip memory
   * (an explicit pick means "try it anyway") but never re-drafts a processed issue.
   */
  async runOnIssue(repo, issueNumber) {
    if (!configIsReady(this.config)) return { kind: 'failed', reason: 'Configuration incomplete' };
    const myLogin = await this.#resolveLogin();
    if (!myLogin) return { kind: 'failed', reason: 'GitHub auth failed' };

    const full = repoFullName(repo);
    if (this.store.isProcessed(full, issueNumber)) {
      return { kind: 'skipped', reason: 'Issue was already drafted or handled' };
    }
    let issue;
    try {
      issue = await this.github.getIssue(repo.owner, repo.name, issueNumber);
    } catch (e) {
      return { kind: 'failed', reason: `Could not fetch issue: ${e.message}` };
    }
    if (issue.pull_request) return { kind: 'skipped', reason: 'That number is a pull request' };
    if (issue.state !== 'open' || (issue.assignees ?? []).length > 0 || issue.locked) {
      return { kind: 'skipped', reason: 'Issue is not open/unassigned/unlocked' };
    }
    this.store.log('INFO', `Manually selected ${full}#${issueNumber}: ${issue.title}`);
    return this.#guardedProcess(repo, issue, myLogin);
  }

  /** Open, unseen, unassigned, unlocked, label-eligible issues for the browse tab. */
  async listWorkableIssues(repo) {
    const issues = await this.github.listOpenIssues(repo.owner, repo.name).catch(() => []);
    return issues.filter((i) => this.#isWorkable(repo, i));
  }

  /** Delete surgeon-prefixed branches on the forks that no live draft references. */
  async cleanupOrphanBranches() {
    const myLogin = await this.#resolveLogin();
    if (!myLogin) return 0;
    const active = new Set(
      this.store.getDrafts()
        .filter((d) => d.status === DraftStatus.DRAFT || d.status === DraftStatus.SUBMITTED)
        .map((d) => `${d.forkFullName}:${d.headBranch}`),
    );
    let deleted = 0;
    for (const repo of this.config.repos) {
      const forkOwner = repo.forkOwner?.trim() || myLogin;
      const forkName = repo.forkName?.trim() || repo.name;
      const branches = await this.github.listBranches(forkOwner, forkName).catch(() => []);
      for (const branch of branches) {
        if (!branch.startsWith(`${BRANCH_PREFIX}/`)) continue;
        if (active.has(`${forkOwner}/${forkName}:${branch}`)) continue;
        const ok = await this.github.deleteRef(forkOwner, forkName, branch).then(() => true).catch(() => false);
        if (ok) deleted++;
      }
    }
    this.store.log('INFO', `Branch cleanup: deleted ${deleted} orphan branch(es)`);
    return deleted;
  }

  // ---- internals ----

  async #resolveLogin() {
    try {
      const me = await this.github.getAuthenticatedUser();
      return me.login || null;
    } catch (e) {
      this.store.log('ERROR', `GitHub auth failed: ${e.message}`);
      return null;
    }
  }

  async #guardedProcess(repo, issue, myLogin) {
    const full = repoFullName(repo);
    try {
      return await this.#processIssue(repo, issue, myLogin);
    } catch (e) {
      if (e instanceof RateLimitError) {
        this.store.log('ERROR',
          `GitHub rate limit hit on ${full}#${issue.number}; backing off` +
          (e.retryAfterSeconds != null ? ` (~${e.retryAfterSeconds}s)` : ''));
        return { kind: 'failed', reason: 'Rate limited by GitHub — will retry later' };
      }
      if (e instanceof ApiError) {
        this.store.log('ERROR', `API error on ${full}#${issue.number}: ${e.message} ${e.body.slice(0, 300)}`);
        return { kind: 'failed', reason: e.message };
      }
      this.store.log('ERROR', `Error on ${full}#${issue.number}: ${e.message}`);
      return { kind: 'failed', reason: e.message || 'unknown error' };
    }
  }

  #isWorkable(repo, issue) {
    const full = repoFullName(repo);
    return issue.state === 'open' &&
      !this.store.isProcessed(full, issue.number) &&
      !this.store.isSkipped(full, issue.number) &&
      (issue.assignees ?? []).length === 0 &&
      !issue.locked &&
      !(issue.labels ?? []).some((l) => SKIP_LABELS.has(normalizeLabel(l.name ?? '')));
  }

  async #pickIssue(repo) {
    let issues;
    try {
      issues = await this.github.listOpenIssues(repo.owner, repo.name);
    } catch (e) {
      this.store.log('WARN', `Could not list issues for ${repoFullName(repo)}: ${e.message}`);
      return null;
    }
    const workable = issues.filter((i) => this.#isWorkable(repo, i));
    if (workable.length === 0) return null;
    return workable[Math.floor(Math.random() * workable.length)];
  }

  /** Re-fetch the issue and confirm it is STILL open/unassigned/unlocked before committing. */
  async #issueStillWorkable(repo, number) {
    const fresh = await this.github.getIssue(repo.owner, repo.name, number).catch(() => null);
    if (!fresh) return false;
    return fresh.state === 'open' && (fresh.assignees ?? []).length === 0 && !fresh.locked;
  }

  /** Explicit fork mapping wins (verified, never auto-created); else <myLogin>/<name>, created if needed. */
  async #resolveFork(repo, myLogin, baseBranch) {
    const explicitOwner = repo.forkOwner?.trim() || null;
    const explicitName = repo.forkName?.trim() || null;
    if (!explicitOwner && !explicitName) {
      await this.github.ensureFork(repo.owner, repo.name, myLogin, baseBranch);
      return [myLogin, repo.name];
    }
    const forkOwner = explicitOwner ?? myLogin;
    const forkName = explicitName ?? repo.name;
    await this.github.verifyFork(forkOwner, forkName, repo.owner, repo.name);
    return [forkOwner, forkName];
  }

  #skipRetryable(repo, issue, reason) {
    this.store.markSkipped(repoFullName(repo), issue.number);
    this.store.log('WARN', `Skipped ${repoFullName(repo)}#${issue.number}: ${reason}`);
    return { kind: 'skipped', reason };
  }

  async #processIssue(repo, issue, myLogin) {
    const full = repoFullName(repo);
    const meta = await this.github.getRepo(repo.owner, repo.name);
    const danger = Boolean(meta.private);
    const baseBranch = meta.default_branch || 'main';
    const issueBody = (issue.body ?? '').slice(0, MAX_ISSUE_BODY_CHARS);

    const [forkOwner, forkName] = await this.#resolveFork(repo, myLogin, baseBranch);
    await this.github.syncForkBranch(forkOwner, forkName, baseBranch);

    // Divergence guard: only ever build on a fork that exactly matches upstream.
    // A diverged fork is how merge conflicts happen — refuse rather than risk it.
    const upstreamSha = await this.github.getBranchHeadSha(repo.owner, repo.name, baseBranch);
    const baseSha = await this.github.getBranchHeadSha(forkOwner, forkName, baseBranch);
    if (baseSha !== upstreamSha) {
      this.store.log('ERROR',
        `Fork ${forkOwner}/${forkName}:${baseBranch} has diverged from upstream — ` +
        'sync or recreate the fork manually. Skipping this repo.');
      return { kind: 'skipped', reason: 'Fork diverged from upstream' };
    }

    // Locate the files to touch, most issue-relevant paths first.
    const allPaths = await this.github.listSourcePaths(forkOwner, forkName, baseSha);
    const paths = rankPaths(allPaths, issue.title, issueBody, MAX_CANDIDATE_PATHS);
    const targetPaths = await this.agent.locate(issue.title, issueBody, paths);
    if (targetPaths.length === 0) {
      return this.#skipRetryable(repo, issue, 'No relevant files located');
    }

    // Fetch current contents.
    const files = {};
    for (const path of targetPaths) {
      const content = await this.github.getFileContent(forkOwner, forkName, path, baseSha).catch(() => '');
      if (content) files[path] = content;
    }
    if (Object.keys(files).length === 0) {
      return this.#skipRetryable(repo, issue, 'Could not read located files');
    }

    // Plan the surgical change.
    const outcome = await this.agent.plan(issue.title, issueBody, files, danger);
    if (outcome.kind === 'abstained') {
      return this.#skipRetryable(repo, issue, `Abstained: ${outcome.reason}`);
    }
    if (outcome.kind === 'rejected') {
      return this.#skipRetryable(repo, issue, `Rejected: ${outcome.reason}`);
    }

    // Structural sanity of every final file (cheap, local, before any further token spend).
    for (const [path, content] of Object.entries(outcome.finalContents)) {
      const error = validateEdit(path, content);
      if (error) {
        return this.#skipRetryable(repo, issue, `Validation failed in ${path}: ${error}`);
      }
    }

    // Independent self-review: a second model call must actively approve the change.
    const { approved, reason: criticReason } = await this.agent.critique(issue.title, issueBody, outcome, danger);
    if (!approved) {
      return this.#skipRetryable(repo, issue, `Critic rejected: ${criticReason}`);
    }

    // "New" files must be new to the whole repo, not just the fetched set.
    for (const edit of outcome.edits.filter((e) => e.isNew)) {
      const existing = await this.github.getFileContent(forkOwner, forkName, edit.path, baseSha).catch(() => '');
      if (existing) {
        return this.#skipRetryable(repo, issue, `Agent marked existing file '${edit.path}' as new`);
      }
    }

    // Final freshness gate: the issue may have died while the model was working.
    if (!(await this.#issueStillWorkable(repo, issue.number))) {
      this.store.markProcessed(full, issue.number);
      this.store.log('WARN',
        `Discarded work on ${full}#${issue.number}: issue was closed/assigned/locked mid-run`);
      return { kind: 'skipped', reason: 'Issue no longer workable' };
    }

    return this.#commitDraft(repo, issue, forkOwner, forkName, baseBranch, baseSha, danger, outcome);
  }

  async #commitDraft(repo, issue, forkOwner, forkName, baseBranch, baseSha, danger, outcome) {
    const full = repoFullName(repo);
    const headBranch = await this.#createUniqueBranch(forkOwner, forkName, issue.number, issue.title, baseSha);

    const baseTreeSha = await this.github.getCommitTreeSha(forkOwner, forkName, baseSha);
    const entries = [];
    for (const [path, content] of Object.entries(outcome.finalContents)) {
      entries.push({ path, blobSha: await this.github.createBlob(forkOwner, forkName, content) });
    }
    const treeSha = await this.github.createTree(forkOwner, forkName, baseTreeSha, entries);
    const commitSha = await this.github.createCommit(forkOwner, forkName, {
      message: outcome.commitMessage,
      treeSha,
      parentSha: baseSha,
      authorName: this.config.authorName,
      authorEmail: this.config.authorEmail,
      isoDate: new Date().toISOString(),
    });
    await this.github.updateRef(forkOwner, forkName, headBranch, commitSha);

    const compareUrl = `https://github.com/${repo.owner}/${repo.name}/compare/` +
      `${baseBranch}...${forkOwner}:${forkName}:${headBranch}?expand=1`;

    const draft = {
      id: crypto.randomUUID(),
      repoFullName: full,
      forkFullName: `${forkOwner}/${forkName}`,
      baseBranch,
      headBranch,
      issueNumber: issue.number,
      issueTitle: issue.title,
      issueUrl: issue.html_url ?? '',
      isPrivateRepo: danger,
      commitSha,
      commitMessage: outcome.commitMessage,
      compareUrl,
      edits: outcome.edits,
      agentNotes: outcome.notes,
      status: DraftStatus.DRAFT,
      createdAt: Date.now(),
      isStale: false,
    };
    this.store.upsertDraft(draft);
    this.store.markProcessed(full, issue.number);
    this.store.log('SUCCESS',
      `Drafted ${full}#${issue.number} → branch ${headBranch} (${outcome.edits.length} file(s))` +
      (danger ? ' [PRIVATE — review carefully]' : ''));
    return { kind: 'drafted', draft };
  }

  async #createUniqueBranch(forkOwner, name, issueNumber, title, baseSha) {
    const candidate = `${BRANCH_PREFIX}/issue-${issueNumber}-${slugify(title)}`;
    try {
      await this.github.createBranch(forkOwner, name, candidate, baseSha);
      return candidate;
    } catch (e) {
      // 422 == ref already exists; disambiguate. Anything else must propagate.
      if (!(e instanceof ApiError) || e.code !== 422) throw e;
      const suffixed = `${candidate}-${Date.now() % 100000}`;
      await this.github.createBranch(forkOwner, name, suffixed, baseSha);
      return suffixed;
    }
  }
}
