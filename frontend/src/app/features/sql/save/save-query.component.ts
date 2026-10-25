import { NgTemplateOutlet } from '@angular/common';
import { Component, ElementRef, HostListener, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, CanDeactivateFn, Router, RouterLink } from '@angular/router';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { ERROR_MESSAGES, toUiError } from '../core/error-mapper';
import { InAppHistory } from '../core/in-app-history.service';
import { SavedQuery, SimilarQuery, SimilarQueryDraft } from '../core/models';
import { SqlApiService } from '../core/sql-api.service';
import { SqlSessionState } from '../core/session-state.service';
import { DiscardModalComponent } from '../shared/discard-modal.component';
import { TagsInputComponent } from '../shared/tags-input.component';
import { ToastService } from '../shared/toast.service';
import {
  DEFAULT_DIALECT,
  FORM_FIELDS,
  FieldErrors,
  FieldMarkers,
  FormField,
  MORE_FIELDS,
  SAVE_LIMITS,
  SQL_COUNTER_FROM,
  SaveDraft,
  draftFromQuery,
  emptyDraft,
  firstInvalid,
  mergeOnReload,
  moreSummary,
  mutationToast,
  sameDraft,
  toCreateRequest,
  toPatchRequest,
  validateDraft,
  validateField
} from './save-form';

/** Story 8.4: quiet time after the last keystroke before asking for similar queries. */
export const SIMILAR_DEBOUNCE_MS = 600;
export const SIMILAR_MAX = 3;

type TextField = 'title' | 'description' | 'sql_content' | 'notes';
type LoadState = 'loading' | 'ready' | 'not-found' | 'error';

/**
 * Save form (Story 3.4) and its edit mode (Story 3.5, route `data.mode = 'edit'`): Title, Description, SQL required;
 * Tags, Notes, Dialect under "More options". Edit sends PATCH with `version`; a 409 offers Merge on Reload.
 */
