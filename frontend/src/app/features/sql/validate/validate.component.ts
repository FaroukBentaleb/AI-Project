import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { toUiError } from '../core/error-mapper';
import { resultHeader } from '../core/read-only';
import { SqlSessionState } from '../core/session-state.service';
import { SqlApiService } from '../core/sql-api.service';
import { SAVE_LIMITS, SQL_COUNTER_FROM } from '../save/save-form';
import { CopyButtonComponent } from '../shared/copy-button.component';

export const VALIDATE_EXPLAINER = 'Checks the statement structure — not whether it’s correct or safe on your schema.';

/**
 * Validate screen (FR32, EXPERIENCE.md screen 05): paste SQL → Validation Result and the server Safety Note.
 * Input and result live in module session state, so they survive in-app navigation.
 */
@Component({
  selector: 'sql-validate',
  standalone: true,
  imports: [CopyButtonComponent],
  template: `
    <section class="validate-page">
      <h1 class="page-title">Validate</h1>
      <p class="muted explainer">{{ explainer }}</p>

      <div class="field">
        <div class="label-row">
          <label class="label" for="sql-validate-input">SQL</label>
          @if (input().length > counterFrom) {
            <span class="counter" [class.over]="overLimit()">{{ input().length }}/{{ limit }}</span>
          }
        </div>
        <textarea #area id="sql-validate-input" class="sql-textarea" rows="8" spellcheck="false"
          placeholder="Paste a SQL statement…"
          [class.invalid]="overLimit() || !!fieldError()" [attr.aria-invalid]="overLimit() || !!fieldError()"
          [attr.aria-describedby]="fieldError() ? 'sql-validate-error' : null"
          [value]="input()" (input)="onInput($event)" (keydown)="onKeydown($event)"></textarea>
        @if (fieldError(); as e) { <p id="sql-validate-error" class="field-error" role="alert">{{ e }}</p> }
      </div>

      <div class="submit-row">
        <button type="button" class="btn btn-primary validate-btn" [disabled]="!canSubmit()" (click)="submit()">
          {{ validating() ? 'Validating…' : 'Validate' }}
        </button>
        <span class="hint">Ctrl/⌘+Enter</span>
      </div>

      @if (error(); as msg) {
        <div class="banner banner-danger validate-error" role="alert">
          <span class="banner-glyph" aria-hidden="true">✕</span><span>{{ msg }}</span>
        </div>
      }

      @if (result(); as r) {
        <section class="result" aria-label="Validation result">
          <h2 #resultHeader class="result-header" tabindex="-1" [class.accepted]="r.accepted" [class.rejected]="!r.accepted">
            {{ headerText() }}
          </h2>

          @if (r.rejections.length) {
            <ul class="reason-list rejections">
              @for (x of r.rejections; track $index) {
                <li><span class="code-chip code-chip-rejection">{{ x.code }}</span><span>{{ x.reason }}</span></li>
              }
            </ul>
          }

          @if (r.warnings.length) {
            <div class="warnings">
              <h3 class="sub-title warnings-title">Warnings ({{ r.warnings.length }})</h3>
              <ul class="reason-list">
                @for (w of r.warnings; track $index) {
                  <li><span class="code-chip code-chip-warning" aria-hidden="true">!</span><span>{{ w }}</span></li>
                }
              </ul>
            </div>
          }

          <div class="normalized">
            <div class="normalized-head">
              <h3 class="sub-title">Normalized SQL</h3>
              @if (r.accepted) {
                <div class="result-actions">
                  <sql-copy-button [text]="r.normalized_sql" label="Copy normalized SQL" />
                  <button type="button" class="btn btn-sm btn-secondary save-btn" (click)="saveToLibrary()">Save to library</button>
                </div>
              }
            </div>
            <pre class="code-block normalized-sql" aria-label="Normalized SQL"><code>{{ r.normalized_sql }}</code></pre>
          </div>
        </section>

        <p class="safety-note" role="note"><strong>Safety note.</strong> {{ r.safety_note }}</p>
      }
    </section>
  `,
  styleUrls: ['../shell/sql-theme.css', './validate.component.css']
})
export class ValidateComponent {
  private readonly api = inject(SqlApiService);
  private readonly session = inject(SqlSessionState);
  private readonly router = inject(Router);

  readonly explainer = VALIDATE_EXPLAINER;
  readonly limit = SAVE_LIMITS.sql_content;
  readonly counterFrom = SQL_COUNTER_FROM;

  readonly input = this.session.validateInput;
  readonly result = this.session.validateResult;
  readonly validating = signal(false);
  readonly error = signal<string | null>(null);
  readonly fieldError = signal<string | null>(null);

  readonly overLimit = computed(() => this.input().length > this.limit);
  readonly canSubmit = computed(() => !this.validating() && this.input().trim().length > 0 && !this.overLimit());
  readonly headerText = computed(() => {
    const r = this.result();
    return r ? resultHeader(r.accepted, r.rejections) : '';
  });

  private readonly headerEl = viewChild<ElementRef<HTMLElement>>('resultHeader');
  private escBeforeTab = false;

  onInput(event: Event): void {
    this.input.set((event.target as HTMLTextAreaElement).value);
    this.fieldError.set(null);
  }

  /** Ctrl/⌘+Enter submits; Tab inserts two spaces; Esc then Tab leaves the field. */
  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.submit();
      return;
    }
    if (event.key === 'Escape') {
      this.escBeforeTab = true;
      return;
    }
    const plainTab = event.key === 'Tab' && !event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey;
    if (plainTab && !this.escBeforeTab) {
      event.preventDefault();
      const el = event.target as HTMLTextAreaElement;
      const start = el.selectionStart;
      const value = el.value.slice(0, start) + '  ' + el.value.slice(el.selectionEnd);
      el.value = value;
      el.selectionStart = el.selectionEnd = start + 2;
      this.input.set(value);
    }
    this.escBeforeTab = false;
  }

  submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    // A new run replaces the previous result; a failed run never leaves an old one on screen.
    this.validating.set(true);
    this.result.set(null);
    this.error.set(null);
    this.fieldError.set(null);
    this.api.validate(this.input()).subscribe({
      next: (r) => {
        this.validating.set(false);
        this.result.set(r);
        setTimeout(() => this.headerEl()?.nativeElement.focus());
      },
      error: (err) => {
        this.validating.set(false);
        const ui = toUiError(err);
        if (ui.code === 'VALIDATION_FAILED') {
          this.fieldError.set(ui.message);
        } else {
          this.error.set(ui.message);
        }
      }
    });
  }

  /** Save form prefill 2 (EXPERIENCE.md): the normalized SQL, not LLM-generated. */
  saveToLibrary(): void {
    const r = this.result();
    if (!r?.accepted) {
      return;
    }
    this.session.offerPrefill('validate', { sql_content: r.normalized_sql, is_llm_generated: false });
    void this.router.navigate(['/sql/queries/new'], { queryParams: { from: 'validate' } });
  }
}
