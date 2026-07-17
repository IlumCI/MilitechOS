// Persistence for config, drafts, logs, and issue memory. Backed by any localStorage-like
// object (browser localStorage in the app, an in-memory shim in tests).
//
// Issue memory is split in two:
//  - processed: permanently handled (drafted, or dead at re-check). Never picked again.
//  - skipped:   retryable outcomes (abstain/reject/validation/critic failure).
//               Excluded from selection until the user clears them ("Retry skipped").

import { defaultConfig, DraftStatus } from './models.js';

const KEYS = {
  config: 'surgeon.config',
  drafts: 'surgeon.drafts',
  logs: 'surgeon.logs',
  processed: 'surgeon.processed',
  skipped: 'surgeon.skipped',
};

const MAX_LOGS = 300;

export class Store {
  constructor(storage) {
    this.storage = storage;
    this.listeners = new Set();
  }

  onChange(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  #emit() {
    for (const fn of this.listeners) fn();
  }

  #read(key, fallback) {
    try {
      const raw = this.storage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch {
      return fallback;
    }
  }

  #write(key, value) {
    this.storage.setItem(key, JSON.stringify(value));
  }

  // ---- Config ----

  getConfig() {
    return { ...defaultConfig(), ...this.#read(KEYS.config, {}) };
  }

  saveConfig(cfg) {
    this.#write(KEYS.config, cfg);
    this.#emit();
  }

  // ---- Drafts ----

  getDrafts() {
    return this.#read(KEYS.drafts, []);
  }

  upsertDraft(draft) {
    const drafts = this.getDrafts();
    const idx = drafts.findIndex((d) => d.id === draft.id);
    if (idx >= 0) drafts[idx] = draft;
    else drafts.unshift(draft);
    this.#write(KEYS.drafts, drafts);
    this.#emit();
  }

  updateDraftStatus(id, status) {
    const draft = this.getDrafts().find((d) => d.id === id);
    if (draft) this.upsertDraft({ ...draft, status });
  }

  /** Number of drafts created since local midnight, in any non-discarded state. */
  draftsToday() {
    const midnight = new Date();
    midnight.setHours(0, 0, 0, 0);
    const start = midnight.getTime();
    return this.getDrafts().filter(
      (d) => d.createdAt >= start &&
        d.status !== DraftStatus.DISCARDED && d.status !== DraftStatus.FAILED,
    ).length;
  }

  // ---- Logs ----

  getLogs() {
    return this.#read(KEYS.logs, []);
  }

  log(level, message) {
    const logs = this.getLogs();
    logs.unshift({ id: crypto.randomUUID(), timestamp: Date.now(), level, message });
    this.#write(KEYS.logs, logs.slice(0, MAX_LOGS));
    this.#emit();
  }

  // ---- Issue memory ----

  #key(repoFullName, issueNumber) {
    return `${repoFullName}#${issueNumber}`;
  }

  isProcessed(repoFullName, issueNumber) {
    return this.#read(KEYS.processed, []).includes(this.#key(repoFullName, issueNumber));
  }

  markProcessed(repoFullName, issueNumber) {
    const set = new Set(this.#read(KEYS.processed, []));
    set.add(this.#key(repoFullName, issueNumber));
    this.#write(KEYS.processed, [...set]);
    this.#emit();
  }

  isSkipped(repoFullName, issueNumber) {
    return this.#read(KEYS.skipped, []).includes(this.#key(repoFullName, issueNumber));
  }

  markSkipped(repoFullName, issueNumber) {
    const set = new Set(this.#read(KEYS.skipped, []));
    set.add(this.#key(repoFullName, issueNumber));
    this.#write(KEYS.skipped, [...set]);
    this.#emit();
  }

  skippedCount() {
    return this.#read(KEYS.skipped, []).length;
  }

  /** Forget all retryable skips so those issues become eligible again. */
  clearSkipped() {
    this.#write(KEYS.skipped, []);
    this.#emit();
  }
}

/** In-memory localStorage-compatible shim (used by tests). */
export class MemoryStorage {
  constructor() { this.map = new Map(); }
  getItem(k) { return this.map.has(k) ? this.map.get(k) : null; }
  setItem(k, v) { this.map.set(k, String(v)); }
  removeItem(k) { this.map.delete(k); }
}
