// Two-phase surgical agent + critic, backed by an Ollama model.
//   locate — pick the files to touch.
//   plan   — produce exact string replacements, validated before anything is committed.
//   critique — independent second model call that must actively approve the change.
// Validation is what keeps changes "surgical": every replacement must match its target
// exactly once, and file/size caps are enforced (tighter in danger mode).

import * as Prompts from './prompts.js';
import { occurrencesOf } from './utils.js';

export const MAX_FILES = 3;
export const MAX_FILES_DANGER = 2;
export const MAX_CHANGED_BYTES = 8000;
export const CRITIC_SNIPPET_CHARS = 2000;

/** Strips markdown fences and isolates the outermost {...} object; null if none. */
export function extractJsonObject(raw) {
  const trimmed = String(raw).trim()
    .replace(/^```json/, '').replace(/^```/, '').replace(/```$/, '').trim();
  const start = trimmed.indexOf('{');
  const end = trimmed.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(trimmed.slice(start, end + 1));
  } catch {
    return null;
  }
}

export class SurgicalAgent {
  constructor(ollama) {
    this.ollama = ollama;
  }

  async locate(issueTitle, issueBody, paths) {
    if (paths.length === 0) return [];
    const raw = await this.ollama.chat([
      { role: 'system', content: Prompts.LOCATE_SYSTEM },
      { role: 'user', content: Prompts.locateUser(issueTitle, issueBody, paths) },
    ], { temperature: 0.0 });
    const parsed = extractJsonObject(raw);
    if (!parsed || !Array.isArray(parsed.files)) return [];
    const allowed = new Set(paths);
    return parsed.files.filter((f) => allowed.has(f)).slice(0, MAX_FILES);
  }

  /**
   * Returns one of:
   *   {kind:'ready', edits, finalContents, commitMessage, notes}
   *   {kind:'abstained', reason}
   *   {kind:'rejected', reason}
   */
  async plan(issueTitle, issueBody, files, danger) {
    const raw = await this.ollama.chat([
      { role: 'system', content: Prompts.editSystem(danger) },
      { role: 'user', content: Prompts.editUser(issueTitle, issueBody, files) },
    ], { temperature: danger ? 0.0 : 0.15 });

    const plan = extractJsonObject(raw);
    if (!plan) return { kind: 'rejected', reason: 'Model returned unparseable output' };
    if (plan.abstain) {
      return { kind: 'abstained', reason: plan.reason || 'Agent chose to abstain' };
    }
    const planEdits = Array.isArray(plan.edits) ? plan.edits : [];
    if (planEdits.length === 0) {
      return { kind: 'abstained', reason: 'Agent produced no edits' };
    }

    const fileCap = danger ? MAX_FILES_DANGER : MAX_FILES;
    const touched = new Set(planEdits.map((e) => e.path));
    if (touched.size > fileCap) {
      return { kind: 'rejected', reason: `Edit touches ${touched.size} files (cap ${fileCap})` };
    }

    // Apply edits to a working copy, validating each replacement.
    const working = { ...files };
    const outEdits = [];
    let changedBytes = 0;

    for (const edit of planEdits) {
      const path = edit.path;
      const isNew = Boolean(edit.is_new);
      const oldString = edit.old_string ?? null;
      const newString = edit.new_string ?? '';

      if (!path) return { kind: 'rejected', reason: 'Edit with empty path' };

      if (isNew) {
        if (path in files) {
          return { kind: 'rejected', reason: `Edit marks existing file '${path}' as new` };
        }
        working[path] = newString;
        changedBytes += newString.length;
        outEdits.push({ path, oldString: null, newString, isNew: true });
        continue;
      }

      const current = working[path];
      if (current === undefined) {
        return { kind: 'rejected', reason: `Edit targets unknown file '${path}'` };
      }
      if (!oldString) {
        return { kind: 'rejected', reason: `Non-new edit for '${path}' is missing old_string` };
      }
      const occurrences = occurrencesOf(current, oldString);
      if (occurrences === 0) {
        return { kind: 'rejected', reason: `old_string not found in '${path}'` };
      }
      if (occurrences > 1) {
        return { kind: 'rejected', reason: `old_string is ambiguous in '${path}' (${occurrences} matches)` };
      }
      working[path] = current.replace(oldString, newString);
      changedBytes += Math.abs(newString.length - oldString.length);
      outEdits.push({ path, oldString, newString, isNew: false });
    }

    if (changedBytes > MAX_CHANGED_BYTES) {
      return { kind: 'rejected', reason: `Change too large (${changedBytes} bytes); not surgical` };
    }

    const finalContents = {};
    for (const edit of outEdits) finalContents[edit.path] = working[edit.path] ?? '';
    return {
      kind: 'ready',
      edits: outEdits,
      finalContents,
      commitMessage: plan.commit_message || `Address issue: ${issueTitle}`,
      notes: plan.reason || '',
    };
  }

  /** Independent self-review; returns {approved, reason}. Fails closed on garbage output. */
  async critique(issueTitle, issueBody, outcome, danger) {
    const rendered = outcome.edits.map((edit) => {
      let block = `FILE: ${edit.path}${edit.isNew ? ' (new file)' : ''}\n`;
      if (edit.oldString) block += `REMOVED:\n${edit.oldString.slice(0, CRITIC_SNIPPET_CHARS)}\n`;
      block += `ADDED:\n${edit.newString.slice(0, CRITIC_SNIPPET_CHARS)}`;
      return block;
    }).join('\n\n');

    const raw = await this.ollama.chat([
      { role: 'system', content: Prompts.criticSystem(danger) },
      { role: 'user', content: Prompts.criticUser(issueTitle, issueBody, outcome.commitMessage, rendered) },
    ], { temperature: 0.0 });

    const verdict = extractJsonObject(raw);
    if (!verdict || typeof verdict.approve !== 'boolean') {
      return { approved: false, reason: 'Critic output unparseable' };
    }
    return {
      approved: verdict.approve,
      reason: verdict.reason || (verdict.approve ? 'approved' : 'no reason given'),
    };
  }
}
