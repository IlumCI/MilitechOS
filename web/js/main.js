// UI wiring: four tabs, all state in Store (localStorage), engine invoked on demand,
// foreground scheduler for auto-run while the app is open.

import { Store } from './store.js';
import { AutomationEngine } from './engine.js';
import { GitHubClient } from './github.js';
import { OllamaClient } from './ollama.js';
import { ForegroundScheduler, dailyTarget } from './scheduler.js';
import { configIsReady, repoFullName, forkOverrideLabel, DraftStatus } from './models.js';

const store = new Store(localStorage);
const view = document.getElementById('view');
const tabTitle = document.getElementById('tab-title');

let currentTab = 'dashboard';
let busy = false;
let statusMessage = null;
let maintenanceMessage = null;
let modelList = null;      // null = not fetched; [] = fetched empty
let modelListError = null;
let browseRows = null;     // null = not loaded
let browseLoading = false;
let refreshingDrafts = false;

const engine = () => new AutomationEngine(store.getConfig(), store);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[c]));

// ---- notifications ----

function notifyDraft(draft) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
  try {
    new Notification('PR draft ready to review', {
      body: `${draft.repoFullName}#${draft.issueNumber}: ${draft.issueTitle}`,
      icon: 'icons/icon.svg',
    });
  } catch { /* notifications are best-effort */ }
}

// ---- scheduler ----

const scheduler = new ForegroundScheduler(store, {
  onDrafted: (draft) => { notifyDraft(draft); render(); },
  onStatus: (msg) => { statusMessage = msg; render(); },
});
scheduler.start();

// ---- actions ----

async function runOnce() {
  if (busy) return;
  busy = true;
  statusMessage = 'Working…';
  render();
  const cfg = store.getConfig();
  if (!configIsReady(cfg)) {
    statusMessage = 'Finish setup first (GitHub token, Ollama, repos).';
  } else {
    const result = await engine().runOnce();
    statusMessage = describeResult(result);
    if (result.kind === 'drafted') notifyDraft(result.draft);
  }
  busy = false;
  render();
}

async function draftIssue(repo, number) {
  if (busy) return;
  busy = true;
  statusMessage = `Drafting ${repoFullName(repo)}#${number}…`;
  render();
  const result = await engine().runOnIssue(repo, number);
  statusMessage = describeResult(result);
  if (result.kind === 'drafted') notifyDraft(result.draft);
  if (browseRows) {
    browseRows = browseRows.filter((r) => !(repoFullName(r.repo) === repoFullName(repo) && r.number === number));
  }
  busy = false;
  render();
}

function describeResult(result) {
  switch (result.kind) {
    case 'drafted': return `Drafted ${result.draft.repoFullName}#${result.draft.issueNumber} — review it.`;
    case 'skipped': return `Skipped: ${result.reason}`;
    case 'noWork': return 'No unprocessed issues found.';
    default: return `Failed: ${result.reason}`;
  }
}

async function loadIssues() {
  if (browseLoading) return;
  browseLoading = true;
  render();
  const cfg = store.getConfig();
  const eng = engine();
  const rows = [];
  for (const repo of cfg.repos) {
    const issues = await eng.listWorkableIssues(repo).catch(() => []);
    for (const issue of issues) {
      rows.push({ repo, number: issue.number, title: issue.title, labels: (issue.labels ?? []).map((l) => l.name) });
    }
  }
  browseRows = rows;
  browseLoading = false;
  render();
}

async function discardDraft(draft) {
  const cfg = store.getConfig();
  if (cfg.githubToken && draft.forkFullName.includes('/')) {
    const [fo, fn] = draft.forkFullName.split('/');
    await new GitHubClient(cfg.githubToken).deleteRef(fo, fn, draft.headBranch).catch(() => {});
  }
  store.updateDraftStatus(draft.id, DraftStatus.DISCARDED);
  render();
}

