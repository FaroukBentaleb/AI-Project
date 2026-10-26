import { documentCode, formatDocstring, summaryLine, timeAgo } from './snippet-format';

describe('formatDocstring', () => {
  it('wraps Python docstrings in triple quotes', () => {
    expect(formatDocstring('Add.', 'python', '    ')).toBe('    """Add."""');
    expect(formatDocstring('Add.\n\nArgs:\n    a: x', 'python')).toBe('"""\nAdd.\n\nArgs:\n    a: x\n"""');
  });

  it('writes a doc comment for Java and JavaScript', () => {
    expect(formatDocstring('Add.\n@param a x', 'java', '  ')).toBe('  /**\n   * Add.\n   * @param a x\n   */');
    expect(formatDocstring('Add.', 'javascript')).toBe('/**\n * Add.\n */');
  });
});

describe('documentCode', () => {
  it('puts a Python docstring right after the signature, indented like the body', () => {
    const code = 'def add(a, b):\n    return a + b';
    expect(documentCode(code, 'python', 'Add two numbers.')).toBe(
      'def add(a, b):\n    """Add two numbers."""\n    return a + b'
    );
  });

  it('handles methods and signatures over several lines', () => {
    const code = '  def add(self,\n          a, b):\n      return a + b';
    expect(documentCode(code, 'python', 'Sum.\n\nArgs:\n    a: x')).toBe(
      '  def add(self,\n          a, b):\n      """\n      Sum.\n\n      Args:\n          a: x\n      """\n      return a + b'
    );
  });

  it('puts a doc comment above Java and JavaScript functions, keeping their indentation', () => {
    expect(documentCode('  public int size() {\n    return 1;\n  }', 'java', 'Size.')).toBe(
      '  /**\n   * Size.\n   */\n  public int size() {\n    return 1;\n  }'
    );
    expect(documentCode('function f() {}', 'javascript', 'Does f.')).toBe('/**\n * Does f.\n */\nfunction f() {}');
  });

  it('returns the code unchanged without a docstring', () => {
    expect(documentCode('def f(): pass', 'python', null)).toBe('def f(): pass');
    expect(documentCode('def f(): pass', 'python', '  ')).toBe('def f(): pass');
  });
});

describe('summaryLine', () => {
  it('is the first non-empty line', () => {
    expect(summaryLine('\n Adds numbers.\n\nArgs:')).toBe('Adds numbers.');
    expect(summaryLine(null)).toBe('');
  });
});

describe('timeAgo', () => {
  it('is relative for recent dates', () => {
    const now = Date.parse('2026-01-01T12:00:00Z');
    expect(timeAgo('2026-01-01T11:59:50Z', now)).toBe('just now');
    expect(timeAgo('2026-01-01T11:30:00Z', now)).toBe('30 min ago');
    expect(timeAgo('2026-01-01T09:00:00Z', now)).toBe('3 h ago');
  });
});
