# TMC QR Attendance Edge

Phase 2 demo for the M2 assignment. The Gate Station calls its local Edge Agent. Central synchronization runs through the outbox, outside the scan path.

## Run

Java 21 is required. The included wrapper resolves Maven 3.9.11 on the first run. Use `./mvnw` on Linux or macOS.

```powershell
$env:TMC_HR_DEMO_TOKEN = 'tmc-local-hr'
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

The demo binds to loopback and stores attempts, site-scoped JTI consumption, idempotency, audit, presence, credentials, attendance and export batches in H2. It seeds `demo-credential` / `demo-device` and `demo-credential-2` / `demo-device-2`. The current demo station is `GATE-1` at `SITE-1`. Use the frontend to issue a QR and submit a scan before expiry. For a second scan, issue a fresh token and switch direction or record a missed exit.

The packaged backend can also run with `java -jar target/qr-attendance-edge-1.0.0.jar` after `mvn verify`. The integration tests bind to an ephemeral loopback port. Run them in an environment that allows local sockets.

## Structure

Layered modular monolith (`api` → `application` → `domain` ← `infrastructure`). Spring stays in `api`, `infrastructure` JDBC adapters, and `tmc.bootstrap`.

- `tmc.access.domain` / `tmc.access.domain.port` — attempt model and one interface per client need
- `tmc.access.application` / `tmc.access.application.step` — verify use case and ordered verification steps
- `tmc.userqr` — issue/revoke QR
- `tmc.attendance` — eligibility and `AttendanceRecord` (consumes `attendance.contract`)
- `tmc.shared` — clock, IDs, audit, central sync simulation
- `tmc.bootstrap` — composition root, CORS, outbox/reconciliation workers

Frontend mirrors those modules under `../frontend/src/modules/{access,userqr,attendance,shared,console}`. The route file only composes the console.

Decision, command and passage are independent. ACK updates presence with `ACK_ONLY`. A station without a sensor reports `NOT_AVAILABLE`. With `tmc.edge.passage-sensor=true`, an attempt starts at `UNCONFIRMED` and waits for separate evidence. Attendance receives ACKED attempts and flags ACK_ONLY for HR review. The reconciler queries UNKNOWN or interrupted SENT commands by ID. A definite FAILED command stays available in the access and sync history. JTI uniqueness at Edge is per site. Central flags cross-site reuse as `SUSPICIOUS` after sync.

## Demo access and export

| Demo identity | Environment variable | Access |
|---|---|---|
| Employee | `TMC_EMPLOYEE_DEMO_TOKEN` | Own attendance |
| Manager | `TMC_MANAGER_DEMO_TOKEN` | Assigned team attendance |
| HR | `TMC_HR_DEMO_TOKEN` | Authorized attendance and CSV |
| Assigned guard | `TMC_GUARD_DEMO_TOKEN` | Passage evidence and missed-exit correction at the assigned station |
| System Administrator | `TMC_ADMIN_DEMO_TOKEN` | Configuration permission in the policy port, with attendance denied |

Set distinct demo tokens for identities you want to use. A blank token disables that identity. Pass the token as `Authorization: Bearer <token>`. For a missed exit, call `POST /gates/v1/presence-corrections` with the guard token, `X-Station-Id: GATE-1`, and `{"lastEntryEventId":"<eventId>","reason":"MISSED_EXIT"}`. The correction updates current presence and appends one audit event without creating a payroll scan.

HR can download a CSV for a selected month at `GET /reports/v1/exports?month=YYYY-MM`. It excludes unresolved, failed and suspicious records and carries the review flag. The scheduled demo makes one durable batch for the previous month. The default job runs every five minutes on day one in `Asia/Ho_Chi_Minh`, and H2 stores the ready, sent and simulated ACK timestamps. A real payroll receiver, holiday rules and reconciliation after a missed deadline still require agreement. Disable the job with `tmc.hr.schedule=-`.

## SOLID evidence

| Principle | Code evidence | Test evidence | Before → after refactor |
|---|---|---|---|
| SRP | One class per `VerificationStep` under `application.step`; `RecordAttendance` records eligibility only. | `AccessAttemptTest`, planned per-step coverage. | Bad: `verifyScan()` validates QR, checks policy, writes DB and opens gate in one method. After: `TokenSignatureAndTimeStep` → `PolicyStep` → `PersistAndConsumeStep` → `GateCommandStep`. |
| OCP | `VerifyAccessUseCase` sorts an injected `List<VerificationStep>` by `order()`. Policy and eligibility are strategies. | `VerificationPipelineTest` injects an extra step. | A fixed sequence of validation branches becomes an ordered set of replaceable steps. |
| LSP | `GateCommandSender`, the fake and simulator, and the HTTP skeleton. | All three run through `GateCommandSenderContractTest`. The HTTP skeleton returns FAILED without sending until the vendor contract is available. | Outcome handling uses only the port's return value. Separate evidence supplies passage. |
| ISP | One port type per file in `tmc.access.domain.port` (`GateCommandSender`, `CommandStatusReader`, `PassageEvidenceListener`, credential/token/repository). | Contract test focuses on the command port. | Bad: broad `GateService` with send/read/listen/configure. After: separate interfaces for each client need. |
| DIP | Application depends on `VerificationStep`, domain ports, `ClockProvider`, `IdGenerator`; composition root wires adapters. | `ArchitectureRulesTest` checks no Spring/infrastructure dependency in domain/application. | Bad: use case constructs JDBC/vendor adapters and calls system time. After: inject ports and clock. |

Some before examples are deliberately labeled anti-pattern examples for the assignment; they are explanatory snippets, not compiled source. The after behavior is represented by the linked implementation classes.

## Deviations from Phase 0–1

- This code follows the later Phase 2 instruction that Edge is the primary scan processor and Central is asynchronous.
- Device-signed offline QR is documented only; the implemented issuance path signs short-lived QR tokens centrally.
- The isolated demo reads credentials and revocations directly from local H2. For snapshot mode, configure `tmc.edge.snapshot-file` and supply a trusted, deployment-managed JSON file with `generatedAt`, `revoked`, and `stationSites` (one station-to-site map). Outdated or unavailable snapshots remain fail-closed. With local demo authority disabled and no file configured, scans fail closed.
- The Edge transaction writes attempt, used JTI, SENT, outbox and ALLOW audit together. A second transaction records the command outcome and updates presence. Attendance owns a separate table and consumes the outbox contract. PostgreSQL and recovery across a real controller restart remain outside the local test scope.
- Turnstile, station identity, end-user identity, and HR delivery use simulations. The Turnstile HTTP adapter is a skeleton pending a vendor contract.
- Enable asynchronous Central delivery with `tmc.central.enabled=true` and set `tmc.central.url`. The demo Central receiver deduplicates event IDs in memory. Durable Central processing and distributing the conflict flag back to other sites remain open.
- The demo policy is wired to Attendance and guard actions. Administrative role/configuration screens and the offline override workflow remain outside the Phase 2 scope. The override still needs TMC approval.
- The demo generates a signing key at startup. To load existing keys, set `tmc.qr.keystore` (PKCS#12), `TMC_QR_KEYSTORE_PASSWORD`, and `tmc.qr.signing-kid`. Public certificates for other configured EC aliases can verify prior tokens.
- Timeout/snapshot/TTL values are configurable design assumptions in the composition; production configuration and measured operating limits remain open.

## Assumptions and simulated parts

The proposed values are a 30-second token TTL, 5 seconds of clock skew, 15-minute snapshot freshness, an 800 ms Turnstile timeout, a 60-second idempotency lease, and a month boundary in `Asia/Ho_Chi_Minh`. TMC and HR still need to approve these settings. Revocations in the snapshot freshness window can still be accepted offline. Reuse across sites can be detected after sync.

## Test and load report

The local verification on Windows 11 used Java 21 and Node.js 24. `mvnw.cmd verify` passed 24 backend tests and packaged the JAR. Frontend passed 5 tests, TypeScript, and the client/server Vite build. A Microsoft Edge smoke test issued a QR, verified ACKED without passage confirmation, waited for the outbox, and checked that the same event appeared in both the UI and its downloaded HR CSV. The first offline Surefire run lacked plugin dependencies. The Windows sandbox blocked local sockets for the HTTP tests and required a workspace-local temp directory for Vitest. The checks completed after those environment issues were resolved.

The Node.js load script issues one fresh QR per scan and logs each verify response, error and timeout as JSON Lines. From `backend/`, run `node scripts/access-load.mjs` against a fresh local demo database. It defaults to 800 scans over 30 minutes for one site. To run two sites concurrently, use a separate Edge/DB per site and configure `EDGE_URL`, `SITE_ID`, `STATION_ID`, and a separate `LOAD_REPORT` per process. Set `DURATION_SECONDS=30` for the compressed local run. The boundary is Node HTTP request through receipt and parsing of the Edge response. It excludes browser rendering, real LAN and physical gate response.

| Compressed run on two local H2 file databases, 30 seconds each | ACKED | Errors | Timeouts | Over 2 s | Max |
|---|---:|---:|---:|---:|---:|
| SITE-1 | 800 / 800 | 0 | 0 | 0 | 74.55 ms |
| SITE-2 | 800 / 800 | 0 | 0 | 0 | 58.35 ms |

The two sites ran at the same time on this Windows machine with the packaged JAR and fake Turnstile. These results cover the local HTTP boundary in the compressed run. The official 30-minute scenario and production measurement are still unverified.

## Assignment checklist

| Item | Status |
|---|---|
| Task 2 | Edge verification, short-lived issue/revoke, attendance contract and demo integrations wired. |
| SOLID structure and ports | `VerificationPipelineTest`, `ArchitectureRulesTest` and three Turnstile contract implementations. |
| AC-01 | `EdgeFlowTest` and `VerificationPipelineTest` cover the main positive and negative flows. Individual invalid claim and assignment cases remain for expansion. |
| AC-02 | Contract and HTTP tests keep ACK, passage and evidence distinct. Physical sensor acceptance requires a vendor contract. |
| AC-03 | `EdgeFlowTest` checks the ACK_ONLY review flag. `AttendanceEligibilityTest` checks command eligibility. |
| AC-04 | `EdgeFlowTest` injects an outbox write failure and verifies that the JTI is reusable and no gate command was sent. Restart recovery still requires a real controller. |
| AC-05 | `EdgeFlowTest` covers stored retry and concurrent JTI use. `VerificationPipelineTest` covers the in-progress state and timeout retry. |
| AC-06 | Two-site simulated local load. The official 30-minute workload and full measurement boundary remain to be validated. |
| AC-07 | `EdgeFlowTest` verifies one simulated ACKed monthly batch before noon under a fixed clock. HR schema and real delivery ACK remain open. |
| AC-08 | `EdgeFlowTest` covers anonymous denial, no Admin attendance access, employee scope and HR export permissions. The complete TMC authorization matrix is unverified. |
| AC-09 | `EdgeFlowTest` recomputes a stored audit hash and detects a changed field. A full event catalog, query scopes and independent production retention verification remain open. |
| AC-10 | `EdgeFlowTest` verifies that the HR CSV omits the raw QR. Full privacy and retention review remains open. |

## Frontend connection

The frontend operations console lives in `../frontend/src/modules`. Set `VITE_EDGE_API_URL` using `../frontend/.env.example`. For a different frontend port, set `tmc.cors.origins` to the exact frontend origin. The root README lists the supported browser flows and routes.