async function refreshDrafts() {
  if (refreshingDrafts) return;
  const cfg = store.getConfig();
  if (!cfg.githubToken) return;
  refreshingDrafts = true;
  render();
  const gh = new GitHubClient(cfg.githubToken);
  for (const draft of store.getDrafts().filter((d) => d.status === DraftStatus.DRAFT && !d.isStale)) {
    const [uo, un] = draft.repoFullName.split('/');
    const issue = await gh.getIssue(uo, un, draft.issueNumber).catch(() => null);
    if (issue && (issue.state !== 'open' || issue.locked)) {
      store.upsertDraft({ ...draft, isStale: true });
    }
  }
  refreshingDrafts = false;
  render();
}

async function cleanupBranches() {
  maintenanceMessage = 'Cleaning up branches…';
  render();
  const deleted = await engine().cleanupOrphanBranches().catch(() => 0);
  maintenanceMessage = `Deleted ${deleted} orphan branch(es).`;
  render();
}

async function fetchModels() {
  const cfg = collectSetupForm() ?? store.getConfig();
  modelListError = null;
  modelList = null;
  render();
  try {
    modelList = await new OllamaClient(cfg.ollamaBaseUrl, '', cfg.ollamaApiKey).listModels();
    if (modelList.length === 0) modelListError = 'Endpoint returned no models';
  } catch (e) {
    modelList = [];
    modelListError = `Could not list models: ${e.message}`;
  }
  render();
}

// ---- rendering ----

function render() {
  const titles = { dashboard: '· Dashboard', issues: '· Issues', review: '· Review', setup: '· Setup' };
  tabTitle.textContent = titles[currentTab];
  switch (currentTab) {
    case 'dashboard': renderDashboard(); break;
    case 'issues': renderIssues(); break;
    case 'review': renderReview(); break;
    case 'setup': renderSetup(); break;
  }
}

function renderDashboard() {
  const cfg = store.getConfig();
  const drafts = store.getDrafts();
  const pending = drafts.filter((d) => d.status === DraftStatus.DRAFT).length;
  const target = dailyTarget(cfg.dailyMin, cfg.dailyMax);
  const skipped = store.skippedCount();

  view.innerHTML = `
    <div class="card">
      <h3>Today</h3>
      <div>${store.draftsToday()} drafted · target ${cfg.dailyMin}–${cfg.dailyMax}/day (today: ${target})</div>
      <div>${pending} awaiting your review</div>
      ${configIsReady(cfg) ? '' : '<div class="error">⚠ Setup incomplete — add GitHub token, Ollama endpoint, and repos.</div>'}
      <div class="muted">Auto-run: ${cfg.autoRunEnabled ? 'ON — drafts while this app is open' : 'OFF'}</div>
    </div>
    <button class="primary" id="run-now" ${busy ? 'disabled' : ''}>${busy ? 'Working…' : 'Draft one PR now'}</button>
    ${statusMessage ? `<p>${esc(statusMessage)}</p>` : ''}
    <div class="card">
      <h3>Maintenance</h3>
      <div class="row">
        <button class="outline" id="retry-skipped" ${skipped === 0 ? 'disabled' : ''}>Retry skipped (${skipped})</button>
        <button class="outline" id="clean-branches">Clean branches</button>
      </div>
      ${maintenanceMessage ? `<p class="muted">${esc(maintenanceMessage)}</p>` : ''}
    </div>
    <h2 class="section">Activity log</h2>
    ${store.getLogs().slice(0, 40).map((l) => {
      const cls = { ERROR: 'error', WARN: 'warn', SUCCESS: 'success', INFO: 'muted' }[l.level] ?? 'muted';
      return `<div class="log-line ${cls}">• ${esc(l.message)}</div>`;
    }).join('') || '<p class="muted">No activity yet.</p>'}
  `;

  document.getElementById('run-now').onclick = runOnce;
  document.getElementById('retry-skipped').onclick = () => {
    store.clearSkipped();
    maintenanceMessage = 'Skipped issues are eligible again.';
    render();
  };
  document.getElementById('clean-branches').onclick = cleanupBranches;
}

