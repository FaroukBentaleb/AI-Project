const UNITS: ReadonlyArray<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['week', 7 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60]
];

const FORMAT = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

/** "3 days ago", "yesterday", "just now". Invalid input returns ''. */
export function relativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) {
    return '';
  }
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) {
    return '';
  }
  const seconds = Math.round((t - now.getTime()) / 1000);
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) {
      return FORMAT.format(Math.trunc(seconds / size), unit);
    }
  }
  return 'just now';
}
