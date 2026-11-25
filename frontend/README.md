# TMC Attendance Console

React and TanStack Start frontend for the TMC QR access and attendance Edge API.

## Run

1. Copy `.env.example` to `.env` and set `VITE_EDGE_API_URL` to the Edge Agent URL.
2. Install the locked dependencies with `npm ci` (Node.js 24).
3. Start the development server with `npm run dev`.
4. For the attendance tab, enter the corresponding demo bearer token configured on the backend. The download action requires the HR token.

## API modules

- `src/modules/access/api`: gate verification with station, correlation, and idempotency headers.
- `src/modules/userqr/api`: token issue and credential revoke.
- `src/modules/attendance/api`: attendance record query.
- `src/modules/shared/http`: common JSON request/error handling.

| Action | Request |
|---|---|
| Issue a short-lived QR | `POST /qr/v1/tokens` |
| Revoke a credential | `POST /qr/v1/credentials/{id}/revoke` |
| Verify an access scan | `POST /access/v1/verify` |
| Load attendance records | `GET /attendance/v1/records` |
| Download monthly attendance CSV | `GET /reports/v1/exports?month=YYYY-MM` |

Run `npm test`, `npm run typecheck`, and `npm run build` after installing dependencies. The package names and versions are pinned in `package-lock.json`.

The local demo API simulates station authentication, user authentication, and Turnstile behavior. Do not use production employee data with the demo configuration.
