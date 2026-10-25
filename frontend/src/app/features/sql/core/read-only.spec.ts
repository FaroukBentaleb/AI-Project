import { parseReason, resultHeader } from './read-only';

describe('read-only helpers', () => {
  it('splits "CODE: reason" into a chip code and a reason', () => {
    expect(parseReason('LOCKING_CLAUSE: FOR UPDATE takes row locks')).toEqual({ code: 'LOCKING_CLAUSE', reason: 'FOR UPDATE takes row locks' });
    expect(parseReason('SQL_PARSE_ERROR: Encountered ": x"')).toEqual({ code: 'SQL_PARSE_ERROR', reason: 'Encountered ": x"' });
  });

  it('keeps anything else whole, with no code', () => {
    expect(parseReason('Note: free text')).toEqual({ code: null, reason: 'Note: free text' });
    expect(parseReason('no prefix')).toEqual({ code: null, reason: 'no prefix' });
  });

  it('builds the result header', () => {
    expect(resultHeader(true, [])).toBe('✓ Accepted · single read-only SELECT');
    expect(resultHeader(false, [{ code: 'A', reason: 'a' }])).toBe('✕ Rejected · 1 problem');
    expect(resultHeader(false, [{ code: 'A', reason: 'a' }, { code: 'B', reason: 'b' }])).toBe('✕ Rejected · 2 problems');
  });
});
