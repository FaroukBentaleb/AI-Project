import { SavedQuery } from '../core/models';
import { draftFromQuery, emptyDraft, mergeOnReload, moreSummary, toPatchRequest } from './save-form';

describe('save-form (edit)', () => {
  const base = () => ({ ...emptyDraft(), title: 'T', description: 'D', sql_content: 'SELECT 1', tags: ['a'], notes: 'n' });

  it('builds a draft from a saved query', () => {
    const q = { title: 'T', description: 'D', sql_content: 'S', tags: ['x'], notes: null, dialect: '', is_llm_generated: true, original_nl_prompt: 'p' } as unknown as SavedQuery;
    expect(draftFromQuery(q)).toEqual({ title: 'T', description: 'D', sql_content: 'S', tags: ['x'], notes: '', dialect: 'postgresql', is_llm_generated: true, original_nl_prompt: 'p' });
  });

  it('patches only changed fields plus version; clearing notes sends an empty string', () => {
    expect(toPatchRequest(base(), base(), 3)).toEqual({ version: 3 });
    const mine = { ...base(), title: ' New ', notes: '  ', tags: ['a', 'b'] };
    expect(toPatchRequest(mine, base(), 3)).toEqual({ version: 3, title: 'New', notes: '', tags: ['a', 'b'] });
  });

  it('merges on reload: untouched take server values, mine keep markers, both-changed carry the server value', () => {
    const mine = { ...base(), sql_content: 'SELECT 2', description: 'Mine' };
    const latest = { ...base(), description: 'Theirs', tags: ['a', 'new'] };
    const { draft, markers } = mergeOnReload(base(), mine, latest);
    expect(draft.tags).toEqual(['a', 'new']);
    expect(draft.sql_content).toBe('SELECT 2');
    expect(draft.description).toBe('Mine');
    expect(markers).toEqual({ sql_content: { server: null }, description: { server: 'Theirs' } });
  });

  it('drops the marker when the server already has my value', () => {
    const mine = { ...base(), title: 'Same' };
    expect(mergeOnReload(base(), mine, { ...base(), title: 'Same' }).markers).toEqual({});
  });

  it('summarizes More options', () => {
    expect(moreSummary(base())).toBe('1 tag · notes · PostgreSQL');
    expect(moreSummary(emptyDraft())).toBe('PostgreSQL');
  });
});
