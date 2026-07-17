// UI smoke test: serve web/ statically, load in Chromium, click through all tabs,
// exercise the repo dialog, and fail on any console error or missing element.
// Chromium path comes from CHROME_PATH (CI) or the local Playwright cache.
// Requires playwright-core (installed on demand; not a runtime dependency of the app).
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = process.env.CHROME_PATH || '/opt/pw-browsers/chromium';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json', '.json': 'application/json',
};

const server = createServer(async (req, res) => {
  let path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (path === '/' || path === '\\') path = '/index.html';
  try {
    const data = await readFile(join(ROOT, path));
    res.writeHead(200, { 'Content-Type': MIME[extname(path)] ?? 'application/octet-stream' });
    res.end(data);
  } catch {
    res.writeHead(404); res.end('nope');
  }
});
await new Promise((resolve) => server.listen(0, resolve));
const port = server.address().port;

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const page = await browser.newPage();
const errors = [];
page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
page.on('pageerror', (err) => errors.push(String(err)));

await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'networkidle' });

const results = [];
const check = (name, ok) => { results.push(`${ok ? 'PASS' : 'FAIL'}: ${name}`); if (!ok) process.exitCode = 1; };

// Dashboard renders with setup warning and the run button.
check('dashboard renders', (await page.textContent('#view')).includes('Draft one PR now'));
check('setup-incomplete warning shows', (await page.textContent('#view')).includes('Setup incomplete'));

// Issues tab.
await page.click('[data-tab="issues"]');
check('issues tab renders', (await page.textContent('#view')).includes('Load workable issues'));

// Review tab.
await page.click('[data-tab="review"]');
check('review tab renders', (await page.textContent('#view')).includes('Nothing to review yet'));

// Setup tab: fields prefilled with defaults.
await page.click('[data-tab="setup"]');
check('setup shows author default', await page.inputValue('#cfg-author-name') === 'Ilum');
check('setup shows email default', await page.inputValue('#cfg-author-email') === 'Ilum@linux.org');
check('setup shows model default', await page.inputValue('#cfg-model') === 'deepseek-v4-flash:cloud');
check('setup lists 7 repos', (await page.$$('[data-edit-repo]')).length === 7);

// Repo dialog opens, saves an entry with fork mapping, and the list updates.
await page.click('#add-repo');
await page.fill('#dlg-owner', 'someorg');
await page.fill('#dlg-name', 'somerepo');
await page.fill('#dlg-fork-owner', 'myuser');
await page.click('#dlg-save');
check('repo dialog adds entry', (await page.textContent('#repo-list')).includes('someorg/somerepo'));
check('fork mapping shown', (await page.textContent('#repo-list')).includes('myuser/somerepo'));

// Save settings persists to localStorage.
await page.fill('#cfg-token', 'test-token-123');
await page.click('#save-config');
const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('surgeon.config')));
check('config saved with token', saved.githubToken === 'test-token-123');
check('config saved with 8 repos', saved.repos.length === 8);

// Back to dashboard: token + defaults + repos → ready, so the warning must be gone.
await page.click('[data-tab="dashboard"]');
check('setup warning cleared once ready', !(await page.textContent('#view')).includes('Setup incomplete'));

// Service worker registered.
const swRegistered = await page.evaluate(async () => {
  const reg = await navigator.serviceWorker.getRegistration();
  return Boolean(reg);
});
check('service worker registered', swRegistered);

// Manifest reachable and valid.
const manifestOk = await page.evaluate(async () => {
  const resp = await fetch('manifest.webmanifest');
  if (!resp.ok) return false;
  const m = await resp.json();
  return m.name?.includes('Surgeon') && Array.isArray(m.icons) && m.icons.length >= 2;
});
check('manifest valid', manifestOk);

check('no console/page errors', errors.length === 0);
if (errors.length) console.log('ERRORS:', errors);

console.log(results.join('\n'));
await browser.close();
server.close();
