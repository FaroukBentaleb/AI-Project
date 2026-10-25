import { MAX_ZIP_BYTES, formatBytes, gitCommand, splitMessage, timeAgo, zipProblem } from './commit-format';

describe('splitMessage', () => {
  it('separates the conventional type from the subject', () => {
    expect(splitMessage('feat: add about page')).toEqual({ type: 'feat', subject: 'add about page' });
    expect(splitMessage('fix(ui): center the logo')).toEqual({ type: 'fix', subject: 'center the logo' });
  });

  it('keeps free text whole', () => {
    expect(splitMessage('  Add about page ')).toEqual({ type: null, subject: 'Add about page' });
    expect(splitMessage('wip: something')).toEqual({ type: null, subject: 'wip: something' });
  });
});

describe('zipProblem', () => {
  it('accepts a normal zip', () => {
    expect(zipProblem({ name: 'Site.ZIP', size: 1000 })).toBeNull();
  });

  it('rejects other types, empty files and oversized files', () => {
    expect(zipProblem({ name: 'notes.txt', size: 10 })).toContain('.zip');
    expect(zipProblem({ name: 'a.zip', size: 0 })).toContain('empty');
    expect(zipProblem({ name: 'a.zip', size: MAX_ZIP_BYTES + 1 })).toContain('limit');
  });
});

describe('helpers', () => {
  it('formats sizes', () => {
    expect(formatBytes(500)).toBe('500 B');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
  });

  it('describes elapsed time', () => {
    const now = Date.parse('2026-10-25T12:00:00Z');
    expect(timeAgo('2026-10-25T11:59:50Z', now)).toBe('just now');
    expect(timeAgo('2026-10-25T11:45:00Z', now)).toBe('15 min ago');
    expect(timeAgo('2026-10-25T09:00:00Z', now)).toBe('3 h ago');
    expect(timeAgo('2026-10-22T12:00:00Z', now)).toBe('3 d ago');
  });

  it('quotes the message for a shell command', () => {
    expect(gitCommand("fix: don't crash")).toBe("git commit -m 'fix: don'\\''t crash'");
  });
});
