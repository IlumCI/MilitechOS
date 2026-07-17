// Cheap, local structural checks on the final content of every edited file.
// These run before the critic model call — a malformed file should never cost tokens.

/** Returns null when the content passes, or a human-readable error string. */
export function validateEdit(path, content) {
  if (!content || !content.trim()) {
    return 'file would be left empty';
  }
  if (path.endsWith('.json')) {
    try {
      JSON.parse(content);
    } catch (e) {
      return `invalid JSON: ${String(e.message).split('\n')[0]}`;
    }
  }
  if (path.endsWith('.yml') || path.endsWith('.yaml')) {
    const lines = content.split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].startsWith('\t')) {
        return `YAML uses tab indentation at line ${i + 1} (tabs are illegal in YAML)`;
      }
    }
  }
  return null;
}
