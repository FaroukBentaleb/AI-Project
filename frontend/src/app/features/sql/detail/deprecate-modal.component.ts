import { AfterViewInit, Component, ElementRef, EventEmitter, Input, Output, ViewChild, computed, signal } from '@angular/core';
import { trapTab } from '../shared/focus-trap';
import { QueryRef, ReplacedByPickerComponent } from './replaced-by-picker.component';

/** UI-only limit (EXPERIENCE.md `[ASSUMPTION]`); the API allows 2000. */
export const REASON_MAX = 500;

export interface DeprecateSubmit {
  reason: string;
  replaced_by: number | null;
  replacement: QueryRef | null;
}

/** Errors the host maps from the API: inline on reason / replaced_by, or modal-level. */
export interface DeprecateErrors {
  reason?: string;
  replaced_by?: string;
  form?: string;
}

/** Deprecate modal (EXPERIENCE.md): required reason (/500), optional Replaced-by picker; the host owns the API call. */
@Component({
  selector: 'sql-deprecate-modal',
  standalone: true,
  imports: [ReplacedByPickerComponent],
  template: `
    <div class="scrim">
      <div #dialog class="modal deprecate-modal" role="dialog" aria-modal="true" aria-labelledby="sql-deprecate-title" (keydown)="onKeydown($event)">
        <h2 id="sql-deprecate-title" class="modal-title">Deprecate '{{ title }}'?</h2>
        <p class="muted">It stays in the library with a Deprecated badge and is removed from team examples.</p>
        <div class="field">
          <div class="label-row">
            <label class="label" for="sql-deprecate-reason">Reason</label>
            <span class="counter" [class.over]="reason().length > max">{{ reason().length }}/{{ max }}</span>
          </div>
          <textarea #reasonInput id="sql-deprecate-reason" class="text-input" rows="3" required
            [attr.aria-invalid]="!!(errors.reason || reasonProblem())"
            aria-describedby="sql-deprecate-reason-help"
            [value]="reason()" (input)="reason.set($any($event.target).value)"></textarea>
          <p id="sql-deprecate-reason-help" class="field-error" [class.hint]="!errors.reason && !reasonProblem()" role="status">
            {{ errors.reason || reasonProblem() || 'A reason is required.' }}
          </p>
        </div>
        <div class="field">
          <label class="label" for="sql-deprecate-replaced-by">Replaced by <span class="optional">(optional)</span></label>
          <sql-replaced-by-picker inputId="sql-deprecate-replaced-by" [excludeId]="queryId" [invalid]="!!errors.replaced_by"
            [selected]="replacement()" (selectedChange)="replacement.set($event)" />
          @if (errors.replaced_by) {
            <p class="field-error" role="alert">{{ errors.replaced_by }}</p>
          }
        </div>
        @if (errors.form) {
          <p class="modal-error" role="alert">{{ errors.form }}</p>
        }
        <div class="actions">
          <button type="button" class="btn btn-secondary" (click)="cancelled.emit()">Cancel</button>
          <button type="button" class="btn btn-primary confirm" [disabled]="!canSubmit() || busy" (click)="submit()">
            {{ busy ? 'Deprecating…' : 'Deprecate' }}
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `.deprecate-modal { width: min(520px, 92vw); }
     .field { margin-top: 12px; }
     .label-row { display: flex; justify-content: space-between; align-items: baseline; }
     .counter { font: 400 12px/1.4 var(--sql-font-code); color: var(--sql-text-muted); }
     .counter.over { color: var(--sql-badge-danger-text); }
     textarea.text-input { resize: vertical; }
     .field-error { margin: 4px 0 0; font-size: 12px; color: var(--sql-badge-danger-text); }
     .field-error.hint { color: var(--sql-text-muted); }
     .optional { font-weight: 400; color: var(--sql-text-muted); }`
  ],
  styleUrls: ['../shell/sql-theme.css']
})
export class DeprecateModalComponent implements AfterViewInit {
  @Input({ required: true }) title = '';
  @Input({ required: true }) queryId = 0;
  @Input() busy = false;
  @Input() errors: DeprecateErrors = {};
  @Input() set initialReplacement(r: QueryRef | null) {
    this.replacement.set(r);
  }
  @Output() readonly submitted = new EventEmitter<DeprecateSubmit>();
  @Output() readonly cancelled = new EventEmitter<void>();

  readonly max = REASON_MAX;
  readonly reason = signal('');
  readonly replacement = signal<QueryRef | null>(null);
  readonly reasonProblem = computed(() => (this.reason().length > REASON_MAX ? `Reason must be at most ${REASON_MAX} characters.` : null));
  readonly canSubmit = computed(() => !!this.reason().trim() && !this.reasonProblem());

  @ViewChild('reasonInput', { static: true }) private reasonInput!: ElementRef<HTMLTextAreaElement>;
  @ViewChild('dialog', { static: true }) private dialog!: ElementRef<HTMLElement>;

  ngAfterViewInit(): void {
    this.reasonInput.nativeElement.focus();
  }

  submit(): void {
    if (!this.canSubmit() || this.busy) {
      return;
    }
    const r = this.replacement();
    this.submitted.emit({ reason: this.reason().trim(), replaced_by: r?.id ?? null, replacement: r });
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (!this.busy) {
        this.cancelled.emit();
      }
    } else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.submit();
    } else {
      trapTab(event, this.dialog.nativeElement);
    }
  }
}
