import { Language } from './snippets.models';

export const LANGUAGES: { id: Language; label: string }[] = [
  { id: 'python', label: 'Python' },
  { id: 'java', label: 'Java' },
  { id: 'javascript', label: 'JavaScript' }
];

export const SAMPLES: Record<Language, string> = {
  python: 'def average(values, ignore_none=False):\n    if ignore_none:\n        values = [v for v in values if v is not None]\n    return sum(values) / len(values)\n',
  java: 'public int countWords(String text, boolean ignoreCase) {\n    String value = ignoreCase ? text.toLowerCase() : text;\n    return value.trim().split("\\\\s+").length;\n}\n',
  javascript: 'function debounce(fn, delay) {\n  let timer;\n  return (...args) => {\n    clearTimeout(timer);\n    timer = setTimeout(() => fn(...args), delay);\n  };\n}\n'
};

/** Docstring text -> comment block in the language's syntax (mirrors `format_docstring` in snippet_prompt.py). */
export function formatDocstring(text: string, language: Language, indent = ''): string {
  const lines = text.trim().split('\n');
  if (language === 'python') {
    if (lines.length === 1) {
      return `${indent}"""${lines[0]}"""`;
    }
    const body = lines.map((line) => `${indent}${line}`.trimEnd()).join('\n');
    return `${indent}"""\n${body}\n${indent}"""`;
  }
  const body = lines.map((line) => `${indent} * ${line}`.trimEnd()).join('\n');
  return `${indent}/**\n${body}\n${indent} */`;
}

function indentOf(line: string): string {
  return /^\s*/.exec(line)![0];
}

/**
 * The code with its docstring in place: right after the signature for Python, in a doc comment above the function
 * for Java and JavaScript. Code without a docstring is returned unchanged.
 */
export function documentCode(code: string, language: Language, docstring: string | null): string {
  if (!docstring?.trim()) {
    return code;
  }
  const lines = code.split('\n');
  if (language !== 'python') {
    const first = lines.findIndex((line) => line.trim() !== '');
    const at = Math.max(0, first);
    return [...lines.slice(0, at), formatDocstring(docstring, language, indentOf(lines[at] ?? '')), ...lines.slice(at)]
      .join('\n');
  }
  const def = lines.findIndex((line) => /^\s*(async\s+)?def\s/.test(line));
  if (def < 0) {
    return `${formatDocstring(docstring, language)}\n${code}`;
  }
  let end = def;
  while (end < lines.length - 1 && !lines[end].trimEnd().endsWith(':')) {
    end++; // signature spread over several lines
  }
  const next = lines.slice(end + 1).find((line) => line.trim() !== '');
  const body = next && indentOf(next).length > indentOf(lines[def]).length ? indentOf(next) : indentOf(lines[def]) + '    ';
  return [...lines.slice(0, end + 1), formatDocstring(docstring, language, body), ...lines.slice(end + 1)].join('\n');
}

/** First non-empty line of a docstring, for list rows. */
export function summaryLine(docstring: string | null): string {
  return (docstring ?? '').split('\n').find((line) => line.trim() !== '')?.trim() ?? '';
}

export function languageLabel(language: Language): string {
  return LANGUAGES.find((l) => l.id === language)?.label ?? language;
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
  return new Date(iso).toLocaleDateString();
}