function renderIssues() {
  view.innerHTML = `
    <button class="primary" id="load-issues" ${browseLoading ? 'disabled' : ''}>
      ${browseLoading ? 'Loading…' : 'Load workable issues'}
    </button>
    ${statusMessage ? `<p>${esc(statusMessage)}</p>` : ''}
    ${browseRows === null && !browseLoading
      ? '<p class="muted">No issues loaded. Tap the button to scan the configured repos for open, unassigned, unlocked issues.</p>' : ''}
    ${browseRows !== null && browseRows.length === 0 && !browseLoading
      ? '<p class="muted">No workable issues right now.</p>' : ''}
    ${(browseRows ?? []).map((row, i) => `
      <div class="card">
        <div class="mono muted">${esc(repoFullName(row.repo))}#${row.number}</div>
        <div class="issue-title">${esc(row.title)}</div>
        ${row.labels.length ? `<div class="muted">${row.labels.map((l) => `[${esc(l)}]`).join(' ')}</div>` : ''}
        <button class="outline" data-draft="${i}" ${busy ? 'disabled' : ''}>Draft this issue</button>
      </div>
    `).join('')}
  `;
  document.getElementById('load-issues').onclick = loadIssues;
  view.querySelectorAll('[data-draft]').forEach((btn) => {
    btn.onclick = () => {
      const row = browseRows[Number(btn.dataset.draft)];
      draftIssue(row.repo, row.number);
    };
  });
}

function renderReview() {
  const visible = store.getDrafts().filter(
    (d) => d.status === DraftStatus.DRAFT || d.status === DraftStatus.SUBMITTED,
  );
  view.innerHTML = `
    <button class="outline" id="recheck" ${refreshingDrafts || !visible.some((d) => d.status === DraftStatus.DRAFT) ? 'disabled' : ''}>
      ${refreshingDrafts ? 'Re-checking issue status…' : 'Re-check issue status'}
    </button>
    ${visible.length === 0 ? '<p class="muted">Nothing to review yet. Draft a PR from the Dashboard or Issues tab.</p>' : ''}
    ${visible.map((d, i) => `
      <div class="card">
        <div class="row">
          <strong class="mono">${esc(d.repoFullName)}#${d.issueNumber}</strong>
          ${d.isPrivateRepo ? '<span class="chip fit">PRIVATE</span>' : ''}
        </div>
        <div class="issue-title">${esc(d.issueTitle)}</div>
        ${d.isStale ? '<div class="error">⚠ Issue was closed/locked after drafting — this PR is probably obsolete. Discard it.</div>' : ''}
        <div class="mono muted">commit: ${esc(d.commitMessage.split('\n')[0])}</div>
        <div class="muted">${d.edits.length} file(s): ${d.edits.map((e) => esc(e.path.split('/').pop())).join(', ')}</div>
        ${d.status === DraftStatus.SUBMITTED ? '<div class="success">✓ marked submitted</div>' : ''}
        <button class="ghost" data-toggle="${i}">Show diff</button>
        <div class="hidden" data-diff="${i}">
          ${d.edits.map((e) => `
            <div class="mono">${esc(e.path)}${e.isNew ? ' (new file)' : ''}</div>
            ${e.oldString ? `<pre class="diff removed">- ${esc(e.oldString)}</pre>` : ''}
            <pre class="diff added">+ ${esc(e.newString)}</pre>
          `).join('')}
          ${d.agentNotes ? `<div class="muted">agent: ${esc(d.agentNotes)}</div>` : ''}
        </div>
        <div class="row">
          <button class="primary" data-open="${i}">Open PR</button>
          <button class="small fit" data-submitted="${i}">Submitted</button>
          <button class="small fit" data-discard="${i}">Discard</button>
        </div>
      </div>
    `).join('')}
  `;

  document.getElementById('recheck').onclick = refreshDrafts;
  view.querySelectorAll('[data-toggle]').forEach((btn) => {
    btn.onclick = () => {
      const diff = view.querySelector(`[data-diff="${btn.dataset.toggle}"]`);
      diff.classList.toggle('hidden');
      btn.textContent = diff.classList.contains('hidden') ? 'Show diff' : 'Hide diff';
    };
  });
  view.querySelectorAll('[data-open]').forEach((btn) => {
    btn.onclick = () => window.open(visible[Number(btn.dataset.open)].compareUrl, '_blank', 'noopener');
  });
  view.querySelectorAll('[data-submitted]').forEach((btn) => {
    btn.onclick = () => {
      store.updateDraftStatus(visible[Number(btn.dataset.submitted)].id, DraftStatus.SUBMITTED);
      render();
    };
  });
  view.querySelectorAll('[data-discard]').forEach((btn) => {
    btn.onclick = () => discardDraft(visible[Number(btn.dataset.discard)]);
  });
}

