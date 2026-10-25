import { Component, DestroyRef, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { SqlSessionState } from '../core/session-state.service';
import { SqlApiService } from '../core/sql-api.service';
import { CopyButtonComponent } from '../shared/copy-button.component';
import { SAVE_LIMITS } from '../save/save-form';
import { generateError, retrySecondsLeft } from './generate-error';

export const PROMPT_LIMIT = 1000;
export const PERSONAL_DATA_NOTICE =
  'Don’t include personal data — prompts and team examples are sent to an external AI model.';
export const GENERATING_TEXT = 'Finding similar team queries, then generating…';
export const NO_EXAMPLES_TEXT = 'No team examples were used.';
const MAX_ROWS = 8;

export type ResultTab = 'sql' | 'examples' | 'warnings';
export const RESULT_TABS: readonly ResultTab[] = ['sql', 'examples', 'warnings'];

/**
 * Generate screen (FR29, EXPERIENCE.md screen 04): NL prompt → Generated SQL, Examples, Warnings and the
 * server Safety Note. Prompt, result and error live in module session state. Save (7.2) and the error
 * actions (7.3) plug into the SQL tab action row and the error banner.
 */
@Component({
  selector: 'sql-generate',
  standalone: true,
  imports: [CopyButtonComponent, RouterLink],
  template: `
    <section class="generate-page">
      <h1 class="page-title">Generate</h1>

      <div class="command-bar">
        <label class="sr-only" for="sql-generate-prompt">Prompt</label>
        <textarea #promptArea id="sql-generate-prompt" class="prompt" spellcheck="true"
          placeholder="Describe the query you need, e.g. weekly orders by region for the last 4 weeks"
          [rows]="rows()" [readOnly]="generating()"
          [class.invalid]="overLimit() || !!inlineError()" [attr.aria-invalid]="overLimit() || !!inlineError()"
          [attr.aria-describedby]="'sql-generate-notice sql-generate-counter' + (inlineError() ? ' sql-generate-error' : '')"
          [value]="prompt()" (input)="onInput($event)" (keydown)="onKeydown($event)"></textarea>
        @if (inlineError(); as e) { <p id="sql-generate-error" class="field-error" role="alert">{{ e }}</p> }
        <div class="command-foot">
          <div class="command-meta">
            <p id="sql-generate-notice" class="notice">{{ notice }}</p>
            <span id="sql-generate-counter" class="counter" [class.over]="overLimit()">{{ prompt().length }}/{{ limit }}</span>
          </div>
          <div class="command-actions">
            <span class="hint">Ctrl/⌘+Enter</span>
            <button type="button" class="btn btn-primary generate-btn" [disabled]="!canSubmit()" (click)="submit()">
              {{ generating() ? 'Generating…' : 'Generate' }}
            </button>
          </div>
        </div>
      </div>

      <p class="generating" role="status" aria-live="polite">{{ generating() ? generatingText : '' }}</p>

      @if (error(); as e) {
        @if (e.kind !== 'inline') {
          <div class="banner generate-error" role="alert"
            [class.banner-danger]="e.kind === 'danger'" [class.banner-warning]="e.kind === 'warning'">
            <span class="banner-glyph" aria-hidden="true">{{ e.kind === 'danger' ? '✕' : '!' }}</span>
            <span class="error-message">{{ e.message }}</span>
            @if (e.canSaveManually || e.canRetry || e.canRephrase) {
              <span class="error-actions">
                @if (e.canSaveManually) {
                  <button type="button" class="btn btn-sm btn-secondary manual-save-btn" (click)="saveManually()">Save a query manually</button>
                }
                @if (e.canRetry) {
                  <button type="button" class="btn btn-sm btn-secondary retry-btn"
                    [disabled]="retryLeft() > 0 || !canSubmit()" (click)="submit()">
                    {{ retryLeft() > 0 ? 'Retry in ~' + retryLeft() + 's' : 'Retry' }}
                  </button>
                }
                @if (e.canRephrase) {
                  <button type="button" class="btn btn-sm btn-secondary rephrase-btn" (click)="focusPrompt()">Rephrase and retry</button>
                }
              </span>
            }
          </div>
          @if (e.code === 'SQL_REJECTED') {
            <section class="rejected" aria-labelledby="sql-generate-rejected-title">
              <h2 id="sql-generate-rejected-title" class="result-title">Rejected SQL</h2>
              @if (e.rejectedSql) {
                <pre class="code-block rejected-sql" aria-label="Rejected SQL, for reference only"><code>{{ e.rejectedSql }}</code></pre>
              }
              @if (e.reasons.length) {
                <ul class="reason-list rejected-reasons">
                  @for (x of e.reasons; track $index) {
                    <li><span class="code-chip code-chip-rejection">{{ x.code }}</span><span>{{ x.reason }}</span></li>
                  }
                </ul>
              }
            </section>
            @if (e.safetyNote) {
              <p class="safety-note" role="note"><strong>Safety note.</strong> {{ e.safetyNote }}</p>
            }
          }
        }
      }

      @if (result(); as r) {
        <div class="result-wrap" [class.dimmed]="generating()" [attr.aria-busy]="generating()">
          <section class="result" aria-labelledby="sql-generate-result-title">
            <h2 #resultHeading id="sql-generate-result-title" class="result-title" tabindex="-1">Generated SQL</h2>
            <div class="result-tabs" role="tablist" aria-label="Result">
              @for (t of tabs; track t) {
                <button type="button" role="tab" class="result-tab" [id]="'gen-tab-' + t"
                  [attr.aria-controls]="'gen-panel-' + t" [attr.aria-selected]="activeTab() === t"
                  [attr.aria-label]="tabAriaLabel(t)"
                  [tabIndex]="activeTab() === t ? 0 : -1" [class.active]="activeTab() === t"
                  [class.has-warnings]="t === 'warnings' && r.warnings.length > 0"
                  (click)="selectTab(t)" (keydown)="onTabKeydown($event)">{{ tabLabel(t) }}</button>
              }
            </div>

            @switch (activeTab()) {
              @case ('sql') {
                <div class="panel" role="tabpanel" id="gen-panel-sql" aria-labelledby="gen-tab-sql">
                  <div class="panel-actions">
                    <sql-copy-button [text]="r.sql" label="Copy generated SQL" />
                    <button type="button" class="btn btn-sm btn-secondary save-btn" [disabled]="generating()" (click)="saveGenerated()">Save to library</button>
                  </div>
                  <pre class="code-block generated-sql" aria-label="Generated SQL"><code>{{ r.sql }}</code></pre>
                </div>
              }
              @case ('examples') {
                <div class="panel" role="tabpanel" id="gen-panel-examples" aria-labelledby="gen-tab-examples">
                  @if (r.examples_used.length) {
                    <ul class="examples-list">
                      @for (x of r.examples_used; track x.id) {
                        <li><a class="example-link" [routerLink]="['/sql/queries', x.id]">{{ x.title }}</a></li>
                      }
                    </ul>
                  } @else {
                    <p class="muted no-examples">{{ noExamples }}</p>
                  }
                </div>
              }
              @case ('warnings') {
                <div class="panel" role="tabpanel" id="gen-panel-warnings" aria-labelledby="gen-tab-warnings">
                  @if (r.warnings.length) {
                    <ul class="reason-list warning-list">
                      @for (w of r.warnings; track $index) {
                        <li class="warning-row"><span class="code-chip code-chip-warning" aria-hidden="true">!</span><span>{{ w }}</span></li>
                      }
                    </ul>
                  } @else {
                    <p class="muted">No warnings.</p>
                  }
                </div>
              }
            }
          </section>

          <p class="safety-note" role="note"><strong>Safety note.</strong> {{ r.safety_note }}</p>
        </div>
      }
    </section>
  `,
  styleUrls: ['../shell/sql-theme.css', './generate.component.css']
})
export class GenerateComponent implements OnInit {
  private readonly api = inject(SqlApiService);
  private readonly session = inject(SqlSessionState);
  private readonly router = inject(Router);
  /** Router `history.state` of the navigation that created this screen (Library handoff). */
  private readonly navState = this.router.getCurrentNavigation()?.extras.state ?? null;

  readonly limit = PROMPT_LIMIT;
  readonly notice = PERSONAL_DATA_NOTICE;
  readonly generatingText = GENERATING_TEXT;
  readonly noExamples = NO_EXAMPLES_TEXT;
  readonly tabs = RESULT_TABS;

  readonly prompt = this.session.generatePrompt;
  readonly result = this.session.generateResult;
  readonly error = this.session.generateError;
  readonly generating = signal(false);
  readonly activeTab = signal<ResultTab>('sql');

  readonly overLimit = computed(() => this.prompt().length > this.limit);
  readonly canSubmit = computed(() => !this.generating() && this.prompt().trim().length > 0 && !this.overLimit());
  readonly inlineError = computed(() => {
    const e = this.error();
    return e?.kind === 'inline' ? e.message : null;
  });
  readonly rows = computed(() => Math.min(MAX_ROWS, Math.max(1, this.prompt().split('\n').length)));

  /** Clock for the *Retry in ~Ns* countdown; ticks once a second only while a countdown is running. */
  private readonly now = signal(Date.now());
  readonly retryLeft = computed(() => {
    const e = this.error();
    return e ? retrySecondsLeft(e, this.now()) : 0;
  });
  private ticker: ReturnType<typeof setInterval> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopTicker());
  }

  /** Starts the countdown tick when the current error has a pending retry hint (new error or session restore). */
  private startTicker(): void {
    this.now.set(Date.now());
    if (this.ticker || this.retryLeft() === 0) {
      return;
    }
    this.ticker = setInterval(() => {
      this.now.set(Date.now());
      if (this.retryLeft() === 0) {
        this.stopTicker();
      }
    }, 1000);
  }

  private stopTicker(): void {
    if (this.ticker) {
      clearInterval(this.ticker);
      this.ticker = null;
    }
  }

  private readonly headingEl = viewChild<ElementRef<HTMLElement>>('resultHeading');
  private readonly promptEl = viewChild<ElementRef<HTMLTextAreaElement>>('promptArea');

  ngOnInit(): void {
    this.startTicker();
    // Library "Generate '<q>'" hands the term over in history.state: prefilled, never submitted.
    const handed = (this.navState as { prompt?: unknown } | null)?.prompt;
    if (typeof handed === 'string' && handed.trim()) {
      this.prompt.set(handed.slice(0, this.limit));
      this.error.set(null);
    }
  }

  onInput(event: Event): void {
    this.prompt.set((event.target as HTMLTextAreaElement).value);
    if (this.error()?.kind === 'inline') {
      this.error.set(null);
    }
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.submit();
    }
  }

  submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    // The previous result stays visible (dimmed) until it is replaced; a failure clears it.
    this.generating.set(true);
    this.error.set(null);
    this.api.generate(this.prompt()).subscribe({
      next: (r) => {
        this.generating.set(false);
        this.result.set(r);
        this.activeTab.set('sql');
        setTimeout(() => this.headingEl()?.nativeElement.focus());
      },
      error: (err) => {
        this.generating.set(false);
        this.result.set(null);
        const now = Date.now();
        this.now.set(now);
        this.error.set(generateError(err, now));
        this.startTicker();
      }
    });
  }

  /**
   * Save form prefill 1 (EXPERIENCE.md): the SQL and the original prompt, is_llm_generated = true.
   * Handed over one-shot via session state; the Generate prompt and result stay intact for Cancel/Back.
   */
  saveGenerated(): void {
    const r = this.result();
    if (!r || this.generating()) {
      return;
    }
    const original = (r.original_prompt || this.prompt()).slice(0, SAVE_LIMITS.original_nl_prompt);
    this.session.offerPrefill('generate', {
      sql_content: r.sql,
      is_llm_generated: true,
      original_nl_prompt: original.trim() ? original : null
    });
    void this.router.navigate(['/sql/queries/new'], { queryParams: { from: 'generate' } });
  }

  /**
   * Save form prefill 3 (EXPERIENCE.md, outage): the prompt goes into Notes, *More options* expanded,
   * is_llm_generated = false. One-shot via session state; Generate state stays intact for Cancel.
   */
  saveManually(): void {
    const notes = this.prompt().slice(0, SAVE_LIMITS.notes);
    this.session.offerPrefill('manual', { notes, is_llm_generated: false, original_nl_prompt: null });
    void this.router.navigate(['/sql/queries/new'], { queryParams: { from: 'manual' } });
  }

  /** Focuses the prompt (*Rephrase and retry*). */
  focusPrompt(): void {
    this.promptEl()?.nativeElement.focus();
  }

  tabLabel(t: ResultTab): string {
    const r = this.result();
    switch (t) {
      case 'sql':
        return 'SQL';
      case 'examples':
        return `Examples (${r?.examples_used.length ?? 0})`;
      default:
        return `Warnings (${r?.warnings.length ?? 0})`;
    }
  }

  tabAriaLabel(t: ResultTab): string {
    if (t === 'sql') {
      return 'SQL';
    }
    const r = this.result();
    const n = t === 'examples' ? r?.examples_used.length ?? 0 : r?.warnings.length ?? 0;
    return `${t === 'examples' ? 'Examples' : 'Warnings'}, ${n} ${n === 1 ? 'item' : 'items'}`;
  }

  selectTab(t: ResultTab): void {
    this.activeTab.set(t);
  }

  /** ARIA tabs, automatic activation: arrows move and select (wrapping), Home and End jump. */
  onTabKeydown(event: KeyboardEvent): void {
    const i = this.tabs.indexOf(this.activeTab());
    const n = this.tabs.length;
    const next: Record<string, number> = { ArrowRight: (i + 1) % n, ArrowLeft: (i - 1 + n) % n, Home: 0, End: n - 1 };
    if (!(event.key in next)) {
      return;
    }
    event.preventDefault();
    const t = this.tabs[next[event.key]];
    this.activeTab.set(t);
    const host = (event.target as HTMLElement).parentElement;
    setTimeout(() => host?.querySelector<HTMLElement>(`#gen-tab-${t}`)?.focus());
  }
}
