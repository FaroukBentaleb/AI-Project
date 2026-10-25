import { parseDiff } from './diff-parser';

const ADDED = [
  'diff --git a/about.html b/about.html',
  '--- /dev/null',
  '+++ b/about.html',
  '@@ -0,0 +1,2 @@',
  '+<h1>About</h1>',
  '+<p>Hi</p>'
].join('\n');

const MODIFIED = [
  'diff --git a/index.html b/index.html',
  '--- a/index.html',
  '+++ b/index.html',
  '@@ -6,3 +6,3 @@',
  ' <body>',
  '-  <h1>Hello</h1>',
  '+  <h1>Welcome</h1>',
  ' </body>'
].join('\n');

const DELETED = ['diff --git a/old.txt b/old.txt', '--- a/old.txt', '+++ /dev/null', '@@ -1,1 +0,0 @@', '-bye'].join('\n');

describe('parseDiff', () => {
  it('reads an added file with numbered lines', () => {
    const [file] = parseDiff(ADDED);
    expect(file.path).toBe('about.html');
    expect(file.status).toBe('added');
    expect(file.additions).toBe(2);
    expect(file.deletions).toBe(0);
    expect(file.lines.filter((l) => l.kind === 'add').map((l) => l.newNo)).toEqual([1, 2]);
  });

  it('tracks old and new line numbers across context, removals and additions', () => {
    const [file] = parseDiff(MODIFIED);
    expect(file.status).toBe('modified');
    expect(file.lines.map((l) => [l.kind, l.oldNo, l.newNo])).toEqual([
      ['hunk', null, null],
      ['ctx', 6, 6],
      ['del', 7, null],
      ['add', null, 7],
      ['ctx', 8, 8]
    ]);
  });

  it('marks a deleted file', () => {
    const [file] = parseDiff(DELETED);
    expect(file.status).toBe('deleted');
    expect(file.deletions).toBe(1);
  });

  it('splits several files and does not mistake content that looks like a header', () => {
    const tricky = [
      'diff --git a/a.sql b/a.sql', '--- a/a.sql', '+++ b/a.sql', '@@ -1,2 +1,2 @@',
      '--- a comment line starting with dashes', '+++ not a header either'
    ].join('\n');
    const files = parseDiff([ADDED, tricky].join('\n'));
    expect(files.map((f) => f.path)).toEqual(['about.html', 'a.sql']);
    expect(files[1].deletions).toBe(1);
    expect(files[1].additions).toBe(1);
  });

  it('returns nothing for text without a diff header', () => {
    expect(parseDiff('')).toEqual([]);
    expect(parseDiff('just some text')).toEqual([]);
  });
});