@Component({
  selector: 'sql-save-query',
  standalone: true,
  imports: [TagsInputComponent, DiscardModalComponent, RouterLink, NgTemplateOutlet],
  template: `
    <section class="save-page">
      @if (isEdit) {
        <nav class="breadcrumb" aria-label="Breadcrumb">
          <a [routerLink]="libraryUrl">Library</a><span aria-hidden="true"> › </span>
          @if (baseTitle()) {
            <a class="crumb-title" [routerLink]="['/sql/queries', id]" [attr.title]="baseTitle()">{{ baseTitle() }}</a
            ><span aria-hidden="true"> › </span>
          }
          <span aria-current="page">Edit</span>
        </nav>
      }
      @switch (loadState()) {
        @case ('loading') {
          <div class="skeleton-form" aria-hidden="true">
            <div class="bar w40"></div>
            <div class="bar"></div>
            <div class="bar tall"></div>
            <div class="bar code"></div>
          </div>
          <span class="sr-only" role="status" aria-live="polite">Loading</span>
        }
        @case ('not-found') {
          <div class="empty-state not-found">
            <p role="alert">{{ notFoundMessage }}</p>
            @if (lostSql(); as sql) {
              <pre class="code-block lost-sql"><code>{{ sql }}</code></pre>
            }
            <div class="empty-actions"><a class="btn btn-secondary" [routerLink]="libraryUrl">Back to Library</a></div>
          </div>
        }
        @case ('error') {
          <div class="banner banner-danger form-banner" role="alert">
            <span class="banner-glyph" aria-hidden="true">✕</span><span>{{ formError() }}</span>
            <button type="button" class="btn btn-sm btn-secondary" (click)="load()">Retry</button>
          </div>
        }
        @case ('ready') {
          <h1 class="page-title">{{ isEdit ? 'Edit query' : 'New query' }}</h1>
          @if (isEdit) {
            <p class="edit-notice"><span class="banner-glyph" aria-hidden="true">i</span> Changing the SQL re-checks its read-only status.</p>
          }
          @if (conflict()) {
            <div class="banner banner-danger form-banner conflict-banner" role="alert" aria-live="assertive">
              <span class="banner-glyph" aria-hidden="true">!</span><span>{{ conflictMessage }}</span>
              <button type="button" class="btn btn-sm btn-secondary reload-btn" [disabled]="reloading()" (click)="reload()">
                {{ reloading() ? 'Reloading…' : 'Reload' }}
              </button>
            </div>
          }
          @if (formError(); as msg) {
            <div class="banner banner-danger form-banner form-error" role="alert">
              <span class="banner-glyph" aria-hidden="true">✕</span><span>{{ msg }}</span>
            </div>
          }
          <form novalidate (submit)="$event.preventDefault(); submit()" (keydown)="onFormKeydown($event)">
            <div class="field">
              <div class="label-row">
                <label class="label" for="sql-save-title">Title</label>
                <span class="counter" [class.over]="draft().title.length > limits.title">{{ draft().title.length }}/{{ limits.title }}</span>
              </div>
              <input id="sql-save-title" class="text-input" type="text" autocomplete="off" required
                [class.invalid]="!!errors().title" [attr.aria-invalid]="!!errors().title"
                [attr.aria-describedby]="errors().title ? 'sql-save-title-error' : null"
                [value]="draft().title" (input)="setText('title', $event)" />
              @if (errors().title; as e) { <p id="sql-save-title-error" class="field-error" role="alert">{{ e }}</p> }
              <ng-container *ngTemplateOutlet="markerTpl; context: { $implicit: markers().title }" />
            </div>

            <div class="field">
              <div class="label-row">
                <label class="label" for="sql-save-description">Description</label>
                <span class="counter" [class.over]="draft().description.length > limits.description">{{ draft().description.length }}/{{ limits.description }}</span>
              </div>
              <textarea id="sql-save-description" class="text-input" rows="3" required
                [class.invalid]="!!errors().description" [attr.aria-invalid]="!!errors().description"
                [attr.aria-describedby]="errors().description ? 'sql-save-description-error' : null"
                [value]="draft().description" (input)="setText('description', $event)"></textarea>
              @if (errors().description; as e) { <p id="sql-save-description-error" class="field-error" role="alert">{{ e }}</p> }
              <ng-container *ngTemplateOutlet="markerTpl; context: { $implicit: markers().description }" />
            </div>

            <div class="field">
              <div class="label-row">
                <label class="label" for="sql-save-sql_content">SQL</label>
                @if (draft().sql_content.length > sqlCounterFrom) {
                  <span class="counter" [class.over]="sqlOverLimit()">{{ draft().sql_content.length }}/{{ limits.sql_content }}</span>
                }
              </div>
              <textarea id="sql-save-sql_content" class="sql-textarea" rows="8" spellcheck="false" required
                [class.invalid]="!!errors().sql_content" [attr.aria-invalid]="!!errors().sql_content"
                [attr.aria-describedby]="errors().sql_content ? 'sql-save-sql_content-error' : null"
                [value]="draft().sql_content" (input)="setText('sql_content', $event); grow($event)"
                (keydown)="onSqlKeydown($event)"></textarea>
              @if (errors().sql_content; as e) { <p id="sql-save-sql_content-error" class="field-error" role="alert">{{ e }}</p> }
              <ng-container *ngTemplateOutlet="markerTpl; context: { $implicit: markers().sql_content }" />
            </div>

            @if (!isEdit) {
              <div class="similar-slot" aria-live="polite">
                @if (similar().length) {
                  <aside class="similar" aria-label="Similar queries in the library">
                    <p class="similar-heading">Similar queries already in the library</p>
                    <ul class="similar-list">
                      @for (s of similar(); track s.id) {
                        <li><a class="similar-link" [routerLink]="['/sql/queries', s.id]" target="_blank" rel="noopener">{{ s.title }}</a></li>
                      }
                    </ul>
                  </aside>
                }
              </div>
            }

            @if (!isEdit && draft().is_llm_generated && draft().original_nl_prompt; as p) {
              <p class="original-prompt"><span class="label">Original prompt</span> <span class="original-prompt-text">“{{ p }}”</span></p>
            }

            <button type="button" class="btn btn-ghost more-toggle" aria-controls="sql-save-more"
              [attr.aria-expanded]="moreOpen()" (click)="moreOpen.set(!moreOpen())">
              More options
              @if (isEdit) { <span class="more-summary">· {{ summary() }}</span> }
              <span class="chevron" aria-hidden="true">{{ moreOpen() ? '▾' : '▸' }}</span>
            </button>
            @if (moreOpen()) {
              <div id="sql-save-more" class="more">
                <div class="field">
                  <label class="label" for="sql-save-tags">Tags</label>
                  <sql-tags-input inputId="sql-save-tags" [tags]="draft().tags" [maxTags]="limits.tags" [maxLength]="limits.tag"
                    [class.invalid]="!!errors().tags" (tagsChange)="setTags($event)" (rejected)="setFieldError('tags', $event)" />
                  @if (errors().tags; as e) { <p class="field-error" role="alert">{{ e }}</p> }
                  <ng-container *ngTemplateOutlet="markerTpl; context: { $implicit: markers().tags }" />
                </div>
                <div class="field">
                  <div class="label-row">
                    <label class="label" for="sql-save-notes">Notes</label>
                    <span class="counter" [class.over]="draft().notes.length > limits.notes">{{ draft().notes.length }}/{{ limits.notes }}</span>
                  </div>
                  <textarea id="sql-save-notes" class="text-input" rows="3"
                    [class.invalid]="!!errors().notes" [attr.aria-invalid]="!!errors().notes"
                    [attr.aria-describedby]="errors().notes ? 'sql-save-notes-error' : null"
                    [value]="draft().notes" (input)="setText('notes', $event)"></textarea>
                  @if (errors().notes; as e) { <p id="sql-save-notes-error" class="field-error" role="alert">{{ e }}</p> }
                  <ng-container *ngTemplateOutlet="markerTpl; context: { $implicit: markers().notes }" />
                </div>
                <div class="field">
                  <label class="label" for="sql-save-dialect">Dialect</label>
                  <select id="sql-save-dialect" class="text-input" [class.invalid]="!!errors().dialect" [attr.aria-invalid]="!!errors().dialect">
                    <option value="postgresql" selected>PostgreSQL</option>
                  </select>
                  @if (errors().dialect; as e) { <p class="field-error" role="alert">{{ e }}</p> }
                </div>
              </div>
            }

            <div class="form-actions">
              <button type="submit" class="btn btn-primary" [disabled]="saving() || sqlOverLimit()">
                {{ saving() ? 'Saving…' : isEdit ? 'Save changes' : 'Save' }}
              </button>
              <button type="button" class="btn btn-secondary cancel-btn" (click)="cancel()">Cancel</button>
            </div>
          </form>
        }
      }
    </section>

    <ng-template #markerTpl let-m>
      @if (m) {
        <p class="marker">Your change{{ m.server !== null ? ' — also changed by someone else' : '' }}</p>
        @if (m.server !== null) {
          <p class="marker-server">Their version: <span class="marker-value">{{ m.server }}</span></p>
        }
      }
    </ng-template>

    @if (discardOpen()) {
      <sql-discard-modal (keepEditing)="resolveDiscard(false)" (discard)="resolveDiscard(true)" />
    }
  `,
  styleUrls: ['../shell/sql-theme.css', './save-query.component.css']
})
export class SaveQueryComponent {
  private readonly api = inject(SqlApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly history = inject(InAppHistory);
  private readonly toasts = inject(ToastService);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  readonly isEdit = this.route.snapshot.data['mode'] === 'edit';
  readonly id = Number(this.route.snapshot.paramMap.get('id'));
  readonly notFoundMessage = ERROR_MESSAGES['NOT_FOUND'];
  readonly conflictMessage = ERROR_MESSAGES['CONFLICT'];
  readonly libraryUrl = this.router.parseUrl(this.history.lastLibraryUrl());

  readonly limits = SAVE_LIMITS;
  readonly sqlCounterFrom = SQL_COUNTER_FROM;

  /** Create: the blank/prefilled draft. Edit: the server version the edit is based on. */
  private initial: SaveDraft = emptyDraft();
  /** Edit: version sent with PATCH. */
  private version = 0;
  readonly draft = signal<SaveDraft>(emptyDraft());
  readonly errors = signal<FieldErrors>({});
  readonly formError = signal<string | null>(null);
  readonly moreOpen = signal(false);
  readonly saving = signal(false);
  readonly discardOpen = signal(false);

  readonly loadState = signal<LoadState>(this.isEdit ? 'loading' : 'ready');
  readonly baseTitle = signal('');
  readonly conflict = signal(false);
  readonly reloading = signal(false);
  readonly markers = signal<FieldMarkers>({});
  /** Edit 404 on save: the user's SQL stays selectable on screen. */
  readonly lostSql = signal<string | null>(null);

  /** Story 8.4 (FR34): advisory only; never blocks or delays Save, any failure means no suggestions. */
  readonly similar = signal<SimilarQuery[]>([]);
  private readonly similarDraft = new Subject<SimilarQueryDraft>();

  readonly summary = computed(() => moreSummary(this.draft()));
  readonly sqlOverLimit = computed(() => this.draft().sql_content.length > SAVE_LIMITS.sql_content);

  /** After the first submit, fields re-validate live (EXPERIENCE.md). */
  private submitted = false;
  /** Set once saved or discarded: leaving no longer asks. */
  private done = false;
  private escBeforeTab = false;
  /** Where Cancel goes when there is no in-app history entry to go back to. */
  private cancelFallback = '/sql/library';
  private pendingDiscard: ((leave: boolean) => void) | null = null;

  constructor() {
    if (this.isEdit) {
      this.load();
    } else {
      this.watchSimilar();
      // Save from Validate (EXPERIENCE.md prefill 2): the normalized SQL, handed over via session state.
      // Save from Generate (prefill 1): the SQL, the original prompt, is_llm_generated = true.
      // Manual after an outage (prefill 3): the prompt in Notes, More options expanded, is_llm_generated = false.
      const from = this.route.snapshot.queryParamMap.get('from');
      this.cancelFallback = from === 'generate' || from === 'manual' ? '/sql/generate' : '/sql/library';
      const pending = inject(SqlSessionState, { optional: true })?.takePrefill(from);
      if (pending) {
        this.prefill(pending, from === 'manual');
      }
    }
  }

  get dirty(): boolean {
    return !this.done && this.loadState() === 'ready' && !sameDraft(this.draft(), this.initial);
  }

  /** Prefill hook for the Generate / Validate / outage entry points (Epic 7). */
  prefill(draft: Partial<SaveDraft>, expandMore = false): void {
    this.initial = { ...emptyDraft(), ...draft, dialect: draft.dialect || DEFAULT_DIALECT };
    this.draft.set({ ...this.initial });
    this.moreOpen.set(expandMore);
    this.askSimilar();
  }

  /** Edit mode: loads the query into the form. */
  load(): void {
    this.loadState.set('loading');
    this.formError.set(null);
    this.api.getQuery(this.id).subscribe({
      next: (q) => {
        this.adopt(q);
        this.draft.set({ ...this.initial, tags: [...this.initial.tags] });
        this.loadState.set('ready');
      },
      error: (err) => {
        const ui = toUiError(err);
        if (isNotFound(ui)) {
          this.loadState.set('not-found');
        } else {
          this.formError.set(ui.message);
          this.loadState.set('error');
        }
      }
    });
  }

  /** 409 → Reload: fetch the latest version and merge it under the fields the user changed. */
  reload(): void {
    if (this.reloading()) {
      return;
    }
    this.reloading.set(true);
    this.api.getQuery(this.id).subscribe({
      next: (q) => {
        this.reloading.set(false);
        const base = this.initial;
        this.adopt(q);
        const merged = mergeOnReload(base, this.draft(), this.initial);
        this.draft.set(merged.draft);
        this.markers.set(merged.markers);
        this.conflict.set(false);
      },
      error: (err) => {
        this.reloading.set(false);
        this.onError(err);
      }
    });
  }

  setText(field: TextField, event: Event): void {
    const value = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    this.update({ [field]: value }, field);
  }

  setTags(tags: string[]): void {
    this.update({ tags }, 'tags');
  }

  setFieldError(field: FormField, message: string): void {
    this.errors.update((e) => ({ ...e, [field]: message }));
  }

  grow(event: Event): void {
    const el = event.target as HTMLTextAreaElement;
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight + 2}px`;
  }

  /** Tab inserts two spaces; Esc then Tab leaves the field. */
  onSqlKeydown(event: KeyboardEvent): void {
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
      this.update({ sql_content: value }, 'sql_content');
    }
    this.escBeforeTab = false;
  }

  onFormKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.submit();
    }
  }

  submit(): void {
    if (this.saving() || this.loadState() !== 'ready') {
      return;
    }
    this.submitted = true;
    this.formError.set(null);
    const errors = validateDraft(this.draft());
    this.errors.set(errors);
    const first = firstInvalid(errors);
    if (first) {
      this.focusField(first);
      return;
    }
    this.saving.set(true);
    this.conflict.set(false);
    const request = this.isEdit
      ? this.api.patchQuery(this.id, toPatchRequest(this.draft(), this.initial, this.version))
      : this.api.createQuery(toCreateRequest(this.draft()));
    request.subscribe({
      next: (saved) => this.onSaved(saved),
      error: (err) => this.onError(err)
    });
  }

  /** Back to the previous in-app surface; otherwise Library (create) or the detail page (edit). Never leaves the app. */
  cancel(): void {
    this.history.back(this.isEdit ? `/sql/queries/${this.id}` : this.cancelFallback);
  }

  /** CanDeactivate: asks "Discard your changes?" when the form has unsaved edits. */
  canLeave(): boolean | Promise<boolean> {
    if (!this.dirty) {
      return true;
    }
    if (this.saving() || this.pendingDiscard || this.host.nativeElement.ownerDocument.querySelector('[role="dialog"]')) {
      // Never stack modals; never leave mid-save.
      return false;
    }
    this.discardOpen.set(true);
    return new Promise<boolean>((resolve) => (this.pendingDiscard = resolve));
  }

  resolveDiscard(leave: boolean): void {
    this.discardOpen.set(false);
    const resolve = this.pendingDiscard;
    this.pendingDiscard = null;
    if (leave) {
      this.done = true;
    }
    resolve?.(leave);
  }

  @HostListener('window:beforeunload', ['$event'])
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.dirty) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  private watchSimilar(): void {
    this.similarDraft
      .pipe(
        debounceTime(SIMILAR_DEBOUNCE_MS),
        distinctUntilChanged((a, b) => a.title === b.title && a.description === b.description && a.sql_content === b.sql_content),
        switchMap((d) =>
          !d.title && !d.description && !d.sql_content
            ? of<SimilarQuery[]>([])
            : this.api.similarQueries(d).pipe(catchError(() => of<SimilarQuery[]>([])))
        ),
        takeUntilDestroyed()
      )
      .subscribe((list) => this.similar.set(Array.isArray(list) ? list.slice(0, SIMILAR_MAX) : []));
  }

  private askSimilar(): void {
    if (this.isEdit) {
      return;
    }
    const d = this.draft();
    this.similarDraft.next({ title: d.title.trim(), description: d.description.trim(), sql_content: d.sql_content.trim() });
  }

  private update(patch: Partial<SaveDraft>, field: FormField): void {
    this.draft.update((d) => ({ ...d, ...patch }));
    if (field === 'title' || field === 'description' || field === 'sql_content') {
      this.askSimilar();
    }
    // Live per-field validation after the first submit; before it, editing only clears a server/limit error.
    const message = this.submitted ? validateField(this.draft(), field) : null;
    if (message === (this.errors()[field] ?? null)) {
      return;
    }
    this.errors.update((e) => {
      const next = { ...e };
      if (message) {
        next[field] = message;
      } else {
        delete next[field];
      }
      return next;
    });
  }

  private adopt(q: SavedQuery): void {
    this.initial = draftFromQuery(q);
    this.version = q.version;
    this.baseTitle.set(q.title);
  }

  private onSaved(saved: SavedQuery): void {
    this.saving.set(false);
    this.done = true;
    const toast = mutationToast(saved);
    this.toasts.show(toast.kind, toast.text);
    this.router.navigate(['/sql/queries', saved.id]);
  }

  private onError(err: unknown): void {
    this.saving.set(false);
    const ui = toUiError(err);
    const field = ui.field;
    if (this.isEdit && ui.code === 'CONFLICT') {
      this.conflict.set(true);
      return;
    }
    if (this.isEdit && isNotFound(ui)) {
      this.done = true;
      this.lostSql.set(this.draft().sql_content || null);
      this.loadState.set('not-found');
      return;
    }
    if (ui.code === 'VALIDATION_FAILED' && field && (FORM_FIELDS as readonly string[]).includes(field)) {
      this.errors.update((e) => ({ ...e, [field]: ui.message }));
      this.focusField(field as FormField);
    } else {
      this.formError.set(ui.message);
    }
  }

  private focusField(field: FormField): void {
    if (MORE_FIELDS.includes(field)) {
      this.moreOpen.set(true);
    }
    // After render, so a just-expanded "More options" field exists.
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>(`#sql-save-${field}`)?.focus());
  }
}

function isNotFound(ui: { code: string | null; status: number }): boolean {
  return ui.code === 'NOT_FOUND' || ui.status === 404;
}

export const confirmLeaveSaveForm: CanDeactivateFn<SaveQueryComponent> = (c) => c.canLeave();
