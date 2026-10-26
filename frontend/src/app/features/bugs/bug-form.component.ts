import { Component, EventEmitter, Input, OnChanges, Output, computed, signal } from '@angular/core';
import { BugsApiService, errorCode, errorMessage } from './bugs-api.service';
import { blankToNull, categoryLabel, parseTags, severityLabel, statusLabel } from './bug-format';
import {
  Bug, BugInput, CATEGORIES, Category, SEVERITIES, STATUSES, Severity, Status, TriageResult, TriageStatus
} from './bugs.models';

/** Create / edit form with the AI triage: "Analyze" suggests severity, category and title from the report. */
@Component({
  selector: 'app-bug-form',
  standalone: true,
  templateUrl: './bug-form.component.html',
  styleUrls: ['./bugs-shared.css', './bug-form.component.css']
})
export class BugFormComponent implements OnChanges {
  /** The bug to edit, or null to create one. */
  @Input() bug: Bug | null = null;
  @Input() triageStatus: TriageStatus | null = null;
  @Output() saved = new EventEmitter<Bug>();
  @Output() cancelled = new EventEmitter<void>();

  readonly severities = SEVERITIES;
  readonly categories = CATEGORIES;
  readonly statuses = STATUSES;
  readonly sevLabel = severityLabel;
  readonly catLabel = categoryLabel;
  readonly stLabel = statusLabel;

  readonly title = signal('');
  readonly description = signal('');
  readonly steps = signal('');
  readonly expected = signal('');
  readonly actual = signal('');
  readonly stackTrace = signal('');
  readonly environment = signal('');
  readonly appVersion = signal('');
  readonly reporter = signal('');
  readonly assignee = signal('');
  readonly tags = signal('');
  readonly severity = signal<Severity>('MEDIUM');
  readonly category = signal<Category>('OTHER');
  readonly status = signal<Status>('OPEN');
  readonly showDetails = signal(false);

  readonly saving = signal(false);
  readonly analysing = signal(false);
  readonly error = signal('');
  readonly fieldError = signal('');
  readonly aiError = signal('');
  readonly suggestion = signal<TriageResult | null>(null);

  readonly editing = computed(() => this.bug !== null);
  readonly canAnalyse = computed(() => !this.analysing() && this.description().trim().length >= 10);
  readonly canSave = computed(() => !this.saving() && this.title().trim().length >= 3
    && this.description().trim().length > 0);
  readonly suggestionApplied = computed(() => {
    const s = this.suggestion();
    return !!s && s.severity === this.severity() && s.category === this.category() && s.suggested_title === this.title();
  });

  constructor(private readonly api: BugsApiService) {}

  ngOnChanges(): void {
    const b = this.bug;
    this.title.set(b?.title ?? '');
    this.description.set(b?.description ?? '');
    this.steps.set(b?.steps_to_reproduce ?? '');
    this.expected.set(b?.expected_result ?? '');
    this.actual.set(b?.actual_result ?? '');
    this.stackTrace.set(b?.stack_trace ?? '');
    this.environment.set(b?.environment ?? '');
    this.appVersion.set(b?.app_version ?? '');
    this.reporter.set(b?.reporter ?? '');
    this.assignee.set(b?.assignee ?? '');
    this.tags.set(b?.tags.join(', ') ?? '');
    this.severity.set(b?.severity ?? 'MEDIUM');
    this.category.set(b?.category ?? 'OTHER');
    this.status.set(b?.status ?? 'OPEN');
    this.showDetails.set(!!(b && (b.steps_to_reproduce || b.expected_result || b.actual_result || b.stack_trace
      || b.environment || b.app_version)));
    this.suggestion.set(null);
    this.error.set('');
    this.fieldError.set('');
    this.aiError.set('');
  }

  /** Bound to (input)/(change) of a field: `set(this.title, $event)`. */
  set(target: { set(value: string): void }, event: Event): void {
    target.set((event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).value);
  }

  setSeverity(event: Event): void {
    this.severity.set((event.target as HTMLSelectElement).value as Severity);
  }

  setCategory(event: Event): void {
    this.category.set((event.target as HTMLSelectElement).value as Category);
  }

  setStatus(event: Event): void {
    this.status.set((event.target as HTMLSelectElement).value as Status);
  }

  analyse(): void {
    if (!this.canAnalyse()) {
      return;
    }
    this.analysing.set(true);
    this.aiError.set('');
    this.api.triage({
      title: blankToNull(this.title()),
      description: this.description().trim(),
      steps_to_reproduce: blankToNull(this.steps()),
      expected_result: blankToNull(this.expected()),
      actual_result: blankToNull(this.actual()),
      stack_trace: blankToNull(this.stackTrace()),
      environment: blankToNull(this.environment()),
      app_version: blankToNull(this.appVersion())
    }).subscribe({
      next: (result) => {
        this.analysing.set(false);
        this.suggestion.set(result);
      },
      error: (e) => {
        this.analysing.set(false);
        this.aiError.set(aiHint(errorCode(e), errorMessage(e)));
      }
    });
  }

  applyAll(): void {
    const s = this.suggestion();
    if (s) {
      this.severity.set(s.severity);
      this.category.set(s.category);
      this.title.set(s.suggested_title);
    }
  }

  save(): void {
    if (!this.canSave()) {
      return;
    }
    const input: BugInput = {
      title: this.title().trim(),
      description: this.description().trim(),
      steps_to_reproduce: blankToNull(this.steps()),
      expected_result: blankToNull(this.expected()),
      actual_result: blankToNull(this.actual()),
      stack_trace: blankToNull(this.stackTrace()),
      environment: blankToNull(this.environment()),
      app_version: blankToNull(this.appVersion()),
      reporter: blankToNull(this.reporter()),
      assignee: blankToNull(this.assignee()),
      tags: parseTags(this.tags()),
      severity: this.severity(),
      category: this.category(),
      status: this.status()
    };
    this.saving.set(true);
    this.error.set('');
    const call = this.bug ? this.api.update(this.bug.id, input) : this.api.create(input);
    call.subscribe({
      next: (bug) => {
        this.saving.set(false);
        this.saved.emit(bug);
      },
      error: (e) => {
        this.saving.set(false);
        this.error.set(errorMessage(e));
        this.fieldError.set((e?.error?.field as string | undefined) ?? '');
      }
    });
  }
}

/** A sentence telling the user what to do about an AI failure. */
export function aiHint(code: string, message: string): string {
  switch (code) {
    case 'LLM_NOT_CONFIGURED':
      return 'AI triage is off: no model key is configured on bug-service. You can still fill the fields yourself.';
    case 'LLM_QUOTA_EXCEEDED':
      return `${message}. You can still classify the bug yourself.`;
    case 'LLM_TIMEOUT':
    case 'LLM_UNAVAILABLE':
      return `${message}.`;
    default:
      return message;
  }
}
