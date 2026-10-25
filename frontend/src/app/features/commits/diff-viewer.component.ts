import { Component, Input, computed, signal } from '@angular/core';
import { DiffFile, parseDiff } from './diff-parser';

/** GitHub-style diff: one collapsible block per file, +/- counts, line numbers and coloured lines. */
@Component({
  selector: 'app-diff-viewer',
  standalone: true,
  template: `
    @if (files().length > 0) {
      <div class="toolbar">
        <span>{{ files().length }} {{ files().length === 1 ? 'file' : 'files' }} changed</span>
        <span class="add">+{{ totals().additions }}</span>
        <span class="del">−{{ totals().deletions }}</span>
        <button type="button" (click)="toggleAll()">{{ allOpen() ? 'Collapse all' : 'Expand all' }}</button>
      </div>
      @for (file of files(); track file.path) {
        <section class="file">
          <button type="button" class="head" (click)="toggle(file.path)" [attr.aria-expanded]="isOpen(file.path)">
            <span class="chevron" [class.open]="isOpen(file.path)" aria-hidden="true">▸</span>
            <span class="status {{ file.status }}" [attr.title]="file.status">{{ letter(file) }}</span>
            <span class="path">{{ file.path }}</span>
            <span class="add">+{{ file.additions }}</span>
            <span class="del">−{{ file.deletions }}</span>
          </button>
          @if (isOpen(file.path)) {
            <div class="body" role="table" [attr.aria-label]="'Changes in ' + file.path">
              @for (line of file.lines; track $index) {
                <div class="line {{ line.kind }}" role="row">
                  <span class="no" aria-hidden="true">{{ line.oldNo ?? '' }}</span>
                  <span class="no" aria-hidden="true">{{ line.newNo ?? '' }}</span>
                  <span class="sign" aria-hidden="true">{{ sign(line.kind) }}</span>
                  <span class="text">{{ line.kind === 'hunk' ? line.text : line.text || ' ' }}</span>
                </div>
              }
            </div>
          }
        </section>
      }
    } @else {
      <p class="empty">No textual changes to show.</p>
    }
  `,
  styles: [`
    :host { display: block; font: 13px system-ui, sans-serif; color: var(--text, #e6edf3); }
    .toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 8px; color: var(--muted, #8b949e); }
    .toolbar button { margin-left: auto; background: transparent; border: 1px solid var(--border, #30363d);
      color: inherit; border-radius: 6px; padding: 3px 10px; cursor: pointer; font: inherit; }
    .toolbar button:hover { color: var(--text, #e6edf3); }
    .add { color: #3fb950; font-weight: 600; }
    .del { color: #f85149; font-weight: 600; }
    .file { border: 1px solid var(--border, #30363d); border-radius: 8px; margin-bottom: 8px; overflow: hidden;
      background: #0d1117; }
    .head { display: flex; align-items: center; gap: 10px; width: 100%; padding: 8px 12px; border: 0;
      background: #161b22; color: inherit; cursor: pointer; font: inherit; text-align: left; }
    .head:hover { background: #1c2230; }
    .head:focus-visible { outline: 2px solid #58a6ff; outline-offset: -2px; }
    .chevron { color: var(--muted, #8b949e); transition: transform .12s; display: inline-block; }
    .chevron.open { transform: rotate(90deg); }
    .status { width: 18px; height: 18px; border-radius: 4px; display: grid; place-items: center; font-size: 11px;
      font-weight: 700; color: #0d1117; flex: none; }
    .status.added { background: #3fb950; } .status.deleted { background: #f85149; } .status.modified { background: #d29922; }
    .path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .body { overflow-x: auto; font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, monospace; }
    .line { display: grid; grid-template-columns: 44px 44px 18px 1fr; min-width: max-content; }
    .no { text-align: right; padding-right: 8px; color: #6e7681; user-select: none; }
    .sign { text-align: center; user-select: none; }
    .text { white-space: pre; padding-right: 16px; }
    .line.add { background: rgba(46, 160, 67, .15); } .line.add .sign { color: #3fb950; }
    .line.del { background: rgba(248, 81, 73, .15); } .line.del .sign { color: #f85149; }
    .line.hunk { background: rgba(56, 139, 253, .12); color: #79c0ff; }
    .line.meta { color: var(--muted, #8b949e); font-style: italic; }
    .empty { color: var(--muted, #8b949e); }
  `]
})
export class DiffViewerComponent {
  readonly files = signal<DiffFile[]>([]);
  private readonly open = signal<ReadonlySet<string>>(new Set());
  readonly totals = computed(() => ({
    additions: this.files().reduce((sum, f) => sum + f.additions, 0),
    deletions: this.files().reduce((sum, f) => sum + f.deletions, 0)
  }));
  readonly allOpen = computed(() => this.files().length > 0 && this.files().every((f) => this.open().has(f.path)));

  @Input() set diff(value: string) {
    const files = parseDiff(value ?? '');
    this.files.set(files);
    this.open.set(new Set(files.length <= 3 ? files.map((f) => f.path) : files.slice(0, 1).map((f) => f.path)));
  }

  isOpen(path: string): boolean {
    return this.open().has(path);
  }

  toggle(path: string): void {
    const next = new Set(this.open());
    next.has(path) ? next.delete(path) : next.add(path);
    this.open.set(next);
  }

  toggleAll(): void {
    this.open.set(this.allOpen() ? new Set() : new Set(this.files().map((f) => f.path)));
  }

  letter(file: DiffFile): string {
    return file.status === 'added' ? 'A' : file.status === 'deleted' ? 'D' : 'M';
  }

  sign(kind: string): string {
    return kind === 'add' ? '+' : kind === 'del' ? '−' : '';
  }
}
