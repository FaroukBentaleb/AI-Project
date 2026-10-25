import { Component } from '@angular/core';
import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { InAppHistory } from './in-app-history.service';

@Component({ standalone: true, template: '' })
class StubComponent {}

describe('InAppHistory', () => {
  let router: Router;
  let history: InAppHistory;
  const settle = () => new Promise((r) => setTimeout(r));

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: '**', component: StubComponent }]), provideLocationMocks(), InAppHistory]
    });
    router = TestBed.inject(Router);
    router.initialNavigation(); // sets up the popstate listener, as bootstrap does
    await router.navigateByUrl('/sql/queries/new');
    history = TestBed.inject(InAppHistory);
  });

  it('has no in-app back on the entry page and falls back', async () => {
    expect(history.canGoBack()).toBeFalse();
    history.back('/sql/library');
    await settle();
    expect(router.url).toBe('/sql/library');
  });

  it('goes back after an in-app push', async () => {
    await router.navigateByUrl('/sql/library?q=x');
    await router.navigateByUrl('/sql/queries/new');
    expect(history.canGoBack()).toBeTrue();
    history.back('/sql/library');
    await settle();
    await settle();
    expect(router.url).toBe('/sql/library?q=x');
    expect(history.lastLibraryUrl()).toBe('/sql/library?q=x');
  });

  it('does not go back past the entry page after returning to it with Back (3.4 fix)', async () => {
    await router.navigateByUrl('/sql/library');
    TestBed.inject(Location).back();
    await settle();
    await settle();
    expect(router.url).toBe('/sql/queries/new');
    expect(history.canGoBack()).toBeFalse();
  });

  it('keeps the depth on a replaceUrl navigation', async () => {
    await router.navigateByUrl('/sql/library', { replaceUrl: true });
    expect(history.canGoBack()).toBeFalse();
  });
});
