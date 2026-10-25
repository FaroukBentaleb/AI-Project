import { Component, DestroyRef, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { Subject, catchError, debounceTime, map, of, switchMap, tap } from 'rxjs';
import { SqlApiService } from '../core/sql-api.service';

export interface QueryRef {
  id: number;
  title: string;
}

export const PICKER_MIN_CHARS = 2;
export const PICKER_RESULTS = 8;
export const PICKER_DEBOUNCE_MS = 250;

/**
 * Replaced-by picker (EXPERIENCE.md): ARIA combobox + listbox. Searches non-deprecated queries after 2 characters,
 * excluding this one, 8 results. ↑/↓ move, Enter picks, Esc closes the list only. The pick shows as a removable chip.
 */
@Component({
  selector: 'sql-replaced-by-picker',
  standalone: true,
  template: `
    @if (selected) {
      <span class="tag picked">
        {{ selected.title }}
        <button type="button" class="tag-remove" [attr.aria-label]="'Remove replacement ' + selected.title" (click)="clear()">×</button>
      </span>
    } @else {
      <div class="picker">
        <input
          [id]="inputId"
          class="text-input"
          type="text"
          role="combobox"
          autocomplete="off"
          aria-autocomplete="list"
          [attr.aria-expanded]="open()"
          [attr.aria-controls]="inputId + '-list'"
          [attr.aria-activedescendant]="open() && active() >= 0 ? inputId + '-opt-' + active() : null"
          [attr.aria-invalid]="invalid"
          placeholder="Search queries…"
          (input)="onInput($event)"
          (keydown)="onKeydown($event)"
        />
        @if (open()) {
          <ul class="listbox" role="listbox" [id]="inputId + '-list'" aria-label="Matching queries">
            @if (searching()) {
              <li class="list-status" role="option" aria-disabled="true">Searching…</li>
            } @else if (!results().length) {
              <li class="list-status" role="option" aria-disabled="true">No matching queries</li>
            } @else {
              @for (r of results(); track r.id; let i = $index) {
                <li role="option" class="option" [id]="inputId + '-opt-' + i" [class.active]="i === active()"
                  [attr.aria-selected]="i === active()" (mousedown)="$event.preventDefault()" (click)="pick(r)">{{ r.title }}</li>
              }
            }
          </ul>
        }
      </div>
    }
  `,
  styles: [
    `.picker { position: relative; }
     .listbox { position: absolute; left: 0; right: 0; top: 100%; z-index: 2; margin: 4px 0 0; padding: 4px 0; list-style: none;
       background: var(--sql-surface-raised); border: 1px solid var(--sql-border); border-radius: 6px; max-height: 260px; overflow-y: auto; }
     .option, .list-status { padding: 6px 10px; font-size: 13px; }
     .option { cursor: pointer; color: var(--sql-text-primary); }
     .option.active, .option:hover { background: var(--sql-accent-tint); }
     .list-status { color: var(--sql-text-muted); }
     .tag-remove { margin-left: 4px; background: none; border: 0; color: inherit; cursor: pointer; }`
  ],
  styleUrls: ['../shell/sql-theme.css']
})
export class ReplacedByPickerComponent {
  @Input({ required: true }) excludeId = 0;
  @Input() inputId = 'sql-replaced-by';
  @Input() invalid = false;
  @Input() selected: QueryRef | null = null;
  @Output() readonly selectedChange = new EventEmitter<QueryRef | null>();

  readonly open = signal(false);
  readonly searching = signal(false);
  readonly results = signal<QueryRef[]>([]);
  readonly active = signal(-1);

  private readonly api = inject(SqlApiService);
  private readonly terms = new Subject<string>();

  constructor() {
    const sub = this.terms
      .pipe(
        tap((t) => {
          const ready = t.trim().length >= PICKER_MIN_CHARS;
          this.open.set(ready);
          this.searching.set(ready);
          this.active.set(-1);
        }),
        debounceTime(PICKER_DEBOUNCE_MS),
        switchMap((t) =>
          t.trim().length < PICKER_MIN_CHARS
            ? of([])
            : this.api.listQueries({ q: t.trim(), deprecated: false, page: 0, size: PICKER_RESULTS + 1 }).pipe(
                map((page) =>
                  page.content
                    .filter((q) => q.id !== this.excludeId)
                    .slice(0, PICKER_RESULTS)
                    .map((q) => ({ id: q.id, title: q.title }))
                ),
                catchError(() => of([]))
              )
        )
      )
      .subscribe((list) => {
        this.results.set(list);
        this.searching.set(false);
        this.active.set(list.length ? 0 : -1);
      });
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
  }

  onInput(event: Event): void {
    this.terms.next((event.target as HTMLInputElement).value);
  }

  onKeydown(event: KeyboardEvent): void {
    const n = this.results().length;
    if (event.key === 'Escape' && this.open()) {
      // Closes the list only, not the modal.
      event.preventDefault();
      event.stopPropagation();
      this.open.set(false);
    } else if (event.key === 'ArrowDown' && n) {
      event.preventDefault();
      this.open.set(true);
      this.active.set((this.active() + 1) % n);
    } else if (event.key === 'ArrowUp' && n) {
      event.preventDefault();
      this.open.set(true);
      this.active.set((this.active() - 1 + n) % n);
    } else if (event.key === 'Enter' && this.open() && this.active() >= 0 && !this.searching()) {
      event.preventDefault();
      this.pick(this.results()[this.active()]);
    }
  }

  pick(ref: QueryRef): void {
    this.open.set(false);
    this.selectedChange.emit(ref);
  }

  clear(): void {
    this.results.set([]);
    this.selectedChange.emit(null);
  }
}
