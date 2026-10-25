import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { DISPLAY_NAME_KEY, IdentityService } from '../core/identity.service';
import { SQL_ROUTES } from '../sql.routes';

describe('SqlShellComponent', () => {
  beforeEach(() => {
    localStorage.removeItem(DISPLAY_NAME_KEY);
    // The module provides its own HttpClient on the route; add the testing backend to that same injector
    // so the real Library's requests stay in memory and never touch the network.
    const [root, ...rest] = SQL_ROUTES;
    const routes = [{ ...root, providers: [...(root.providers ?? []), provideHttpClientTesting()] }, ...rest];
    TestBed.configureTestingModule({ providers: [provideRouter([{ path: 'sql', children: routes }])] });
  });
  afterEach(() => localStorage.removeItem(DISPLAY_NAME_KEY));

  const el = (h: RouterTestingHarness) => h.routeNativeElement as HTMLElement;
  const dialog = (h: RouterTestingHarness) => el(h).querySelector('[role="dialog"]');

  async function typeAndSubmit(h: RouterTestingHarness, value: string): Promise<void> {
    const input = el(h).querySelector<HTMLInputElement>('#sql-identity-name')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    h.detectChanges();
    el(h).querySelector<HTMLFormElement>('[role="dialog"] form')!.dispatchEvent(new Event('submit'));
    h.detectChanges();
    await h.fixture.whenStable();
  }

  it('asks for a name on first visit and gates the screen until it is given', async () => {
    const h = await RouterTestingHarness.create('/sql');
    expect(dialog(h)).not.toBeNull();
    expect(el(h).querySelector('sql-library')).toBeNull();
    expect(el(h).querySelector('[role="dialog"] .btn-secondary')).toBeNull();
    const cont = el(h).querySelector<HTMLButtonElement>('[role="dialog"] button[type="submit"]')!;
    expect(cont.disabled).toBeTrue();

    // Esc does not dismiss on first visit.
    dialog(h)!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    h.detectChanges();
    expect(dialog(h)).not.toBeNull();

    await typeAndSubmit(h, '  sami ');
    expect(dialog(h)).toBeNull();
    expect(localStorage.getItem(DISPLAY_NAME_KEY)).toBe('sami');
    expect(el(h).querySelector('.user-name')!.textContent).toContain('sami');
    expect(el(h).querySelector('sql-library h1')!.textContent).toContain('Library');
  });

  it('changes the name from the header menu', async () => {
    TestBed.inject(IdentityService).setDisplayName('sami');
    const h = await RouterTestingHarness.create('/sql/library');
    expect(dialog(h)).toBeNull();
    // The Library's load went to the in-memory backend, not the network.
    const backend = h.routeDebugElement!.injector.get(HttpTestingController);
    expect(backend.match((r) => r.url.endsWith('/sql/queries')).length).toBe(1);

    const chip = el(h).querySelector<HTMLButtonElement>('.user-chip')!;
    chip.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    h.detectChanges();
    expect(chip.getAttribute('aria-expanded')).toBe('true');
    el(h).querySelector<HTMLButtonElement>('[role="menuitem"]')!.click();
    h.detectChanges();

    expect(el(h).querySelector<HTMLInputElement>('#sql-identity-name')!.value).toBe('sami');
    expect(el(h).querySelector('[role="dialog"] .btn-secondary')).not.toBeNull();
    await typeAndSubmit(h, 'leila');
    expect(TestBed.inject(IdentityService).displayName()).toBe('leila');
    expect(dialog(h)).toBeNull();
  });

  it('cancels a name change with Esc', async () => {
    TestBed.inject(IdentityService).setDisplayName('sami');
    const h = await RouterTestingHarness.create('/sql/library');
    el(h).querySelector<HTMLButtonElement>('.user-chip')!.click();
    h.detectChanges();
    el(h).querySelector<HTMLButtonElement>('[role="menuitem"]')!.click();
    h.detectChanges();
    dialog(h)!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    h.detectChanges();
    expect(dialog(h)).toBeNull();
    expect(TestBed.inject(IdentityService).displayName()).toBe('sami');
  });

  it('keeps Library active on detail routes', async () => {
    TestBed.inject(IdentityService).setDisplayName('sami');
    const h = await RouterTestingHarness.create('/sql/queries/5');
    const library = Array.from(el(h).querySelectorAll('.tab')).find((t) => t.textContent!.includes('Library'))!;
    expect(library.classList).toContain('active');
    expect(library.getAttribute('aria-current')).toBe('page');
  });
});
