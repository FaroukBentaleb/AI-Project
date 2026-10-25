import { environment } from '../../../../environments/environment';

/** Gateway base for every SQL call (`/api/sql/**`). */
export const SQL_API_BASE = `${environment.apiBaseUrl}/sql`;
