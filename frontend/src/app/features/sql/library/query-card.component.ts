import { Component, ElementRef, Input, computed, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SavedQuery } from '../core/models';
import { CopyButtonComponent } from '../shared/copy-button.component';
import { relativeTime } from './relative-time';

export const PREVIEW_LINES = 6;

/** Query card (DESIGN.md anatomy): title link, description excerpt, meta line, tags + badges, clipped SQL. */
@Component({
  selector: 'sql-query-card',
  standalone: true,
  imports: [RouterLink, CopyButtonComponent],
  template: `
    @let q = query();
    <article class="card" [attr.aria-labelledby]="'sql-card-' + q.id">
      <div class="title-row">
        <h3 class="card-title" [id]="'sql-card-' + q.id">
          <a [routerLink]="['/sql/queries', q.id]">{{ q.title }}</a>
        </h3>
        <sql-copy-button
          [text]="q.sql_content"
          [queryId]="q.id"
          [label]="'Copy SQL of ' + q.title"
          (copied)="onCopied()"
          (failed)="onCopyFailed()"
        />
      </div>
      @if (q.description) {
        <p class="desc">{{ q.description }}</p>
      }
      <p class="meta">
        <span class="avatar" aria-hidden="true">{{ initial() }}</span>
        <span class="author">{{ q.author }}</span>
        @if (updated()) {
          <span aria-hidden="true">·</span>
          <span [attr.title]="q.updated_at">updated {{ updated() }}</span>
        }
      </p>
      <div class="chips">
        @for (tag of q.tags; track tag) {
          <span class="tag">{{ tag }}</span>
        }
        @if (q.is_llm_generated) {
          <span class="badge badge-llm">LLM-generated</span>
        }
        @if (q.deprecated) {
          <span class="badge badge-deprecated">Deprecated</span>
        }
        <span class="badge badge-usage">{{ usage() }}</span>
      </div>
      <div class="code" [class.clipped]="clipped()">
        <pre class="code-block"><code #code>{{ preview() }}</code></pre>
        @if (clipped()) {
          <div class="fade" aria-hidden="true"></div>
          <a class="btn btn-sm btn-secondary show-all" [routerLink]="['/sql/queries', q.id]">Show all ({{ lineCount() }} lines)</a>
        }
      </div>
    </article>
  `,
  styleUrls: ['../shell/sql-theme.css', './query-card.component.css']
})
export class QueryCardComponent {
  readonly query = signal<SavedQuery>(undefined as unknown as SavedQuery);
  @Input({ required: true, alias: 'query' }) set queryInput(q: SavedQuery) {
    this.query.set(q);
  }

  readonly initial = computed(() => (this.query().author || '?').charAt(0).toUpperCase());
  readonly updated = computed(() => relativeTime(this.query().updated_at));
  private readonly lines = computed(() => (this.query().sql_content ?? '').replace(/\s+$/, '').split('\n'));
  readonly lineCount = computed(() => this.lines().length);
  /** Set when the clipboard is denied: the full SQL is shown so it can be selected and copied by hand. */
  readonly expanded = signal(false);
  readonly clipped = computed(() => !this.expanded() && this.lineCount() > PREVIEW_LINES);
  readonly preview = computed(() => (this.clipped() ? this.lines().slice(0, PREVIEW_LINES).join('\n') : this.query().sql_content ?? ''));
  private readonly code = viewChild<ElementRef<HTMLElement>>('code');
  readonly usage = computed(() => {
    const q = this.query();
    if (!q.use_count) {
      return 'Never used';
    }
    const uses = `Used ${q.use_count}×`;
    const last = relativeTime(q.last_used_at);
    return last ? `${uses} · last ${last}` : uses;
  });

  /** Optimistic use count (EXPERIENCE.md): stays bumped even if the usage call fails. */
  onCopied(): void {
    this.query.update((q) => ({ ...q, use_count: (q.use_count ?? 0) + 1, last_used_at: new Date().toISOString() }));
  }

  /** Clipboard denied: show the full SQL and select it for Ctrl/⌘+C. */
  onCopyFailed(): void {
    this.expanded.set(true);
    setTimeout(() => {
      const el = this.code()?.nativeElement;
      const selection = el?.ownerDocument.defaultView?.getSelection();
      if (el && selection) {
        const range = el.ownerDocument.createRange();
        range.selectNodeContents(el);
        selection.removeAllRanges();
        selection.addRange(range);
      }
    });
  }
}
