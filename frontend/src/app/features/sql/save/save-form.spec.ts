import { SavedQuery } from '../core/models';
import { SAVE_LIMITS, emptyDraft, firstInvalid, mutationToast, toCreateRequest, validateDraft } from './save-form';

describe('save-form', () => {
  const valid = () => ({ ...emptyDraft(), title: 'T', description: 'D', sql_content: 'SELECT 1' });

  it('mirrors the API limits', () => {
    expect(SAVE_LIMITS).toEqual({
      title: 200,
      description: 2000,
      sql_content: 20000,
      notes: 2000,
      original_nl_prompt: 1000,
      tags: 10,
      tag: 50
    });
  });

  it('requires non-blank title, description and SQL', () => {
    const errors = validateDraft({ ...emptyDraft(), title: '   ' });
    expect(Object.keys(errors)).toEqual(['title', 'description', 'sql_content']);
    expect(errors.title).toBe('Title is required.');
    expect(firstInvalid(errors)).toBe('title');
    expect(validateDraft(valid())).toEqual({});
  });

  it('accepts values at the limit and rejects one past it', () => {
    const at = { ...valid(), title: 'a'.repeat(200), description: 'a'.repeat(2000), sql_content: 'a'.repeat(20000), notes: 'a'.repeat(2000) };
    expect(validateDraft(at)).toEqual({});
    const over = { ...valid(), title: 'a'.repeat(201), description: 'a'.repeat(2001), sql_content: 'a'.repeat(20001), notes: 'a'.repeat(2001) };
    const errors = validateDraft(over);
    expect(errors.title).toBe('Title must be at most 200 characters.');
    expect(errors.sql_content).toBe('SQL must be at most 20,000 characters.');
    expect(errors.notes).toContain('2,000');
    expect(errors.description).toBeDefined();
  });

  it('limits tags to 10 of at most 50 chars', () => {
    const tags = Array.from({ length: 11 }, (_, i) => `t${i}`);
    expect(validateDraft({ ...valid(), tags }).tags).toBe('Up to 10 tags.');
    expect(validateDraft({ ...valid(), tags: ['a'.repeat(51)] }).tags).toContain('1–50');
    expect(validateDraft({ ...valid(), tags: tags.slice(0, 10) })).toEqual({});
  });

  it('builds the snake_case create body with defaults', () => {
    expect(toCreateRequest({ ...valid(), title: ' T ', notes: '  ' })).toEqual({
      title: 'T',
      description: 'D',
      sql_content: 'SELECT 1',
      tags: [],
      notes: null,
      dialect: 'postgresql',
      is_llm_generated: false,
      original_nl_prompt: null
    });
  });

  it('picks the toast from warnings[] first, then deprecated, then indexed', () => {
    const q = { warnings: [], deprecated: false } as unknown as SavedQuery;
    expect(mutationToast(q)).toEqual({ kind: 'success', text: 'Saved · added to team examples' });
    expect(mutationToast({ ...q, warnings: ['RAG down'] })).toEqual({
      kind: 'warning',
      text: 'Saved · not yet indexed (team examples unavailable)'
    });
    expect(mutationToast({ ...q, deprecated: true }).text).toBe('Saved');
    expect(mutationToast({ ...q, read_only: false } as SavedQuery).text).toBe('Saved');
    expect(mutationToast({ ...q, deprecated: true, warnings: ['x'] }).kind).toBe('warning');
  });
});
