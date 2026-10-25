import { HttpEventType } from '@angular/common/http';
import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, computed, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { CommitsApiService, errorMessage } from './commits-api.service';
import { formatBytes, gitCommand, splitMessage, timeAgo } from './commit-format';
import { CommitDetail, CommitSummary } from './commits.models';
import { DiffViewerComponent } from './diff-viewer.component';
import { parseDiff } from './diff-parser';
import { DropZoneComponent } from './drop-zone.component';

const LAST_PROJECT_KEY = 'commits.lastProject';

type Phase = 'idle' | 'uploading' | 'analysing';

function hasFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files');
}

@Component({
  selector: 'app-commits-page',
  standalone: true,
  imports: [DropZoneComponent, DiffViewerComponent],
  templateUrl: './commits-page.component.html',
  styleUrl: './commits-page.component.css'
})
export class CommitsPageComponent implements OnInit, OnDestroy {
  @ViewChild(DropZoneComponent, { static: true }) private zone!: DropZoneComponent;
  @ViewChild('result') private resultCard?: ElementRef<HTMLElement>;

  readonly project = signal(readLastProject());
  readonly file = signal<File | null>(null);
  readonly phase = signal<Phase>('idle');
  readonly progress = signal(0);
  readonly error = signal('');
  readonly current = signal<CommitDetail | null>(null);
  readonly history = signal<CommitSummary[]>([]);
  readonly projects = signal<string[]>([]);
  readonly filter = signal('');
  readonly editing = signal(false);
  readonly draft = signal('');
  readonly copied = signal<'' | 'message' | 'command'>('');
  readonly confirmDeleteId = signal<number | null>(null);
  readonly pageDragging = signal(false);

  readonly busy = computed(() => this.phase() !== 'idle');
  readonly canGenerate = computed(() => !this.busy() && this.file() !== null && this.project().trim().length > 0);
  readonly stats = computed(() => {
    const detail = this.current();
    const files = detail ? parseDiff(detail.diff) : [];
    return {
      additions: files.reduce((sum, f) => sum + f.additions, 0),
      deletions: files.reduce((sum, f) => sum + f.deletions, 0)
    };
  });

  readonly split = splitMessage;
  readonly gitCommand = gitCommand;
  readonly bytes = formatBytes;
  readonly ago = (iso: string) => timeAgo(iso);

  private upload?: Subscription;
  private pageDepth = 0;
  private copyTimer?: ReturnType<typeof setTimeout>;

  constructor(private readonly api: CommitsApiService) {}

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.upload?.unsubscribe();
    clearTimeout(this.copyTimer);
  }

  // A ZIP can be dropped anywhere on the page, not only on the zone; a miss must not make the browser open the file.
  @HostListener('window:dragenter', ['$event'])
  onWindowDragEnter(event: DragEvent): void {
    if (hasFiles(event)) {
      event.preventDefault();
      this.pageDepth++;
      this.pageDragging.set(!this.busy());
    }
  }

  @HostListener('window:dragover', ['$event'])
  onWindowDragOver(event: DragEvent): void {
    if (hasFiles(event)) {
      event.preventDefault();
    }
  }

  @HostListener('window:dragleave', ['$event'])
  onWindowDragLeave(event: DragEvent): void {
    if (hasFiles(event)) {
      this.pageDepth = Math.max(0, this.pageDepth - 1);
      if (this.pageDepth === 0) {
        this.pageDragging.set(false);
      }
    }
  }

  @HostListener('window:drop', ['$event'])
  onWindowDrop(event: DragEvent): void {
    if (!hasFiles(event)) {
      return;
    }
    // The zone handles drops on itself (it already called preventDefault); this takes the ones that land elsewhere.
    const handledByZone = event.defaultPrevented && !!(event.target as HTMLElement | null)?.closest?.('app-drop-zone');
    event.preventDefault();
    this.pageDepth = 0;
    this.pageDragging.set(false);
    if (!handledByZone && !this.busy()) {
      this.zone.accept(Array.from(event.dataTransfer?.files ?? []));
    }
  }

  setProject(value: string): void {
    this.project.set(value);
  }

  generate(): void {
    const file = this.file();
    if (!this.canGenerate() || !file) {
      return;
    }
    const project = this.project().trim();
    this.error.set('');
    this.progress.set(0);
    this.phase.set('uploading');
    this.upload = this.api.upload(project, file).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress) {
          const total = event.total ?? file.size;
          this.progress.set(total ? Math.min(100, Math.round((100 * event.loaded) / total)) : 0);
          if (total && event.loaded >= total) {
            this.phase.set('analysing');
          }
        } else if (event.type === HttpEventType.Response && event.body) {
          this.phase.set('idle');
          this.file.set(null);
          rememberProject(project);
          this.show(event.body);
          this.refresh();
          setTimeout(() => this.resultCard?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' }));
        }
      },
      error: (e) => {
        this.phase.set('idle');
        this.error.set(errorMessage(e));
      }
    });
  }

  open(id: number): void {
    this.error.set('');
    this.api.get(id).subscribe({
      next: (detail) => {
        this.show(detail);
        setTimeout(() => this.resultCard?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' }));
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  startEdit(): void {
    this.draft.set(this.current()?.message ?? '');
    this.editing.set(true);
  }

  cancelEdit(): void {
    this.editing.set(false);
  }

  saveEdit(): void {
    const detail = this.current();
    const message = this.draft().trim();
    if (!detail || !message || message === detail.message) {
      this.editing.set(false);
      return;
    }
    this.api.updateMessage(detail.id, message).subscribe({
      next: (updated) => {
        this.show(updated);
        this.refresh();
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  async copy(kind: 'message' | 'command', text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.copied.set(kind);
      clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => this.copied.set(''), 1600);
    } catch {
      this.error.set('Copy is not available in this browser; select the text and copy it manually.');
    }
  }

  remove(item: CommitSummary): void {
    this.confirmDeleteId.set(null);
    this.api.delete(item.id).subscribe({
      next: () => {
        if (this.current()?.id === item.id) {
          this.current.set(null);
        }
        this.refresh();
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  setFilter(project: string): void {
    this.filter.set(project);
    this.refresh();
  }

  typeOf(message: string): string {
    return splitMessage(message).type ?? 'plain';
  }

  private show(detail: CommitDetail): void {
    this.current.set(detail);
    this.editing.set(false);
    this.draft.set(detail.message);
  }

  private refresh(): void {
    this.api.list(this.filter() || undefined).subscribe({
      next: (items) => this.history.set(items),
      error: (e) => this.error.set(errorMessage(e))
    });
    this.api.projects().subscribe({ next: (names) => this.projects.set(names), error: () => undefined });
  }
}

function readLastProject(): string {
  try {
    return localStorage.getItem(LAST_PROJECT_KEY) ?? '';
  } catch {
    return '';
  }
}

function rememberProject(project: string): void {
  try {
    localStorage.setItem(LAST_PROJECT_KEY, project);
  } catch {
    // Storage can be unavailable (private mode); remembering the project is only a convenience.
  }
}
