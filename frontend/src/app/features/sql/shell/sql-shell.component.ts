import { Component, ElementRef, ViewChild, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';
import { IdentityService } from '../core/identity.service';
import { InAppHistory } from '../core/in-app-history.service';
import { IdentityModalComponent } from './identity-modal.component';
import { ToastComponent } from '../shared/toast.component';

/** SQL module frame: module header (tabs, + New query, user chip) and the identity gate (FR-33). */
@Component({
  selector: 'sql-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IdentityModalComponent, ToastComponent],
  template: `
    <header class="module-header">
      <span class="module-name">SQL</span>
      <nav class="tabs" aria-label="SQL module">
        <a class="tab" routerLink="library" [class.active]="libraryActive()" [attr.aria-current]="libraryActive() ? 'page' : null">Library</a>
        <a class="tab" routerLink="generate" routerLinkActive="active" ariaCurrentWhenActive="page">Generate</a>
        <a class="tab" routerLink="validate" routerLinkActive="active" ariaCurrentWhenActive="page">Validate</a>
      </nav>
      <span class="spacer"></span>
      <a class="btn btn-primary" routerLink="queries/new">+ New query</a>
      @if (identity.displayName(); as name) {
        <div class="user">
          <button
            #chip
            type="button"
            class="user-chip"
            aria-haspopup="menu"
            [attr.aria-expanded]="menuOpen()"
            aria-controls="sql-user-menu"
            (click)="toggleMenu()"
            (keydown)="onChipKeydown($event)"
          >
            <span class="avatar" aria-hidden="true">{{ name.charAt(0).toUpperCase() }}</span>
            <span class="user-name">{{ name }}</span>
            <span aria-hidden="true">▾</span>
          </button>
          @if (menuOpen()) {
            <div id="sql-user-menu" class="user-menu" role="menu" aria-label="User" (keydown)="onMenuKeydown($event)">
              <button #menuItem type="button" role="menuitem" class="menu-item" (click)="openChangeName()">Change display name</button>
            </div>
          }
        </div>
      }
    </header>

    <main class="content">
      @if (identity.hasIdentity()) {
        <router-outlet />
      }
    </main>

    <sql-toast />

    @if (!identity.hasIdentity() || changingName()) {
      <sql-identity-modal
        [firstVisit]="!identity.hasIdentity()"
        [initialName]="identity.displayName()"
        (saved)="onNameSaved($event)"
        (cancelled)="closeChangeName()"
      />
    }
  `,
  styleUrls: ['./sql-theme.css', './sql-shell.component.css']
})
export class SqlShellComponent {
  readonly identity = inject(IdentityService);
  private readonly router = inject(Router);
  /** Started here so in-app history depth is tracked from module entry. */
  private readonly history = inject(InAppHistory, { optional: true });

  readonly menuOpen = signal(false);
  readonly changingName = signal(false);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
      startWith(this.router.url)
    ),
    { initialValue: this.router.url }
  );

  /** Detail, Save and Edit live under the Library tab. */
  readonly libraryActive = computed(() => /^\/sql\/(library|queries)(\/|\?|$)/.test(this.url()));

  @ViewChild('chip') private chip?: ElementRef<HTMLButtonElement>;
  @ViewChild('menuItem') private menuItem?: ElementRef<HTMLButtonElement>;

  toggleMenu(): void {
    this.menuOpen() ? this.closeMenu(true) : this.openMenu();
  }

  onChipKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown' || ((event.key === 'Enter' || event.key === ' ') && !this.menuOpen())) {
      event.preventDefault();
      this.openMenu();
    } else if (event.key === 'Escape' && this.menuOpen()) {
      event.preventDefault();
      this.closeMenu(true);
    }
  }

  onMenuKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape' || event.key === 'Tab') {
      if (event.key === 'Escape') {
        event.preventDefault();
      }
      this.closeMenu(event.key === 'Escape');
    }
  }

  openChangeName(): void {
    this.closeMenu(false);
    this.changingName.set(true);
  }

  closeChangeName(): void {
    this.changingName.set(false);
    this.focusChip();
  }

  onNameSaved(name: string): void {
    this.identity.setDisplayName(name);
    this.changingName.set(false);
    this.focusChip();
  }

  private openMenu(): void {
    this.menuOpen.set(true);
    setTimeout(() => this.menuItem?.nativeElement.focus());
  }

  private closeMenu(restoreFocus: boolean): void {
    this.menuOpen.set(false);
    if (restoreFocus) {
      this.focusChip();
    }
  }

  private focusChip(): void {
    setTimeout(() => this.chip?.nativeElement.focus());
  }
}
