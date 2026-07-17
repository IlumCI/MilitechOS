# Surgeon — surgical PR automation (PWA)

Surgeon automates the repetitive part of the issue → PR workflow while keeping **you** at the
one decision that matters: the pull request itself. It finds an open issue in a repo you work on,
prepares an issue branch on your fork, has an Ollama model draft the *smallest possible* change to
resolve it, commits that change under your identity, and hands you a one-tap compare link to open
the PR — where you review the diff and submit. Nothing is submitted without you.

It is a **Progressive Web App**: zero dependencies, no build step, installable on desktop
(Chrome/Edge → install icon in the address bar) and Android (Chrome → "Add to Home screen"),
usable on iOS via Safari share-sheet → "Add to Home Screen".

## Pipeline (unchanged from the Android version)

1. **Pick an issue** — open, unassigned, unlocked, not previously handled, no disqualifying
   labels (`wontfix`, `duplicate`, `question`, …).
2. **Prepare the fork** — explicit fork mapping is verified (never auto-created); otherwise
   `<your login>/<repo>` is used and created if missing. The fork is fast-forwarded to upstream,
   and a **diverged fork stops the pipeline** — that's how merge conflicts are avoided.
3. **Locate → plan** — the model picks files (≤3) and returns exact string replacements. Every
   replacement must match exactly once; file/size caps enforced (tighter for private repos).
4. **Validate + self-review** — JSON must parse, YAML must not use tabs, no file left empty;
   then an independent critic model call must actively approve (complete, minimal, non-breaking),
   failing closed on garbage output.
5. **Freshness gate** — the issue is re-fetched; if it was closed/assigned/locked while the
   model worked, the work is discarded with zero writes.
6. **Commit** — one commit on a `surgeon/issue-N-slug` branch, authored as the configured
   identity (default `Ilum <Ilum@linux.org>`).
7. **You review** — the Review tab shows the diff; **Open PR** opens GitHub's compare page.
   Submitting is always manual.

Retryable outcomes (abstain/reject/validation/critic) land in *skipped* memory and can be
re-enabled with **Retry skipped**; drafted or dead issues are burned permanently. Discarding a
draft deletes its branch; **Clean branches** removes orphaned `surgeon/`-prefixed branches.
Rate limits (429 / drained-quota 403) surface as a clean back-off.

## Browser realities (honest limitations)

- **No background automation.** Auto-run paces drafts (5–15/day target) **only while the app is
  open** — a browser tab or the installed window. Browsers do not run arbitrary background jobs.
- **CORS.** GitHub's API allows browser calls. Ollama does not by default:
  - Self-hosted: start with `OLLAMA_ORIGINS` including this app's origin (or `*`).
  - Cloud models: a local `ollama serve` can run them (e.g. `deepseek-v4-flash:cloud`) and
    handles cloud auth itself — point the app at `http://localhost:11434`. On a phone, point at
    your desktop's LAN IP instead.
- **Secrets live in `localStorage`** of your browser profile. Don't use it on shared machines.
- A page served over HTTPS (GitHub Pages) calling `http://localhost:11434` is mixed content:
  Chrome/Edge allow localhost specifically; a LAN IP from a phone may require serving Ollama
  behind HTTPS or using a browser that permits it.

## Running

- **Hosted**: pushed builds deploy to GitHub Pages via `.github/workflows/web.yml`
  (tests must pass first). Settings → Pages → Source must be "GitHub Actions".
- **Local**: any static server — `python3 -m http.server -d web 8080` → http://localhost:8080.

## Testing

Zero-dependency test suite on Node's built-in runner (`node --test`), plus a real-browser
smoke test. CI runs both **before** deploying; a red test blocks the deploy.

- `tests/engine.test.mjs` — end-to-end: fake GitHub + fake Ollama fetch routers drive the real
  engine/clients/agent through the whole workflow. Asserts author identity on the commit,
  edited blob content, branch naming, compare URL, and every safety gate (closed-mid-run,
  diverged fork, critic rejection → retryable, labels, rate limit, manual-pick semantics,
  janitor spare/delete, explicit fork mapping).
- `tests/agent.test.mjs` — replacement validation (missing/ambiguous targets, caps, danger
  mode, fenced JSON, abstain, garbage) and critic parsing (fails closed).
- `tests/github.test.mjs` — base64 decoding, PR filtering, headers, fork verification,
  rate-limit classification, request bodies, ref deletion.
- `tests/store.test.mjs`, `tests/utils.test.mjs`, `tests/validators.test.mjs`.
- `tests-browser/smoke.mjs` — Chromium loads the real UI: all tabs render, repo dialog +
  fork mapping works, config persists, service worker registers, manifest is valid, and no
  console errors occur.

```bash
cd web
npm test                                # 65 unit/integration tests
npm i playwright-core && node tests-browser/smoke.mjs   # 16 browser checks
```

## History

Surgeon began as a native Android app (Kotlin/Compose, Git Data API, WorkManager). It was
replaced by this PWA to run on desktop and phone alike; the Android sources live in git history
before the "Replace Android app with PWA" commit.
