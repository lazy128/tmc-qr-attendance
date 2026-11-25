# TMC QR Attendance & Access Control

Workspace layout for the M2 project. The backend and frontend are separate projects with their own run instructions.

## Projects

- [`backend/`](backend/README.md): Java 21, Spring Boot, H2 Edge API and domain modules.
- [`frontend/`](frontend/README.md): TMC operations console, split into Access, User QR, and Attendance modules.
- [`Phase1_Architecture.md`](Phase1_Architecture.md): architecture views, ADRs, traceability, assumptions, and NFR scenarios.

## Local API connection

In one PowerShell terminal, start the backend with Java 21 and the included Maven Wrapper:

```powershell
cd backend
$env:TMC_HR_DEMO_TOKEN = 'tmc-local-hr'
.\mvnw.cmd spring-boot:run
```

In a second terminal, start the frontend with Node.js 24 and npm:

```powershell
cd frontend
Copy-Item .env.example .env
npm ci
npm run dev
```

Open `http://localhost:5173`. On **Access check**, issue a QR token for the default demo credential, then verify before it expires. On **Attendance records**, enter the demo HR token, load records, choose the scan month, and download the CSV.

| Feature | Backend route |
|---|---|
| Issue QR token | `POST /qr/v1/tokens` |
| Revoke credential | `POST /qr/v1/credentials/{id}/revoke` |
| Verify gate access | `POST /access/v1/verify` |
| List authorized attendance records | `GET /attendance/v1/records` |
| Download an HR CSV for a month | `GET /reports/v1/exports?month=YYYY-MM` |

The demo uses simulated station authentication and Turnstile behavior. Do not use production employee data. Build and test status is recorded in `backend/README.md`.
