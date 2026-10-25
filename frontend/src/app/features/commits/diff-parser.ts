export type DiffLineKind = 'add' | 'del' | 'ctx' | 'hunk' | 'meta';
export type FileStatus = 'added' | 'deleted' | 'modified';

export interface DiffLine {
  kind: DiffLineKind;
  text: string;
  oldNo: number | null;
  newNo: number | null;
}

export interface DiffFile {
  path: string;
  status: FileStatus;
  additions: number;
  deletions: number;
  lines: DiffLine[];
}

const HUNK = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

/** Splits the unified diff produced by commit-service into one entry per file, with line numbers for both sides. */
export function parseDiff(diff: string): DiffFile[] {
  const files: DiffFile[] = [];
  let file: DiffFile | null = null;
  let inHunk = false;
  let oldNo = 0;
  let newNo = 0;

  for (const raw of diff.split('\n')) {
    const header = /^diff --git a\/(.+?) b\/(.+)$/.exec(raw);
    if (header) {
      file = { path: header[2], status: 'modified', additions: 0, deletions: 0, lines: [] };
      files.push(file);
      inHunk = false;
      continue;
    }
    if (!file) {
      continue;
    }
    if (!inHunk && raw.startsWith('--- ')) {
      if (raw === '--- /dev/null') {
        file.status = 'added';
      }
      continue;
    }
    if (!inHunk && raw.startsWith('+++ ')) {
      if (raw === '+++ /dev/null') {
        file.status = 'deleted';
      }
      continue;
    }
    const hunk = HUNK.exec(raw);
    if (hunk) {
      inHunk = true;
      oldNo = Number(hunk[1]);
      newNo = Number(hunk[2]);
      file.lines.push({ kind: 'hunk', text: raw, oldNo: null, newNo: null });
    } else if (inHunk && raw.startsWith('+')) {
      file.additions++;
      file.lines.push({ kind: 'add', text: raw.slice(1), oldNo: null, newNo: newNo++ });
    } else if (inHunk && raw.startsWith('-')) {
      file.deletions++;
      file.lines.push({ kind: 'del', text: raw.slice(1), oldNo: oldNo++, newNo: null });
    } else if (inHunk && raw.startsWith(' ')) {
      file.lines.push({ kind: 'ctx', text: raw.slice(1), oldNo: oldNo++, newNo: newNo++ });
    } else if (raw.trim() !== '') {
      file.lines.push({ kind: 'meta', text: raw, oldNo: null, newNo: null });
    }
  }
  return files;
}
