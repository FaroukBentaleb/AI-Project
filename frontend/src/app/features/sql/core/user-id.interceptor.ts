import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { IdentityService } from './identity.service';
import { SQL_API_BASE } from './sql-api.config';

export const USER_ID_HEADER = 'X-User-Id';

/** Adds `X-User-Id` to calls under the SQL gateway route only; other URLs pass through untouched. */
export const userIdInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(`${SQL_API_BASE}/`)) {
    return next(req);
  }
  const name = inject(IdentityService).displayName();
  return next(name ? req.clone({ setHeaders: { [USER_ID_HEADER]: name } }) : req);
};
