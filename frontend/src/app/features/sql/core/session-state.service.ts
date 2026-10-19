import { Injectable, signal } from '@angular/core';
import { ValidateResponse } from './models';
import type { SaveDraft } from '../save/save-form';

export type SaveSource = 'validate';

/**
 * Module session state (EXPERIENCE.md Foundation). Provided on the SQL shell route, so it survives in-app
 * navigation and is cleared by a full reload. Holds the last Validate input and result, and a one-shot
 * Save prefill handed over to the Save form (never through the URL). Epic 7 adds the Generate fields.
 */
@Injectable()
export class SqlSessionState {
  readonly validateInput = signal('');
  readonly validateResult = signal<ValidateResponse | null>(null);

  private prefill: { from: SaveSource; draft: Partial<SaveDraft> } | null = null;

  offerPrefill(from: SaveSource, draft: Partial<SaveDraft>): void {
    this.prefill = { from, draft };
  }

  /** Returns and clears the pending prefill if it came from `from`. */
  takePrefill(from: string | null): Partial<SaveDraft> | null {
    const p = this.prefill;
    if (!p || p.from !== from) {
      return null;
    }
    this.prefill = null;
    return p.draft;
  }
}
