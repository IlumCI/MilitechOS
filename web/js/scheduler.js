// Foreground auto-run pacing. Browsers can't run background jobs like Android's
// WorkManager, so this drives drafting while the app (tab or installed window) is open:
// every minute it checks whether we're under today's target and enough time has passed
// since the last attempt, then drafts at most one PR.

import { AutomationEngine } from './engine.js';

const CHECK_EVERY_MS = 60 * 1000;
const MIN_GAP_MS = 20 * 60 * 1000; // at most one auto attempt per 20 minutes

/** Deterministic per-day target within [min, max] (stable across page reloads). */
export function dailyTarget(min, max, dayStamp = Math.floor(Date.now() / 86400000)) {
  const lo = Math.max(1, min | 0);
  const hi = Math.max(lo, max | 0);
  if (hi === lo) return lo;
  // Small LCG on the day number keeps the target stable for the whole day.
  const seed = (dayStamp * 1103515245 + 12345) >>> 0;
  return lo + (seed % (hi - lo + 1));
}

export class ForegroundScheduler {
  constructor(store, { onDrafted = () => {}, onStatus = () => {} } = {}) {
    this.store = store;
    this.onDrafted = onDrafted;
    this.onStatus = onStatus;
    this.lastAttempt = 0;
    this.running = false;
    this.timer = null;
  }

  start() {
    if (this.timer) return;
    this.timer = setInterval(() => this.#tick(), CHECK_EVERY_MS);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async #tick() {
    if (this.running) return;
    const cfg = this.store.getConfig();
    if (!cfg.autoRunEnabled) return;
    if (Date.now() - this.lastAttempt < MIN_GAP_MS) return;

    const target = dailyTarget(cfg.dailyMin, cfg.dailyMax);
    if (this.store.draftsToday() >= target) return;

    this.lastAttempt = Date.now();
    this.running = true;
    this.onStatus('Auto-run: drafting…');
    try {
      const result = await new AutomationEngine(cfg, this.store).runOnce();
      if (result.kind === 'drafted') this.onDrafted(result.draft);
      this.onStatus(null);
    } catch (e) {
      this.store.log('ERROR', `Auto-run error: ${e.message}`);
      this.onStatus(null);
    } finally {
      this.running = false;
    }
  }
}
