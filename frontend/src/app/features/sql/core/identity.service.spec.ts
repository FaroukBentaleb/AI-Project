import { TestBed } from '@angular/core/testing';
import { DISPLAY_NAME_KEY, IdentityService, normalizeDisplayName } from './identity.service';

describe('IdentityService', () => {
  beforeEach(() => localStorage.removeItem(DISPLAY_NAME_KEY));
  afterEach(() => localStorage.removeItem(DISPLAY_NAME_KEY));

  const create = () => TestBed.inject(IdentityService);

  it('has no identity on first visit', () => {
    const service = create();
    expect(service.hasIdentity()).toBeFalse();
    expect(service.displayName()).toBeNull();
  });

  it('reads a stored name', () => {
    localStorage.setItem(DISPLAY_NAME_KEY, 'sami');
    expect(create().displayName()).toBe('sami');
  });

  it('ignores a stored blank name', () => {
    localStorage.setItem(DISPLAY_NAME_KEY, '   ');
    expect(create().hasIdentity()).toBeFalse();
  });

  it('trims, stores and exposes a new name', () => {
    const service = create();
    expect(service.setDisplayName('  sami  ')).toBeTrue();
    expect(service.displayName()).toBe('sami');
    expect(localStorage.getItem(DISPLAY_NAME_KEY)).toBe('sami');
  });

  it('rejects blank and over-long names without changing the current one', () => {
    const service = create();
    service.setDisplayName('leila');
    expect(service.setDisplayName('   ')).toBeFalse();
    expect(service.setDisplayName('x'.repeat(101))).toBeFalse();
    expect(service.displayName()).toBe('leila');
  });

  it('accepts exactly 100 characters', () => {
    expect(normalizeDisplayName('x'.repeat(100))).toBe('x'.repeat(100));
  });

  it('keeps the name in memory when storage throws', () => {
    spyOn(localStorage, 'getItem').and.throwError('blocked');
    spyOn(localStorage, 'setItem').and.throwError('blocked');
    const service = create();
    expect(service.hasIdentity()).toBeFalse();
    expect(service.setDisplayName('sami')).toBeTrue();
    expect(service.displayName()).toBe('sami');
  });
});
