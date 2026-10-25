import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { SqlApiService } from '../core/sql-api.service';

export const COPIED_MS = 2000;
export const COPY_FAILED_MESSAGE = 'Couldn’t copy — press Ctrl/⌘+C';

/**
 * One-click copy (FR28). Copies `text`, flips to "Copied ✓" for ~2 s and announces "Copied" politely.
 * With a `queryId`, records usage fire-and-forget: a failed usage call never blocks or undoes the copy.
 * If the clipboard is denied, emits `failed` (the host selects the SQL) and shows the fallback message.
 */
@Component({
  selector: 'sql-copy-button',
  standalone: true,
  template: `
    <button type="button" class="btn btn-sm btn-secondary copy-btn" [attr.aria-label]="label" (click)="copy()">
      {{ state() === 'copied' ? 'Copied ✓' : buttonText }}
    </button>
    <span class="sr-only copy-status" role="status" aria-live="polite">{{ state() === 'copied' ? 'Copied' : '' }}</span>
    @if (state() === 'failed') {
      <span class="copy-failed" role="alert">{{ failedMessage }}</span>
    }
  `,
  styles: [':host { display: inline-flex; align-items: center; gap: 8px; } .copy-failed { font-size: 12px; color: var(--sql-text-muted); }'],
  styleUrls: ['../shell/sql-theme.css']
})
export class CopyButtonComponent {
  @Input({ required: true }) text = '';
  @Input() queryId: number | null = null;
  @Input() buttonText = 'Copy';
  @Input() label: string | null = null;
  @Output() readonly copied = new EventEmitter<void>();
  @Output() readonly failed = new EventEmitter<void>();

  readonly failedMessage = COPY_FAILED_MESSAGE;
  readonly state = signal<'idle' | 'copied' | 'failed'>('idle');

  private readonly api = inject(SqlApiService);
  private readonly doc = inject(DOCUMENT);
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  async copy(): Promise<void> {
    const text = this.text;
    clearTimeout(this.timer);
    try {
      const clipboard = this.doc.defaultView?.navigator.clipboard;
      if (!clipboard) {
        throw new Error('Clipboard unavailable');
      }
      await clipboard.writeText(text);
    } catch {
      this.state.set('failed');
      this.failed.emit();
      return;
    }
    this.state.set('copied');
    this.timer = setTimeout(() => this.state.set('idle'), COPIED_MS);
    this.copied.emit();
    if (this.queryId != null) {
      // Fire-and-forget: the confirmation never waits on this, and a failure is logged silently.
      this.api.recordUsage(this.queryId).subscribe({ error: (err) => console.debug('[sql] usage not recorded', err) });
    }
  }
}
