import { relativeTime } from './relative-time';

describe('relativeTime', () => {
  const now = new Date('2026-10-19T12:00:00Z');

  it('formats past instants', () => {
    expect(relativeTime('2026-10-19T11:59:40Z', now)).toBe('just now');
    expect(relativeTime('2026-10-19T11:55:00Z', now)).toBe('5 minutes ago');
    expect(relativeTime('2026-10-18T12:00:00Z', now)).toBe('yesterday');
    expect(relativeTime('2026-10-16T12:00:00Z', now)).toBe('3 days ago');
    expect(relativeTime('2025-10-01T12:00:00Z', now)).toBe('last year');
  });

  it('returns empty for missing or invalid input', () => {
    expect(relativeTime(null, now)).toBe('');
    expect(relativeTime('nope', now)).toBe('');
  });
});
