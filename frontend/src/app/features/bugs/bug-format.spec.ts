import { blankToNull, categoryLabel, parseTags, severityLabel, statusLabel, timeAgo } from './bug-format';

describe('bug-format', () => {
  it('normalizes tags like the service does', () => {
    expect(parseTags('Checkout, UI flow ,checkout,, ')).toEqual(['checkout', 'ui-flow']);
    expect(parseTags('')).toEqual([]);
  });

  it('labels enum values', () => {
    expect(statusLabel('IN_PROGRESS')).toBe('In progress');
    expect(categoryLabel('PERFORMANCE')).toBe('Performance');
    expect(severityLabel('CRITICAL')).toBe('Critical');
  });

  it('formats relative times', () => {
    const now = Date.parse('2026-10-25T12:00:00Z');
    expect(timeAgo('2026-10-25T11:59:50Z', now)).toBe('just now');
    expect(timeAgo('2026-10-25T11:57:00Z', now)).toBe('3 minutes ago');
    expect(timeAgo('2026-10-24T12:00:00Z', now)).toBe('1 day ago');
  });

  it('turns blank text into null', () => {
    expect(blankToNull('   ')).toBeNull();
    expect(blankToNull(' a ')).toBe('a');
  });
});
