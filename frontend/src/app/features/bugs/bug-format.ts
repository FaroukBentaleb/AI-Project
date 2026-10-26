import { Category, Severity, Status } from './bugs.models';

const STATUS_LABELS: Record<Status, string> = {
  OPEN: 'Open', IN_PROGRESS: 'In progress', RESOLVED: 'Resolved', CLOSED: 'Closed'
};

const CATEGORY_LABELS: Record<Category, string> = {
  UI: 'UI', FUNCTIONAL: 'Functional', PERFORMANCE: 'Performance', SECURITY: 'Security', CRASH: 'Crash',
  DATA: 'Data', INTEGRATION: 'Integration', OTHER: 'Other'
};

export function statusLabel(status: Status): string {
  return STATUS_LABELS[status] ?? status;
}

export function categoryLabel(category: Category): string {
  return CATEGORY_LABELS[category] ?? category;
}

export function severityLabel(severity: Severity): string {
  return severity.charAt(0) + severity.slice(1).toLowerCase();
}

/** "Checkout, UI flow ,ui" -> ["checkout", "ui-flow"]: same normalization as the service, so chips match. */
export function parseTags(text: string): string[] {
  const out: string[] = [];
  for (const raw of text.split(',')) {
    const tag = raw.trim().toLowerCase().replace(/\s+/g, '-');
    if (tag && !out.includes(tag)) {
      out.push(tag);
    }
  }
  return out;
}

/** "3 minutes ago"; `now` is injectable for tests. */
export function timeAgo(iso: string, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  const units: [number, string][] = [[31536000, 'year'], [2592000, 'month'], [86400, 'day'], [3600, 'hour'], [60, 'minute']];
  for (const [size, name] of units) {
    if (seconds >= size) {
      const n = Math.floor(seconds / size);
      return `${n} ${name}${n > 1 ? 's' : ''} ago`;
    }
  }
  return 'just now';
}

/** Null for blank text, so optional fields are stored as absent rather than "". */
export function blankToNull(text: string): string | null {
  const value = text.trim();
  return value ? value : null;
}
