import { Injectable, computed, signal } from '@angular/core';

export const DISPLAY_NAME_KEY = 'devassist.sql.displayName';
export const DISPLAY_NAME_MAX = 100;

/** Normalizes a display name; returns null when it is blank or too long. */
export function normalizeDisplayName(raw: string | null | undefined): string | null {
  const name = (raw ?? '').trim();
  return name.length >= 1 && name.length <= DISPLAY_NAME_MAX ? name : null;
}

/** Holds the display name sent as `X-User-Id` (FR-33). Falls back to memory when storage is unavailable. */
@Injectable({ providedIn: 'root' })
export class IdentityService {
  private readonly name = signal<string | null>(IdentityService.read());

  readonly displayName = this.name.asReadonly();
  readonly hasIdentity = computed(() => this.name() !== null);

  /** Stores a new display name. Returns false (and changes nothing) when it is invalid. */
  setDisplayName(raw: string): boolean {
    const name = normalizeDisplayName(raw);
    if (name === null) {
      return false;
    }
    try {
      localStorage.setItem(DISPLAY_NAME_KEY, name);
    } catch {
      // Storage blocked or full: keep the name for this session only.
    }
    this.name.set(name);
    return true;
  }

  private static read(): string | null {
    try {
      return normalizeDisplayName(localStorage.getItem(DISPLAY_NAME_KEY));
    } catch {
      return null;
    }
  }
}
