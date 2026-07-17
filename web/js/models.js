// Data shapes and defaults. Plain objects; helpers keep behaviour identical to the Kotlin app.

export const DEFAULT_REPOS = [
  { owner: 'kyegomez', name: 'swarms', forkOwner: null, forkName: null },
  { owner: 'The-Swarm-Corporation', name: 'swarms-platform', forkOwner: null, forkName: null },
  { owner: 'The-Swarm-Corporation', name: 'swarms-website', forkOwner: null, forkName: null },
  { owner: 'The-Swarm-Corporation', name: 'swarms-api-docs', forkOwner: null, forkName: null },
  { owner: 'The-Swarm-Corporation', name: 'swarms-api', forkOwner: null, forkName: null },
  { owner: 'The-Swarm-Corporation', name: 'swarms-framework-docs', forkOwner: null, forkName: null },
  { owner: 'The-Swarm-Corporation', name: 'swarms-cloud-platform', forkOwner: null, forkName: null },
];

export function defaultConfig() {
  return {
    githubToken: '',
    // Desktop: local `ollama serve` can run cloud models and handles cloud auth itself.
    ollamaBaseUrl: 'http://localhost:11434',
    ollamaModel: 'deepseek-v4-flash:cloud',
    ollamaApiKey: '',
    authorName: 'Ilum',
    authorEmail: 'Ilum@linux.org',
    dailyMin: 5,
    dailyMax: 15,
    autoRunEnabled: false,
    repos: structuredClone(DEFAULT_REPOS),
  };
}

export function configIsReady(cfg) {
  return Boolean(
    cfg.githubToken && cfg.githubToken.trim() &&
    cfg.ollamaBaseUrl && cfg.ollamaBaseUrl.trim() &&
    cfg.ollamaModel && cfg.ollamaModel.trim() &&
    Array.isArray(cfg.repos) && cfg.repos.length > 0,
  );
}

export function repoFullName(repo) {
  return `${repo.owner}/${repo.name}`;
}

/** Human-readable fork override, or null when using the default guess. */
export function forkOverrideLabel(repo) {
  const fo = repo.forkOwner && repo.forkOwner.trim() ? repo.forkOwner.trim() : null;
  const fn = repo.forkName && repo.forkName.trim() ? repo.forkName.trim() : null;
  if (!fo && !fn) return null;
  return `${fo ?? '(you)'}/${fn ?? repo.name}`;
}

export const DraftStatus = Object.freeze({
  DRAFT: 'DRAFT',
  SUBMITTED: 'SUBMITTED',
  DISCARDED: 'DISCARDED',
  FAILED: 'FAILED',
});
