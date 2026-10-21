import { Location } from '@angular/common';
import { DestroyRef, Injectable, inject } from '@angular/core';
import { NavigationEnd, NavigationStart, Router } from '@angular/router';

export const DEFAULT_LIBRARY_URL = '/sql/library';

/**
 * Knows whether the previous history entry belongs to this app session, so Cancel never leaves the app.
 * Each entry gets a depth keyed by the router navigationId: push +1, replace =, popstate restores the stored depth.
 * Entries from before tracking started (first page, other modules) count as depth 0.
 * Also remembers the last Library URL (with filters) for the breadcrumb and after a delete.
 */
@Injectable()
export class InAppHistory {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly depthById = new Map<number, number>();
  private depth = 0;
  private pending: { id: number; depth: number } | null = null;
  private libraryUrl = DEFAULT_LIBRARY_URL;

  constructor() {
    this.noteUrl(this.router.url);
    const sub = this.router.events.subscribe((e) => {
      if (e instanceof NavigationStart) {
        let depth: number;
        if (e.navigationTrigger === 'popstate') {
          depth = this.depthById.get(e.restoredState?.navigationId ?? -1) ?? 0;
        } else {
          const replace = !!this.router.getCurrentNavigation()?.extras.replaceUrl || e.url === this.router.url;
          depth = replace ? this.depth : this.depth + 1;
        }
        this.pending = { id: e.id, depth };
      } else if (e instanceof NavigationEnd) {
        if (this.pending?.id === e.id) {
          this.depth = this.pending.depth;
          this.depthById.set(e.id, this.depth);
        }
        this.pending = null;
        this.noteUrl(e.urlAfterRedirects);
      }
    });
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
  }

  canGoBack(): boolean {
    return this.depth > 0;
  }

  /** Browser Back when the previous entry is in-app; otherwise navigate to `fallback`. */
  back(fallback: string): void {
    if (this.canGoBack()) {
      this.location.back();
    } else {
      this.router.navigateByUrl(fallback);
    }
  }

  lastLibraryUrl(): string {
    return this.libraryUrl;
  }

  private noteUrl(url: string): void {
    if (/^\/sql\/library(\?|$)/.test(url)) {
      this.libraryUrl = url;
    }
  }
}
