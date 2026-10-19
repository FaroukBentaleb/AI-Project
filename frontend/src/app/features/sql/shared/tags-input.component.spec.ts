import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TagsInputComponent } from './tags-input.component';

describe('TagsInputComponent', () => {
  let fixture: ComponentFixture<TagsInputComponent>;
  let emitted: string[][];
  let input: HTMLInputElement;

  beforeEach(() => {
    fixture = TestBed.createComponent(TagsInputComponent);
    emitted = [];
    fixture.componentInstance.tagsChange.subscribe((t) => emitted.push(t));
    fixture.componentRef.setInput('tags', ['billing']);
    fixture.detectChanges();
    input = fixture.nativeElement.querySelector('input');
  });

  const key = (k: string) => input.dispatchEvent(new KeyboardEvent('keydown', { key: k, cancelable: true }));

  it('commits on Enter and comma, trimmed and lowercased, ignoring duplicates', () => {
    input.value = '  KPI ';
    key('Enter');
    input.value = 'kpi';
    key(',');
    expect(emitted).toEqual([['billing', 'kpi']]);
    expect(input.value).toBe('');
  });

  it('removes the last chip with Backspace on an empty input', () => {
    key('Backspace');
    expect(emitted).toEqual([[]]);
  });

  it('removes a chip with its labelled button', () => {
    fixture.nativeElement.querySelector('[aria-label="Remove tag billing"]').click();
    expect(emitted).toEqual([[]]);
  });

  it('refuses tags past the limit', () => {
    fixture.componentRef.setInput('maxTags', 1);
    const rejected: string[] = [];
    fixture.componentInstance.rejected.subscribe((m) => rejected.push(m));
    input.value = 'more';
    key('Enter');
    expect(emitted).toEqual([]);
    expect(rejected).toEqual(['Up to 1 tags.']);
    expect(input.value).toBe('more');
  });
});
