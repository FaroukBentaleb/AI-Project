import { Component, ElementRef, EventEmitter, Input, Output, ViewChild, signal } from '@angular/core';
import { MAX_ZIP_BYTES, formatBytes, zipProblem } from './commit-format';

/** A big drag-and-drop target (also clickable and keyboard accessible) that hands back one validated ZIP. */
@Component({
  selector: 'app-drop-zone',
  standalone: true,
  template: `
    <div class="zone" role="button" tabindex="0" [class.over]="over()" [class.filled]="file" [class.disabled]="disabled"
         [attr.aria-disabled]="disabled" [attr.aria-label]="file ? 'Selected ZIP: ' + file.name + '. Press Enter to choose another.' : 'Choose a ZIP file or drop it here'"
         (click)="browse()" (keydown.enter)="browse()" (keydown.space)="$event.preventDefault(); browse()"
         (dragenter)="drag($event, true)" (dragover)="drag($event, true)" (dragleave)="drag($event, false)"
         (drop)="drop($event)">
      <input #picker type="file" accept=".zip,application/zip,application/x-zip-compressed" hidden
             (change)="picked($event)" />
      @if (file) {
        <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M6 2h8l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z" />
          <path d="M14 2v5h5M10 11h2M10 14h2M10 17h2" />
        </svg>
        <div class="file">
          <strong>{{ file.name }}</strong>
          <span>{{ size(file.size) }} · ready to upload</span>
        </div>
        <button type="button" class="remove" (click)="clear($event)" [disabled]="disabled"
                aria-label="Remove the selected file">✕</button>
      } @else {
        <svg class="icon big" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 16V4M7 9l5-5 5 5" />
          <path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" />
        </svg>
        <p class="title">{{ over() ? 'Release to add your ZIP' : 'Drag & drop your project ZIP' }}</p>
        <p class="sub">or <span class="link">browse files</span></p>
        <p class="hint">.zip · up to {{ maxSize }} · text files only</p>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }
    .zone { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px;
      min-height: 168px; padding: 20px; text-align: center; cursor: pointer; color: var(--muted, #8b949e);
      border: 2px dashed var(--border-strong, #3d444d); border-radius: 12px; background: rgba(110, 118, 129, .06);
      transition: border-color .15s, background .15s, transform .15s; outline: none; }
    .zone:hover { border-color: #58a6ff; background: rgba(56, 139, 253, .06); }
    .zone:focus-visible { box-shadow: 0 0 0 3px rgba(56, 139, 253, .45); border-color: #58a6ff; }
    .zone.over { border-color: #58a6ff; background: rgba(56, 139, 253, .14); transform: scale(1.01); }
    .zone.filled { flex-direction: row; justify-content: flex-start; gap: 14px; min-height: 84px; text-align: left;
      border-style: solid; border-color: rgba(63, 185, 80, .5); background: rgba(63, 185, 80, .07); }
    .zone.disabled { opacity: .55; pointer-events: none; }
    .icon { width: 28px; height: 28px; flex: none; fill: none; stroke: currentColor; stroke-width: 1.6;
      stroke-linecap: round; stroke-linejoin: round; }
    .icon.big { width: 44px; height: 44px; color: #58a6ff; margin-bottom: 6px; }
    .filled .icon { width: 34px; height: 34px; color: #3fb950; }
    .title { margin: 0; color: var(--text, #e6edf3); font-size: 15px; font-weight: 600; }
    .sub, .hint { margin: 0; font-size: 13px; }
    .hint { font-size: 12px; opacity: .8; margin-top: 6px; }
    .link { color: #58a6ff; text-decoration: underline; text-underline-offset: 2px; }
    .file { display: grid; gap: 2px; min-width: 0; flex: 1; }
    .file strong { color: var(--text, #e6edf3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .file span { font-size: 12px; }
    .remove { flex: none; width: 30px; height: 30px; border-radius: 50%; border: 1px solid var(--border, #30363d);
      background: transparent; color: inherit; cursor: pointer; }
    .remove:hover { color: #f85149; border-color: #f85149; }
    @media (prefers-reduced-motion: reduce) { .zone { transition: none; } .zone.over { transform: none; } }
  `]
})
export class DropZoneComponent {
  @Input() file: File | null = null;
  @Input() disabled = false;
  @Output() fileChange = new EventEmitter<File | null>();
  @Output() rejected = new EventEmitter<string>();
  @ViewChild('picker', { static: true }) private picker!: ElementRef<HTMLInputElement>;

  readonly over = signal(false);
  readonly maxSize = formatBytes(MAX_ZIP_BYTES);
  private depth = 0;

  size = formatBytes;

  browse(): void {
    if (!this.disabled) {
      this.picker.nativeElement.click();
    }
  }

  picked(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.accept(input.files ? Array.from(input.files) : []);
    input.value = ''; // picking the same file again must still fire a change
  }

  drag(event: DragEvent, entering: boolean): void {
    event.preventDefault();
    if (this.disabled) {
      return;
    }
    if (event.type === 'dragenter') {
      this.depth++;
    } else if (event.type === 'dragleave') {
      this.depth = Math.max(0, this.depth - 1);
    }
    this.over.set(entering ? true : this.depth > 0);
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy';
    }
  }

  drop(event: DragEvent): void {
    event.preventDefault();
    this.depth = 0;
    this.over.set(false);
    if (!this.disabled) {
      this.accept(Array.from(event.dataTransfer?.files ?? []));
    }
  }

  clear(event: Event): void {
    event.stopPropagation();
    this.fileChange.emit(null);
  }

  /** Validates and emits a ZIP; also used by the page-wide drop overlay through the same rules. */
  accept(files: File[]): void {
    if (files.length === 0) {
      return;
    }
    const problem = zipProblem(files[0]);
    if (problem) {
      this.rejected.emit(problem);
      return;
    }
    if (files.length > 1) {
      this.rejected.emit(`Only one ZIP at a time: using ${files[0].name}.`);
    }
    this.fileChange.emit(files[0]);
  }
}
