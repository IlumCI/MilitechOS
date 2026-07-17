# Surgeon

**Surgical PR automation with a human gate.** Surgeon finds an open issue in the repos you work
on, prepares an issue branch on your fork, has an Ollama model draft the *smallest possible*
change that resolves it, commits the change under your identity — and then hands you the diff
and a one-tap compare link. **You** review and open every pull request. Nothing ships without
your eyes on it.

Runs as an installable **Progressive Web App** on desktop and mobile. Zero dependencies, no
build step: vanilla ES modules served straight from GitHub Pages.

**Live app:** https://ilumci.github.io/MilitechOS/

## How it works

```
pick issue ──► verify fork ──► fast-forward to upstream ──► locate files (model)
     │              │                    │
     │              │                    └─ diverged fork? STOP (no merge conflicts, ever)
     │              └─ explicit fork mapping is verified, never guessed
     │
     ▼
plan surgical edits (model) ──► validate: exact-match replacements, file/size caps,
     │                          JSON parses, YAML sane, nothing left empty
     ▼
independent critic pass (model) — must actively approve; fails closed
     ▼
re-check issue is still open/unassigned ──► commit as you ──► YOU review & open the PR
```

Safety rails: issues with `wontfix`/`duplicate`/`question`-style labels are never picked;
abstains and rejections go to retryable *skip memory*; discarded drafts delete their branch;
a janitor sweeps orphaned `surgeon/`-prefixed branches; GitHub rate limits back off cleanly.
Private repos get stricter caps and a **PRIVATE** badge so you scrutinise those diffs harder.

## Quick start

1. Open the [live app](https://ilumci.github.io/MilitechOS/) (or serve `web/` with any static
   server). Install it from the browser menu if you want an app window.
2. In **Setup**: paste a GitHub token (`repo` scope), point at your Ollama endpoint, pick a
   model (the **List ▾** button queries `/api/tags`), adjust repos/fork mappings, save.
3. **Dashboard → Draft one PR now**, or cherry-pick from the **Issues** tab.
4. **Review** the diff, tap **Open PR**, submit on GitHub. That's the whole loop.

Ollama notes: self-hosted servers must allow the app's origin via `OLLAMA_ORIGINS`. A local
`ollama serve` can run cloud models (e.g. `deepseek-v4-flash:cloud`) and handles cloud auth —
point the app at `http://localhost:11434`.

## Repository layout

| Path | What |
|------|------|
| `web/` | The entire app — UI, engine, clients, service worker. See [`web/README.md`](web/README.md) for full docs, honest limitations, and the testing story. |
| `web/tests/` | 65 unit/integration tests (`node --test`), including an end-to-end engine test against fake GitHub/Ollama servers. |
| `web/tests-browser/` | 16-check Chromium smoke test of the real UI. |
| `.github/workflows/web.yml` | CI: tests gate every push and PR; green `main` deploys to GitHub Pages. |

## Development

```bash
cd web
npm test                       # unit + integration (no dependencies)
python3 -m http.server 8080    # or any static server, then open localhost:8080
```

The project began as a native Android app (Kotlin/Compose); those sources live in git history
before the "Replace Android app with Surgeon PWA" commit.

## License

MIT — see [LICENSE](LICENSE).
