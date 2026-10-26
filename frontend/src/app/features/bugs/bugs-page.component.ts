import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { BugFormComponent, aiHint } from './bug-form.component';
import { BugsApiService, errorCode, errorMessage } from './bugs-api.service';
import { categoryLabel, severityLabel, statusLabel, timeAgo } from './bug-format';
import {
  Bug, BugFilter, BugInput, BugStats, CATEGORIES, Category, Page, SEVERITIES, SORTS, STATUSES, Severity, Sort, Status,
  TriageStatus
} from './bugs.models';

type Mode = 'none' | 'view' | 'create' | 'edit';

const SORT_LABELS: Record<Sort, string> = {
  NEWEST: 'Newest first', OLDEST: 'Oldest first', UPDATED: 'Recently updated', SEVERITY: 'Severity'
};

/** Next step of the workflow for the quick status button of the detail view. */
const NEXT_STATUS: Record<Status, Status> = {
  OPEN: 'IN_PROGRESS', IN_PROGRESS: 'RESOLVED', RESOLVED: 'CLOSED', CLOSED: 'OPEN'
};

function toInput(bug: Bug): BugInput {
  return {
    title: bug.title, description: bug.description, steps_to_reproduce: bug.steps_to_reproduce,
    expected_result: bug.expected_result, actual_result: bug.actual_result, stack_trace: bug.stack_trace,
    environment: bug.environment, app_version: bug.app_version, severity: bug.severity, category: bug.category,
    status: bug.status, reporter: bug.reporter, assignee: bug.assignee, tags: bug.tags
  };
}

@Component({
  selector: 'app-bugs-page',
  standalone: true,
  imports: [BugFormComponent],
  templateUrl: './bugs-page.component.html',
  styleUrls: ['./bugs-shared.css', './bugs-page.component.css']
})
export class BugsPageComponent implements OnInit, OnDestroy {
  readonly severities = SEVERITIES;
  readonly categories = CATEGORIES;
  readonly statuses = STATUSES;
  readonly sorts = SORTS;
  readonly sortLabel = (s: Sort) => SORT_LABELS[s];
  readonly sevLabel = severityLabel;
  readonly catLabel = categoryLabel;
  readonly stLabel = statusLabel;
  readonly ago = (iso: string) => timeAgo(iso);
  readonly nextStatus = (s: Status) => NEXT_STATUS[s];

  readonly status = signal<Status | ''>('');
  readonly severity = signal<Severity | ''>('');
  readonly category = signal<Category | ''>('');
  readonly query = signal('');
  readonly sort = signal<Sort>('NEWEST');
  readonly pageIndex = signal(0);

  readonly page = signal<Page<Bug> | null>(null);
  readonly stats = signal<BugStats | null>(null);
  readonly triageStatus = signal<TriageStatus | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');

  readonly mode = signal<Mode>('none');
  readonly selected = signal<Bug | null>(null);
  readonly confirmDelete = signal(false);
  readonly retriaging = signal(false);
  readonly aiError = signal('');

  readonly filtered = computed(() => !!(this.status() || this.severity() || this.category() || this.query().trim()));
  readonly urgent = computed(() => {
    const s = this.stats();
    return s ? s.open_by_severity.CRITICAL + s.open_by_severity.HIGH : 0;
  });
  readonly aiDiffers = computed(() => {
    const b = this.selected();
    return !!b?.ai && (b.ai.severity !== b.severity || b.ai.category !== b.category || b.ai.suggested_title !== b.title);
  });

  private searchTimer?: ReturnType<typeof setTimeout>;

  constructor(private readonly api: BugsApiService) {}

  ngOnInit(): void {
    this.load();
    this.loadStats();
    this.api.triageStatus().subscribe({ next: (s) => this.triageStatus.set(s), error: () => undefined });
  }

  ngOnDestroy(): void {
    clearTimeout(this.searchTimer);
  }

  // ---- filters ----