// Setup keeps unsaved form state across re-renders by reading the DOM before rebuilding.
function collectSetupForm() {
  const el = (id) => document.getElementById(id);
  if (!el('cfg-token')) return null;
  const stored = store.getConfig();
  return {
    ...stored,
    githubToken: el('cfg-token').value.trim(),
    ollamaBaseUrl: el('cfg-ollama-url').value.trim(),
    ollamaModel: el('cfg-model').value.trim(),
    ollamaApiKey: el('cfg-ollama-key').value.trim(),
    authorName: el('cfg-author-name').value.trim(),
    authorEmail: el('cfg-author-email').value.trim(),
    dailyMin: Math.max(1, parseInt(el('cfg-min').value, 10) || 5),
    dailyMax: Math.max(1, parseInt(el('cfg-max').value, 10) || 15),
    autoRunEnabled: el('cfg-autorun').checked,
    repos: JSON.parse(el('cfg-repos-state').value),
  };
}

function renderSetup() {
  const cfg = collectSetupForm() ?? store.getConfig();

  view.innerHTML = `
    <h2 class="section">GitHub</h2>
    <label class="field">Personal access token (repo scope)
      <input id="cfg-token" type="password" value="${esc(cfg.githubToken)}" autocomplete="off" />
    </label>

    <h2 class="section">Ollama</h2>
    <label class="field">Base URL
      <input id="cfg-ollama-url" value="${esc(cfg.ollamaBaseUrl)}" />
    </label>
    <label class="field">API key (Ollama Cloud, optional)
      <input id="cfg-ollama-key" type="password" value="${esc(cfg.ollamaApiKey)}" autocomplete="off" />
    </label>
    <div class="row">
      <label class="field">Model
        <input id="cfg-model" value="${esc(cfg.ollamaModel)}" />
      </label>
      <button class="small fit" id="list-models">List ▾</button>
    </div>
    ${modelList !== null && modelList.length > 0 ? `
      <select id="model-select" size="${Math.min(6, modelList.length)}" style="width:100%">
        ${modelList.map((m) => `<option value="${esc(m)}">${esc(m)}</option>`).join('')}
      </select>` : ''}
    ${modelListError ? `<p class="muted">${esc(modelListError)}</p>` : ''}
    <p class="muted">Self-hosted Ollama must allow this origin: set <span class="mono">OLLAMA_ORIGINS</span>.
    A local <span class="mono">ollama serve</span> can run cloud models (e.g. deepseek-v4-flash:cloud) and handles cloud auth itself.</p>

    <h2 class="section">Commit identity</h2>
    <label class="field">Author name <input id="cfg-author-name" value="${esc(cfg.authorName)}" /></label>
    <label class="field">Author email <input id="cfg-author-email" value="${esc(cfg.authorEmail)}" /></label>

    <h2 class="section">Daily pacing</h2>
    <div class="row">
      <label class="field">Min <input id="cfg-min" type="number" min="1" value="${cfg.dailyMin}" /></label>
      <label class="field">Max <input id="cfg-max" type="number" min="1" value="${cfg.dailyMax}" /></label>
    </div>
    <label class="check">
      <input id="cfg-autorun" type="checkbox" ${cfg.autoRunEnabled ? 'checked' : ''} />
      Auto-run while this app is open (browsers cannot draft in the background)
    </label>

    <h2 class="section">Repositories</h2>
    <p class="muted">Each entry can pin the exact fork to use. Without a fork mapping, the app uses
    &lt;your login&gt;/&lt;repo&gt; and creates the fork if it's missing.</p>
    <input type="hidden" id="cfg-repos-state" value="${esc(JSON.stringify(cfg.repos))}" />
    <div id="repo-list">
      ${cfg.repos.map((r, i) => `
        <div class="row" style="margin-bottom:6px">
          <div>
            <div>${esc(repoFullName(r))}</div>
            ${forkOverrideLabel(r) ? `<div class="muted mono">fork: ${esc(forkOverrideLabel(r))}</div>` : ''}
          </div>
          <button class="small fit" data-edit-repo="${i}">Edit</button>
          <button class="small fit" data-remove-repo="${i}">Remove</button>
        </div>
      `).join('')}
    </div>
    <button class="outline" id="add-repo">Add repository</button>

    <div style="margin-top:16px">
      <button class="primary" id="save-config">Save settings</button>
      <p id="save-msg" class="success hidden">Saved.</p>
    </div>
    <button class="ghost" id="enable-notifs">Enable notifications</button>
  `;

  document.getElementById('list-models').onclick = fetchModels;
  const select = document.getElementById('model-select');
  if (select) {
    select.onchange = () => { document.getElementById('cfg-model').value = select.value; };
  }
  document.getElementById('save-config').onclick = () => {
    const collected = collectSetupForm();
    store.saveConfig(collected);
    document.getElementById('save-msg').classList.remove('hidden');
  };
  document.getElementById('enable-notifs').onclick = () => {
    if (typeof Notification !== 'undefined') Notification.requestPermission();
  };
  document.getElementById('add-repo').onclick = () => openRepoDialog(null);
  view.querySelectorAll('[data-edit-repo]').forEach((btn) => {
    btn.onclick = () => openRepoDialog(Number(btn.dataset.editRepo));
  });
  view.querySelectorAll('[data-remove-repo]').forEach((btn) => {
    btn.onclick = () => {
      const collected = collectSetupForm();
      collected.repos.splice(Number(btn.dataset.removeRepo), 1);
      syncReposState(collected.repos);
      renderSetup();
    };
  });
}

