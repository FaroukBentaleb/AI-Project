import { Component, DestroyRef, ElementRef, ViewChild, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ERROR_MESSAGES, UiError, toUiError } from '../core/error-mapper';
import { InAppHistory } from '../core/in-app-history.service';
import { SavedQuery } from '../core/models';
import { SqlApiService } from '../core/sql-api.service';
import { relativeTime } from '../library/relative-time';
import { mutationToast } from '../save/save-form';
import { CopyButtonComponent } from '../shared/copy-button.component';
import { ToastService } from '../shared/toast.service';
import { DeleteModalComponent } from './delete-modal.component';
import { DeprecateErrors, DeprecateModalComponent, DeprecateSubmit } from './deprecate-modal.component';
import { QueryRef } from './replaced-by-picker.component';

type LoadState = 'loading' | 'ready' | 'not-found' | 'error';
type Extras = SavedQuery & { read_only?: boolean; indexed?: boolean };

export const DEPRECATED_TOAST = 'Deprecated · removed from team examples';
export const ALREADY_DELETED_TOAST = 'Already deleted';

/** Query detail (FR31, EXPERIENCE.md): every attribute, copy, edit, deprecate / undeprecate, delete. */
@Component({
  selector: 'sql-query-detail',
  standalone: true,
  imports: [RouterLink, CopyButtonComponent, DeprecateModalComponent, DeleteModalComponent],
  template: `
    <section class="detail-page">
      <nav class="breadcrumb" aria-label="Breadcrumb">
        <a [routerLink]="libraryUrl()">Library</a>
        @if (query(); as q) {
          <span aria-hidden="true">&nbsp;›&nbsp;</span><span class="crumb-title" aria-current="page" [attr.title]="q.title">{{ q.title }}</span>
        }
      </nav>

      @switch (state()) {
        @case ('loading') {
          <div class="layout" aria-hidden="true">
            <div class="main skeleton-main"><div class="skeleton-bar w50"></div><div class="skeleton-bar w30"></div><div class="skeleton-bar code"></div></div>
            <aside class="aside skeleton-aside">@for (i of [1, 2, 3, 4, 5]; track i) { <div class="skeleton-bar"></div> }</aside>
          </div>
          <span class="sr-only" role="status" aria-live="polite">Loading</span>
        }
        @case ('not-found') {
          <div class="empty-state not-found">
            <p role="alert">{{ notFoundMessage }}</p>
            <div class="empty-actions"><a class="btn btn-secondary" [routerLink]="libraryUrl()">Back to Library</a></div>
          </div>
        }
        @case ('error') {
          <div class="banner banner-danger" role="alert">
            <span class="banner-glyph" aria-hidden="true">✕</span><span>{{ loadError() }}</span>
            <button type="button" class="btn btn-sm btn-secondary retry" (click)="load()">Retry</button>
          </div>
        }
        @case ('ready') {
          @if (query(); as q) {
            <div class="layout">
              <div class="main">
                <h1 class="page-title">{{ q.title }}</h1>
                <div class="badges">
                  @if (q.is_llm_generated) { <span class="badge badge-llm">LLM-generated</span> }
                  @if (q.deprecated) { <span class="badge badge-deprecated">Deprecated</span> }
                  @if (readOnly() === true) { <span class="badge badge-read-only">Read-only ✓</span> }
                  <span class="badge badge-usage">{{ usage() }}</span>
                </div>

                @if (q.deprecated) {
                  <div class="banner banner-neutral deprecated-banner" role="note">
                    <span class="banner-glyph" aria-hidden="true">i</span>
                    <span>
                      Deprecated:
                      @if (replacement(); as r) {
                        replaced by '<a [routerLink]="['/sql/queries', r.id]">{{ r.title }}</a>' —
                      }
                      reason: {{ q.deprecated_reason }}
                    </span>
                  </div>
                }
                @if (actionError(); as msg) {
                  <div class="banner banner-danger action-error" role="alert">
                    <span class="banner-glyph" aria-hidden="true">✕</span><span>{{ msg }}</span>
                  </div>
                }

                <div class="action-row">
                  <sql-copy-button [text]="q.sql_content" [queryId]="q.id" buttonText="Copy SQL" [label]="'Copy SQL of ' + q.title" (copied)="onCopied()" />
                  <a class="btn btn-secondary edit-btn" [routerLink]="['/sql/queries', q.id, 'edit']">Edit</a>
                  <button #deprecateBtn type="button" class="btn btn-secondary deprecate-btn" [disabled]="restoring()"
                    (click)="q.deprecated ? undeprecate() : openDeprecate()">
                    {{ q.deprecated ? (restoring() ? 'Undeprecating…' : 'Undeprecate') : 'Deprecate' }}
                  </button>
                  <button #deleteBtn type="button" class="btn btn-ghost-danger delete-btn" (click)="openDelete()">Delete</button>
                </div>

                <div class="code-wrap">
                  <pre class="gutter" aria-hidden="true">{{ lineNumbers() }}</pre>
                  <pre class="code-block detail-code" aria-label="SQL"><code>{{ q.sql_content }}</code></pre>
                </div>
              </div>

              <aside class="aside" aria-label="Query details">
                <dl>
                  <dt>Description</dt>
                  <dd class="description">{{ q.description }}</dd>
                  <dt>Author</dt>
                  <dd class="author">{{ q.author }}</dd>
                  <dt>Created · Updated</dt>
                  <dd class="dates">
                    <span [attr.title]="q.created_at">{{ created() }}</span> · <span [attr.title]="q.updated_at">{{ updated() }}</span>
                  </dd>
                  <dt>Dialect</dt>
                  <dd>{{ q.dialect === 'postgresql' ? 'PostgreSQL' : q.dialect }}</dd>
                  <dt>Tags</dt>
                  <dd class="tags">
                    @for (t of q.tags; track t) { <span class="tag">{{ t }}</span> } @empty { <span class="muted-inline">None</span> }
                  </dd>
                  <dt>Origin</dt>
                  <dd class="origin">
                    @if (q.is_llm_generated) {
                      <span class="badge badge-llm">LLM-generated</span>
                      @if (q.original_nl_prompt) { <p class="prompt">Original prompt: '{{ q.original_nl_prompt }}'</p> }
                    } @else {
                      Written by hand
                    }
                  </dd>
                  <dt>Usage</dt>
                  <dd class="usage">{{ usage() }}</dd>
                  @if (readOnly() !== undefined) {
                    <dt>Read-only</dt>
                    <dd>{{ readOnly() ? 'Read-only ✓' : 'Not read-only' }}</dd>
                  }
                  <dt>Notes</dt>
                  <dd class="notes">{{ q.notes || '—' }}</dd>
                  <dt>Team examples</dt>
                  <dd class="team">{{ teamStatus() }}</dd>
                </dl>
              </aside>
            </div>
          }
        }
      }
    </section>

    @if (deprecateOpen() && query(); as q) {
      <sql-deprecate-modal [title]="q.title" [queryId]="q.id" [busy]="busy()" [errors]="deprecateErrors()"
        (submitted)="deprecate($event)" (cancelled)="closeDeprecate()" />
    }
    @if (deleteOpen() && query(); as q) {
      <sql-delete-modal [title]="q.title" [busy]="busy()" [error]="deleteError()" (confirmed)="remove()" (cancelled)="closeDelete()" />
    }
  `,
  styleUrls: ['../shell/sql-theme.css', './query-detail.component.css']
})
export class QueryDetailComponent {
  private readonly api = inject(SqlApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly history = inject(InAppHistory);
  private readonly toasts = inject(ToastService);

  readonly notFoundMessage = ERROR_MESSAGES['NOT_FOUND'];
  readonly state = signal<LoadState>('loading');
  readonly query = signal<SavedQuery | null>(null);
  readonly replacement = signal<QueryRef | null>(null);
  readonly loadError = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly restoring = signal(false);
  readonly deprecateOpen = signal(false);
  readonly deleteOpen = signal(false);
  readonly busy = signal(false);
  readonly deprecateErrors = signal<DeprecateErrors>({});
  readonly deleteError = signal<string | null>(null);

  readonly libraryUrl = signal(this.router.parseUrl(this.history.lastLibraryUrl()));
  readonly readOnly = computed(() => (this.query() as Extras | null)?.read_only);
  readonly created = computed(() => relativeTime(this.query()?.created_at) || this.query()?.created_at || '');
  readonly updated = computed(() => relativeTime(this.query()?.updated_at) || this.query()?.updated_at || '');
  readonly lineNumbers = computed(() => {
    const n = (this.query()?.sql_content ?? '').split('\n').length;
    return Array.from({ length: n }, (_, i) => i + 1).join('\n');
  });
  readonly usage = computed(() => {
    const q = this.query();
    if (!q?.use_count) {
      return 'Never used';
    }
    const last = relativeTime(q.last_used_at);
    return last ? `Used ${q.use_count}× · last ${last}` : `Used ${q.use_count}×`;
  });
  readonly teamStatus = computed(() => {
    const q = this.query() as Extras | null;
    if (!q || q.deprecated || q.read_only === false) {
      return 'Not used as a team example';
    }
    if (q.indexed === false) {
      return 'Not yet indexed — will be added automatically.';
    }
    return 'Used as a team example';
  });

  @ViewChild('deprecateBtn') private deprecateBtn?: ElementRef<HTMLButtonElement>;
  @ViewChild('deleteBtn') private deleteBtn?: ElementRef<HTMLButtonElement>;

  private id = 0;

  constructor() {
    // The component is reused when following a replacement link: reload on every id change.
    const sub = this.route.paramMap.subscribe((p) => {
      this.id = Number(p.get('id'));
      this.load();
    });
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
  }

  load(): void {
    this.state.set('loading');
    this.query.set(null);
    this.replacement.set(null);
    this.actionError.set(null);
    this.libraryUrl.set(this.router.parseUrl(this.history.lastLibraryUrl()));
    const id = this.id;
    this.api.getQuery(id).subscribe({
      next: (q) => {
        if (id !== this.id) {
          return;
        }
        this.show(q);
        this.state.set('ready');
      },
      error: (err) => {
        if (id !== this.id) {
          return;
        }
        const ui = toUiError(err);
        if (isNotFound(ui)) {
          this.state.set('not-found');
        } else {
          this.loadError.set(ui.message);
          this.state.set('error');
        }
      }
    });
  }

  /** Optimistic use count (as on the card). */
  onCopied(): void {
    this.query.update((q) => (q ? { ...q, use_count: (q.use_count ?? 0) + 1, last_used_at: new Date().toISOString() } : q));
  }

  openDeprecate(): void {
    this.deprecateErrors.set({});
    this.deprecateOpen.set(true);
  }

  closeDeprecate(): void {
    this.deprecateOpen.set(false);
    this.restoreFocus(this.deprecateBtn);
  }

  deprecate(input: DeprecateSubmit): void {
    const q = this.query();
    if (!q || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.deprecateErrors.set({});
    this.api.deprecateQuery(q.id, { reason: input.reason, replaced_by: input.replaced_by }).subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.show(saved, input.replacement);
        this.toasts.show('success', DEPRECATED_TOAST);
        this.closeDeprecate();
      },
      error: (err) => {
        this.busy.set(false);
        const ui = toUiError(err);
        if (ui.code === 'VALIDATION_FAILED' && (ui.field === 'reason' || ui.field === 'replaced_by')) {
          this.deprecateErrors.set({ [ui.field]: ui.message });
        } else {
          this.deprecateErrors.set({ form: ui.message });
        }
      }
    });
  }

  undeprecate(): void {
    const q = this.query();
    if (!q || this.restoring()) {
      return;
    }
    this.restoring.set(true);
    this.actionError.set(null);
    this.api.restoreQuery(q.id).subscribe({
      next: (saved) => {
        this.restoring.set(false);
        this.show(saved);
        const toast = mutationToast(saved, 'Undeprecated');
        this.toasts.show(toast.kind, toast.text);
        this.restoreFocus(this.deprecateBtn);
      },
      error: (err) => {
        this.restoring.set(false);
        const ui = toUiError(err);
        if (isNotFound(ui)) {
          this.state.set('not-found');
        } else {
          this.actionError.set(ui.message);
        }
      }
    });
  }

  openDelete(): void {
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  closeDelete(): void {
    this.deleteOpen.set(false);
    this.restoreFocus(this.deleteBtn);
  }

  remove(): void {
    const q = this.query();
    if (!q || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.deleteError.set(null);
    this.api.deleteQuery(q.id).subscribe({
      next: () => this.afterDelete(`Deleted '${q.title}'`),
      error: (err) => {
        const ui = toUiError(err);
        if (isNotFound(ui)) {
          this.afterDelete(ALREADY_DELETED_TOAST);
        } else {
          this.busy.set(false);
          this.deleteError.set(ui.message);
        }
      }
    });
  }

  private afterDelete(text: string): void {
    this.busy.set(false);
    this.deleteOpen.set(false);
    this.toasts.show('success', text);
    this.router.navigateByUrl(this.history.lastLibraryUrl());
  }

  /** Shows a query; resolves the replacement title for the deprecated banner link. */
  private show(q: SavedQuery, known: QueryRef | null = null): void {
    this.query.set(q);
    const target = q.deprecated ? q.replaced_by : null;
    if (target == null) {
      this.replacement.set(null);
      return;
    }
    if (known?.id === target) {
      this.replacement.set(known);
      return;
    }
    this.replacement.set(null);
    this.api.getQuery(target).subscribe({
      next: (r) => this.query()?.replaced_by === target && this.replacement.set({ id: r.id, title: r.title }),
      error: (err) => {
        // A deleted replacement gets no link; any other failure still links by id.
        if (!isNotFound(toUiError(err)) && this.query()?.replaced_by === target) {
          this.replacement.set({ id: target, title: `query #${target}` });
        }
      }
    });
  }

  private restoreFocus(ref?: ElementRef<HTMLElement>): void {
    setTimeout(() => (ref ?? this.deprecateBtn)?.nativeElement.focus());
  }
}

function isNotFound(ui: UiError): boolean {
  return ui.code === 'NOT_FOUND' || ui.status === 404;
}
