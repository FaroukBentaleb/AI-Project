import { Location } from '@angular/common';
import { Component, ElementRef, HostListener, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, CanDeactivateFn, Router } from '@angular/router';
import { toUiError } from '../core/error-mapper';
import { SavedQuery } from '../core/models';
import { SqlApiService } from '../core/sql-api.service';
import { DiscardModalComponent } from '../shared/discard-modal.component';
import { TagsInputComponent } from '../shared/tags-input.component';
import { ToastService } from '../shared/toast.service';
import {
  DEFAULT_DIALECT,
  FORM_FIELDS,
  FieldErrors,
  FormField,
  MORE_FIELDS,
  SAVE_LIMITS,
  SQL_COUNTER_FROM,
  SaveDraft,
  emptyDraft,
  firstInvalid,
  mutationToast,
  sameDraft,
  toCreateRequest,
  validateDraft,
  validateField
} from './save-form';

type TextField = 'title' | 'description' | 'sql_content' | 'notes';

/** Save form, create mode (Story 3.4): Title, Description, SQL required; Tags, Notes, Dialect under "More options". */
@Component({
  selector: 'sql-save-query',
  standalone: true,
  imports: [TagsInputComponent, DiscardModalComponent],
  template: `
    <section class="save-page">
      <h1 class="page-title">New query</h1>
      @if (formError(); as msg) {
        <div class="banner banner-danger form-banner" role="alert">
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
        </div>

        <button type="button" class="btn btn-ghost more-toggle" aria-controls="sql-save-more"
          [attr.aria-expanded]="moreOpen()" (click)="moreOpen.set(!moreOpen())">
          More options <span class="chevron" aria-hidden="true">{{ moreOpen() ? '▾' : '▸' }}</span>
        </button>
        @if (moreOpen()) {
          <div id="sql-save-more" class="more">
            <div class="field">
              <label class="label" for="sql-save-tags">Tags</label>
              <sql-tags-input inputId="sql-save-tags" [tags]="draft().tags" [maxTags]="limits.tags" [maxLength]="limits.tag"
                [class.invalid]="!!errors().tags" (tagsChange)="setTags($event)" (rejected)="setFieldError('tags', $event)" />
              @if (errors().tags; as e) { <p class="field-error" role="alert">{{ e }}</p> }
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
          <button type="submit" class="btn btn-primary" [disabled]="saving() || sqlOverLimit()">{{ saving() ? 'Saving…' : 'Save' }}</button>
          <button type="button" class="btn btn-secondary" (click)="cancel()">Cancel</button>
        </div>
      </form>
    </section>

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
  private readonly location = inject(Location);
  private readonly toasts = inject(ToastService);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  readonly limits = SAVE_LIMITS;
  readonly sqlCounterFrom = SQL_COUNTER_FROM;

  private initial: SaveDraft = emptyDraft();
  readonly draft = signal<SaveDraft>(emptyDraft());
  readonly errors = signal<FieldErrors>({});
  readonly formError = signal<string | null>(null);
  readonly moreOpen = signal(false);
  readonly saving = signal(false);
  readonly discardOpen = signal(false);

  readonly sqlOverLimit = computed(() => this.draft().sql_content.length > SAVE_LIMITS.sql_content);

  /** After the first submit, fields re-validate live (EXPERIENCE.md). */
  private submitted = false;
  /** Set once saved or discarded: leaving no longer asks. */
  private done = false;
  private escBeforeTab = false;
  private pendingDiscard: ((leave: boolean) => void) | null = null;

  get dirty(): boolean {
    return !this.done && !sameDraft(this.draft(), this.initial);
  }

  /** Prefill hook for the Generate / Validate / outage entry points (Epic 7). */
  prefill(draft: Partial<SaveDraft>, expandMore = false): void {
    this.initial = { ...emptyDraft(), ...draft, dialect: draft.dialect || DEFAULT_DIALECT };
    this.draft.set({ ...this.initial });
    this.moreOpen.set(expandMore);
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
    if (this.saving()) {
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
    this.api.createQuery(toCreateRequest(this.draft())).subscribe({
      next: (saved) => this.onSaved(saved),
      error: (err) => this.onError(err)
    });
  }

  /** Back to the previous surface (its session state is intact); Library when the form was the entry page. */
  cancel(): void {
    if (this.router.lastSuccessfulNavigation?.previousNavigation) {
      this.location.back();
    } else {
      this.router.navigate(['/sql/library']);
    }
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

  private update(patch: Partial<SaveDraft>, field: FormField): void {
    this.draft.update((d) => ({ ...d, ...patch }));
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

  private onSaved(saved: SavedQuery): void {
    this.saving.set(false);
    this.done = true;
    const toast = mutationToast(saved);
    this.toasts.show(toast.kind, toast.text);
    this.router.navigate(['..', saved.id], { relativeTo: this.route });
  }

  private onError(err: unknown): void {
    this.saving.set(false);
    const ui = toUiError(err);
    const field = ui.field;
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

export const confirmLeaveSaveForm: CanDeactivateFn<SaveQueryComponent> = (c) => c.canLeave();