function syncReposState(repos) {
  const state = document.getElementById('cfg-repos-state');
  if (state) state.value = JSON.stringify(repos);
}

function openRepoDialog(index) {
  const collected = collectSetupForm();
  const repo = index !== null ? collected.repos[index] : { owner: '', name: '', forkOwner: null, forkName: null };
  const dialog = document.getElementById('repo-dialog');
  dialog.innerHTML = `
    <h3>${index === null ? 'Add repository' : 'Edit repository'}</h3>
    <label class="field">Upstream owner <input id="dlg-owner" value="${esc(repo.owner)}" /></label>
    <label class="field">Upstream repository <input id="dlg-name" value="${esc(repo.name)}" /></label>
    <p class="muted">Fork mapping (optional): leave blank to use &lt;your login&gt;/&lt;repository&gt;
    (auto-created if missing). Fill in to pin an existing fork — it is verified, never created.</p>
    <label class="field">Fork owner <input id="dlg-fork-owner" value="${esc(repo.forkOwner ?? '')}" /></label>
    <label class="field">Fork repository <input id="dlg-fork-name" value="${esc(repo.forkName ?? '')}" /></label>
    <div class="row">
      <button class="primary" id="dlg-save">Save</button>
      <button class="small fit" id="dlg-cancel">Cancel</button>
    </div>
  `;
  dialog.showModal();
  dialog.querySelector('#dlg-cancel').onclick = () => dialog.close();
  dialog.querySelector('#dlg-save').onclick = () => {
    const owner = dialog.querySelector('#dlg-owner').value.trim();
    const name = dialog.querySelector('#dlg-name').value.trim();
    if (!owner || !name) return;
    const entry = {
      owner,
      name,
      forkOwner: dialog.querySelector('#dlg-fork-owner').value.trim() || null,
      forkName: dialog.querySelector('#dlg-fork-name').value.trim() || null,
    };
    if (index === null) collected.repos.push(entry);
    else collected.repos[index] = entry;
    dialog.close();
    syncReposState(collected.repos);
    renderSetup();
  };
}

// ---- tabs, install prompt, service worker ----

document.querySelectorAll('.tabbar button').forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll('.tabbar button').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    currentTab = btn.dataset.tab;
    render();
  };
});

let installPrompt = null;
const installBtn = document.getElementById('install-btn');
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  installPrompt = e;
  installBtn.classList.remove('hidden');
});
installBtn.onclick = async () => {
  if (!installPrompt) return;
  installPrompt.prompt();
  await installPrompt.userChoice;
  installPrompt = null;
  installBtn.classList.add('hidden');
};

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

store.onChange(() => { /* re-render on external store changes */ });
render();