  setStatusFilter(value: Status | ''): void {
    this.status.set(this.status() === value ? '' : value);
    this.reload();
  }

  setSeverityFilter(event: Event): void {
    this.severity.set((event.target as HTMLSelectElement).value as Severity | '');
    this.reload();
  }

  setCategoryFilter(event: Event): void {
    this.category.set((event.target as HTMLSelectElement).value as Category | '');
    this.reload();
  }

  setSort(event: Event): void {
    this.sort.set((event.target as HTMLSelectElement).value as Sort);
    this.reload();
  }

  onSearch(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.reload(), 300);
  }

  clearFilters(): void {
    this.status.set('');
    this.severity.set('');
    this.category.set('');
    this.query.set('');
    this.reload();
  }

  goToPage(index: number): void {
    this.pageIndex.set(index);
    this.load();
  }

  // ---- selection and editing ----

  open(bug: Bug): void {
    this.selected.set(bug);
    this.mode.set('view');
    this.confirmDelete.set(false);
    this.aiError.set('');
  }

  newBug(): void {
    this.selected.set(null);
    this.mode.set('create');
  }

  edit(): void {
    this.mode.set('edit');
  }

  closePane(): void {
    this.mode.set(this.mode() === 'edit' && this.selected() ? 'view' : 'none');
    if (this.mode() === 'none') {
      this.selected.set(null);
    }
  }

  onSaved(bug: Bug): void {
    this.open(bug);
    this.refresh();
  }

  advanceStatus(bug: Bug, status: Status): void {
    this.api.changeStatus(bug.id, status).subscribe({
      next: (updated) => {
        this.selected.set(updated);
        this.refresh();
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  remove(bug: Bug): void {
    this.api.delete(bug.id).subscribe({
      next: () => {
        this.selected.set(null);
        this.mode.set('none');
        this.confirmDelete.set(false);
        this.refresh();
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  /** Re-runs the AI on the stored report; the result is saved in the bug's `ai` block. */
  retriage(bug: Bug): void {
    this.retriaging.set(true);
    this.aiError.set('');
    this.api.triageStored(bug.id).subscribe({
      next: (updated) => {
        this.retriaging.set(false);
        this.selected.set(updated);
        this.refresh();
      },
      error: (e) => {
        this.retriaging.set(false);
        this.aiError.set(aiHint(errorCode(e), errorMessage(e)));
      }
    });
  }

  /** Copies the stored suggestion into the bug's own severity, category and title. */
  applyAi(bug: Bug): void {
    if (!bug.ai) {
      return;
    }
    const input = { ...toInput(bug), severity: bug.ai.severity, category: bug.ai.category, title: bug.ai.suggested_title };
    this.api.update(bug.id, input).subscribe({
      next: (updated) => {
        this.selected.set(updated);
        this.refresh();
      },
      error: (e) => this.error.set(errorMessage(e))
    });
  }

  // ---- data ----

  private reload(): void {
    this.pageIndex.set(0);
    this.load();
  }

  private refresh(): void {
    this.load();
    this.loadStats();
  }

  private load(): void {
    const filter: BugFilter = {
      status: this.status() || undefined,
      severity: this.severity() || undefined,
      category: this.category() || undefined,
      q: this.query() || undefined,
      sort: this.sort(),
      page: this.pageIndex()
    };
    this.loading.set(true);
    this.api.list(filter).subscribe({
      next: (page) => {
        this.loading.set(false);
        this.page.set(page);
        // Keep the open bug in sync with the list's fresher copy.
        const current = this.selected();
        const fresh = current && page.items.find((b) => b.id === current.id);
        if (fresh && this.mode() === 'view') {
          this.selected.set(fresh);
        }
      },
      error: (e) => {
        this.loading.set(false);
        this.error.set(errorMessage(e));
      }
    });
  }

  private loadStats(): void {
    this.api.stats().subscribe({ next: (s) => this.stats.set(s), error: () => undefined });
  }
}
