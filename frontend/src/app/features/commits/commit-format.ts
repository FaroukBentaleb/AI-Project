export const COMMIT_TYPES = ['feat', 'fix', 'docs', 'style', 'refactor', 'test', 'chore'] as const;
export type CommitType = (typeof COMMIT_TYPES)[number];

export const MAX_ZIP_BYTES = 10 * 1024 * 1024;

/** "feat(ui): add page" -> { type: 'feat', subject: 'add page' }; anything else keeps the whole text as subject. */
export function splitMessage(message: string): { type: CommitType | null; subject: string } {
  const match = /^(feat|fix|docs|style|refactor|test|chore)(\([^)]*\))?!?:\s*(.*)$/s.exec(message.trim());
  return match ? { type: match[1] as CommitType, subject: match[3] } : { type: null, subject: message.trim() };
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function timeAgo(iso: string, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (seconds < 45) {
    return 'just now';
  }
  if (seconds < 3600) {
    return `${Math.round(seconds / 60)} min ago`;
  }
  if (seconds < 86400) {
    return `${Math.round(seconds / 3600)} h ago`;
  }
  return seconds < 30 * 86400 ? `${Math.round(seconds / 86400)} d ago` : new Date(iso).toLocaleDateString();
}

/** The shell command that commits with this message (single quotes are escaped for sh). */
export function gitCommand(message: string): string {
  return `git commit -m '${message.replace(/'/g, `'\\''`)}'`;
}

/** Why a dropped/picked file cannot be used, or null when it is fine. */
export function zipProblem(file: { name: string; size: number }): string | null {
  if (!file.name.toLowerCase().endsWith('.zip')) {
    return 'Only .zip files are supported.';
  }
  if (file.size === 0) {
    return 'That ZIP is empty.';
  }
  if (file.size > MAX_ZIP_BYTES) {
    return `That ZIP is ${formatBytes(file.size)}; the limit is ${formatBytes(MAX_ZIP_BYTES)}.`;
  }
  return null;
}
