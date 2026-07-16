# Surgeon — surgical PR automation (Android)

Surgeon automates the repetitive part of the issue → PR workflow while keeping **you** at the
one decision that matters: the pull request itself. It finds an open issue in a repo you work on,
prepares an issue branch on your fork, has an Ollama model draft the *smallest possible* change to
resolve it, commits that change under your identity, and then hands you a one-tap link to open the
PR — where you review the diff and submit. Nothing is submitted without you.

## Why it works this way

Cloning full repos on a phone is slow and fragile. Surgeon performs the identical workflow through
GitHub's **Git Data API** instead of local `git`:

1. **Pick an issue** — an open, unassigned, not-yet-processed issue from one of your repos.
2. **Prepare the fork** — ensure your fork exists, **fast-forward it to the latest upstream**
   (`merge-upstream`) so you never build on stale history — this is what avoids merge conflicts.
3. **Locate** — the agent picks only the file(s) that must change (usually 1, never more than 3).
4. **Plan surgically** — the agent returns exact string replacements. Every replacement is validated
   locally: it must match its target **exactly once**, file/size caps are enforced, and anything
   that looks non-surgical is rejected. Private repos get stricter caps (≤2 files) and a lower
   temperature.
5. **Commit** — new branch on your fork, one commit, authored as your configured identity.
6. **Review gate (you)** — Surgeon shows the diff and an **Open PR** button (a GitHub compare link).
   You read it and submit. This is the only manual step, by design.

If the agent isn't confident it can make a safe, minimal change, it **abstains** and the issue is
skipped rather than producing slop.

## Safety model

- **Human-in-the-loop at submit.** The app never opens or merges PRs for you.
- **Private = danger.** Issues in private repos are flagged, capped tighter, and badged `PRIVATE`
  in the review queue so you scrutinise them harder.
- **Non-breaking bias.** The system prompt forbids refactors, renames, reformatting, and unrelated
  edits; validation drops anything that doesn't apply cleanly.
- **No duplicates.** Processed issues are remembered so the same one is never picked twice.
- **Only live, workable issues.** Candidates must be open, unassigned, unlocked, and free of
  disqualifying labels (`wontfix`, `duplicate`, `question`, `blocked`, …) — and the issue is
  **re-fetched and re-verified right before committing**, so work is discarded if it was closed,
  assigned, or locked while the model was running.
- **Fork identity verification.** Before touching `you/<repo>`, the app confirms it really is a
  fork of the intended upstream — a same-named unrelated repo is refused outright.
- **Divergence guard.** After syncing, the fork's head must exactly match upstream's; a diverged
  fork (the merge-conflict scenario) is skipped with a loud log instead of being built on.
- **No silent overwrites.** A file the agent claims is "new" is checked against the repo at the
  base commit; if it already exists, the change is rejected.
- **Independent self-review.** A second model call critiques the produced diff against the issue
  (complete? minimal? non-breaking?) and must actively approve it; unparseable critic output
  fails closed. Structural validators run first: edited `.json` must parse, `.yml`/`.yaml` must
  not use tab indentation, and no file may be left empty.
- **Retryable vs permanent memory.** Abstains/rejections are remembered as *skipped* (re-enable
  them any time with "Retry skipped" — useful after switching to a better model); only drafted or
  dead issues are burned permanently.
- **Rate-limit aware.** 429s and drained-quota 403s are detected and surfaced as a clean back-off
  instead of hammering the API.
- **Branch janitor.** Discarding a draft deletes its branch on the fork; "Clean branches" removes
  orphaned `surgeon/*` branches that no live draft references (never touches anything else).
- **Stale-draft detection.** "Re-check issue status" in Review flags drafts whose issue was
  closed or locked after drafting, so you don't submit an obsolete PR.

## Testing

The pipeline is covered by a JVM test suite (`app/src/test`) that runs in CI **before** the APK
is built — a red test fails the build:

- `AutomationEngineTest` — end-to-end: a fake GitHub API and a fake Ollama server (OkHttp
  MockWebServer) drive the real engine, clients, and agent through the complete
  issue → fork → locate → plan → validate → critique → commit workflow. Asserts the commit is
  authored as the configured identity, the blob carries the edited content, safety gates fire
  (closed-mid-run, diverged fork, critic rejection, labels, rate limits), manual pick semantics,
  and the branch janitor's spare/delete behaviour.
- `SurgicalAgentTest` — edit validation (missing/ambiguous `old_string`, file caps, size caps,
  danger-mode caps, fenced JSON, abstain, garbage output) and critic parsing (fails closed).
- `GitHubClientTest` — base64 decoding, PR filtering, auth headers, fork verification,
  rate-limit classification, request bodies (author identity, parents), ref deletion.
- `StoreTest` — processed/skipped memory persistence and clearing, draft lifecycle, daily counts.
- `EngineUtilsTest` / `EditValidatorsTest` — ranking, slugs, labels, JSON/YAML validators.

Run locally with `./gradlew testDebugUnitTest`.

## Configuration (Setup tab)

| Field | Notes |
|-------|-------|
| GitHub token | Classic or fine-grained PAT with `repo` scope (fork + read + push to your fork). |
| Ollama base URL | Defaults to `https://ollama.com` (Ollama Cloud). For a self-hosted server use `http://<host>:11434`. |
| Ollama model | Defaults to `deepseek-v4-flash:cloud`. Type a tag or tap **List ▾** to pick from the models your endpoint actually serves (`/api/tags`). |
| Ollama API key | Required for Ollama Cloud (`https://ollama.com`). Create one at ollama.com. Leave blank for a self-hosted server. |
| Author name / email | Stamped on every automated commit. Defaults to `Ilum <Ilum@linux.org>`. |
| Daily min/max | Pacing target (recommended 5–15). The background worker spreads drafts across the day. |
| Auto-run | When on, a ~hourly `WorkManager` job drafts up to the day's target automatically. |
| Repositories | The repos to pull issues from. Pre-seeded with the swarms set. Each entry can optionally pin the exact fork (owner + name); unset means `<your login>/<repo>`, auto-created if missing. Pinned forks are verified against the upstream and never auto-created. |

For a self-hosted server on an emulator, `10.0.2.2` reaches the host machine; on a real device use
the server's LAN IP.

## Building

The Gradle **wrapper JAR** is intentionally not committed. Build one of two ways:

```bash
# Option A — Android Studio: File ▸ Open ▸ android/  (Studio provisions Gradle automatically)

# Option B — command line (generate the wrapper once, then assemble):
cd android
gradle wrapper --gradle-version 8.11.1
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK (compileSdk 35). Min Android 8.0 (API 26).

## Module map

```
app/src/main/java/eu/euroswarms/surgeon/
  data/       AppConfig + DataStore, models, JSON store (drafts/logs/processed issues)
  net/        GitHubClient (Git Data API), OllamaClient, shared HTTP
  engine/     Prompts, SurgicalAgent (locate + plan + validate), AutomationEngine (pipeline)
  work/       AutomationWorker (paced drafting), Scheduler, Notifier
  ui/         Compose screens (Dashboard / Review / Setup), AppViewModel, theme
```

## Limitations (v1)

- Model quality is the ceiling on change quality; the review gate exists precisely because an
  unattended small model will sometimes be wrong. Read every diff before submitting.
- File location relies on the repo tree + issue text; very large repos truncate the candidate list.
- Files over ~1 MB are skipped by the contents API path.
