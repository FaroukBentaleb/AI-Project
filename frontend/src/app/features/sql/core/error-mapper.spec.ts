import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ERROR_MESSAGES, GENERIC_ERROR_MESSAGE, toUiError } from './error-mapper';

const httpError = (status: number, error: unknown) =>
  new HttpErrorResponse({ status, error, headers: new HttpHeaders(), url: '/api/sql/queries' });

describe('toUiError', () => {
  it('maps a known code to the client message', () => {
    const ui = toUiError(httpError(404, { error: 'NOT_FOUND', message: 'Saved query 9 not found', field: null, details: null }));
    expect(ui).toEqual({ code: 'NOT_FOUND', message: "This query doesn't exist or was deleted.", field: null, status: 404, details: null });
  });

  it('maps CONFLICT', () => {
    expect(toUiError(httpError(409, { error: 'CONFLICT', message: 'x' })).message).toBe(ERROR_MESSAGES['CONFLICT']);
  });

  it('uses the server message and field for VALIDATION_FAILED', () => {
    const ui = toUiError(httpError(400, { error: 'VALIDATION_FAILED', message: 'title must not be blank', field: 'title', details: null }));
    expect(ui.message).toBe('title must not be blank');
    expect(ui.field).toBe('title');
    expect(ui.code).toBe('VALIDATION_FAILED');
  });

  it('falls back to generic for VALIDATION_FAILED without a message', () => {
    expect(toUiError(httpError(400, { error: 'VALIDATION_FAILED', message: '  ', field: 'sql' })).message).toBe(GENERIC_ERROR_MESSAGE);
  });

  it('parses an envelope delivered as a JSON string', () => {
    expect(toUiError(httpError(404, '{"error":"NOT_FOUND","message":"m"}')).message).toBe(ERROR_MESSAGES['NOT_FOUND']);
  });

  it('uses the generic message for an unknown code', () => {
    const ui = toUiError(httpError(500, { error: 'INTERNAL_ERROR', message: 'Unexpected error' }));
    expect(ui.message).toBe(GENERIC_ERROR_MESSAGE);
    expect(ui.code).toBe('INTERNAL_ERROR');
  });

  it('uses the generic message for a non-JSON body', () => {
    const ui = toUiError(httpError(502, '<html>Bad Gateway</html>'));
    expect(ui).toEqual({ code: null, message: GENERIC_ERROR_MESSAGE, field: null, status: 502, details: null });
  });

  it('uses the generic message for an object without an error code', () => {
    expect(toUiError(httpError(503, { status: 503, path: '/api/sql' })).message).toBe(GENERIC_ERROR_MESSAGE);
  });

  it('uses the generic message for a network failure', () => {
    const ui = toUiError(httpError(0, new ProgressEvent('error')));
    expect(ui.message).toBe(GENERIC_ERROR_MESSAGE);
    expect(ui.status).toBe(0);
  });

  it('uses the generic message for non-HTTP errors', () => {
    expect(toUiError(new Error('boom')).message).toBe(GENERIC_ERROR_MESSAGE);
    expect(toUiError(undefined).code).toBeNull();
  });
});
