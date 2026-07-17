// Pure helpers, mirrored from the Kotlin EngineUtils (and unit-tested identically).

/** Issues with any of these (normalized) labels are never picked. */
export const SKIP_LABELS = new Set([
  'wontfix', 'duplicate', 'invalid', 'question', 'discussion',
  'blocked', 'on-hold', 'needs-discussion', 'wip',
]);

export function normalizeLabel(name) {
  return String(name).toLowerCase().replace(/[\s_]+/g, '-');
}

export function slugify(title) {
  const slug = String(title).toLowerCase()
    .replace(/[^a-z0-9]/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
  const capped = slug.slice(0, 40).replace(/^-|-$/g, '');
  return capped || 'change';
}

/**
 * Orders candidate paths by textual relevance to the issue so the locate model sees the
 * most promising files first. Filename hits count more than directory hits.
 */
export function rankPaths(paths, issueTitle, issueBody, cap) {
  const tokens = [...new Set(
    `${issueTitle} ${issueBody}`.toLowerCase().split(/[^a-z0-9]+/).filter((t) => t.length >= 3),
  )];
  if (tokens.length === 0) return paths.slice(0, cap);
  const scored = paths.map((path, index) => {
    const lower = path.toLowerCase();
    const fileName = lower.slice(lower.lastIndexOf('/') + 1);
    let score = 0;
    for (const token of tokens) {
      if (fileName.includes(token)) score += 3;
      else if (lower.includes(token)) score += 1;
    }
    return { path, score, index };
  });
  // Stable sort by score desc (ties keep original order).
  scored.sort((a, b) => b.score - a.score || a.index - b.index);
  return scored.slice(0, cap).map((s) => s.path);
}

/** UTF-8-safe base64 decode (GitHub content API returns base64 with line breaks). */
export function b64DecodeUtf8(b64) {
  const cleaned = String(b64).replace(/[\r\n]/g, '');
  if (typeof Buffer !== 'undefined') {
    return Buffer.from(cleaned, 'base64').toString('utf-8');
  }
  const bin = atob(cleaned);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

/** Count non-overlapping occurrences of `sub` in `text`. */
export function occurrencesOf(text, sub) {
  if (!sub) return 0;
  let count = 0;
  let idx = text.indexOf(sub);
  while (idx >= 0) {
    count++;
    idx = text.indexOf(sub, idx + sub.length);
  }
  return count;
}
