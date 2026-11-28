# TMC QR Attendance & Access Control · Task 1

Tài liệu này mô tả bản cài đặt hiện hành và các điểm cần nghiệm thu tại nơi triển khai. Các tham số mặc định thuộc bản nộp. Lịch ca, hợp đồng tích hợp và chính sách lưu giữ chính thức vẫn cần TMC phê duyệt.

## Phase 0 · Open questions

| Nội dung | Cách xử lý trong bản nộp | Xác nhận khi triển khai |
| --- | --- | --- |
| Danh tính và vòng đời QR | Token ngắn hạn, kiểm tra credential và phiên bản, phân kênh và scope trạm. Có duyệt trình duyệt cho luồng nhân viên tự quét. | TMC Security duyệt vòng đời thẻ, quy trình mất thiết bị và quản lý khóa ký. |
| Phản hồi mở cổng và passage | Trạng thái lệnh và bằng chứng passage là hai chiều riêng. Adapter phần mềm xác nhận lượt. | Facilities cung cấp hợp đồng controller và cảm biến. |
| Edge mất WAN | Kiểm tra cục bộ với dữ liệu nội bộ hoặc snapshot hợp lệ. Outbox giữ lượt cần đồng bộ. Snapshot hết hạn hoặc lệch tương lai quá ngưỡng làm Edge từ chối lượt mới. | Vận hành cung cấp nguồn phát snapshot, đường phân phối và phương án khôi phục. |
| Ca đêm, nhiều lần vào/ra, thiếu giờ ra | Dùng `ShiftCalendar`, giới hạn cặp cấu hình và hiệu chỉnh có audit. | HR duyệt lịch ca và quy tắc ngày công. |
| Đầu ra Payroll | Tệp CSV lô gốc và lô bổ sung theo tháng, có lịch sử tải. | Payroll cung cấp hợp đồng import, ACK, xử lý từ chối và lịch chốt. |
| Ranh giới đo tối đa 2 giây | Script đo từ lúc gửi HTTP verify đến khi đọc xong phản hồi trên loopback. | TMC duyệt đo từ thiết bị kiosk ở LAN nhà máy, có adapter thực và kịch bản 30 phút. |
| Quyền và dữ liệu cá nhân | Quyền theo vai trò, scope theo hồ sơ/trạm, audit có hash chain. | TMC duyệt ma trận quyền, thời hạn giữ và quy trình tra cứu nhật ký. |

## 1. Context, goals, and scope

TMC quản lý chấm công và quyền vào/ra tại Bình Dương và Long An. Hồ sơ nhà máy quyết định nhân viên được chấm công ở đâu. Trạm quyết định hướng. Nhân sự xem bảng công, đối soát và xuất dữ liệu. Tính lương và quản lý khách thuộc hệ thống khác.

| Vai trò | Trách nhiệm |
| --- | --- |
| Nhân viên | Đọc QR tại cổng bằng trình duyệt tin cậy hoặc đưa QR cá nhân cho bảo vệ. Xem trạng thái và bảng công của chính mình. |
| Quản lý | Chấm công cá nhân và xem dữ liệu đội thuộc cùng nhà máy, bộ phận. |
| Bảo vệ | Vận hành trạm được giao, quét mã nhân viên, hiện mã tại cổng, xem lượt cổng cần đối soát. |
| Nhân sự | Xem và hiệu chỉnh bảng công, duyệt thiết bị, thu hồi credential, đối soát lượt cổng và chốt lô lương. |
| Quản trị viên | Quản lý tài khoản và trạm. Quyền đọc, hiệu chỉnh, xuất công và đối soát được cấp rõ trong policy của bản nộp. |

Mục tiêu chất lượng của Part I gồm phản hồi kiosk tối đa 2 giây với tải 800 lượt trong 30 phút mỗi nhà máy, độ bền dữ liệu trong điều kiện vận hành bình thường, phân quyền có audit và bảo vệ dữ liệu cá nhân. Kết quả đo rút gọn chứng minh một phần đường xử lý. [Kịch bản và ranh giới đo](backend/README.md#chạy-test-và-đo-tải)

## 2. Architecture structure and creation process

### 2.1 Style comparison and selection

| Phong cách | Vai trò trong thiết kế |
| --- | --- |
| Layered modular monolith | Kiểu tổ chức được chọn. Access, UserQr, Attendance và Sync chia trách nhiệm trong một ứng dụng Spring Boot. Giao dịch cục bộ bảo vệ JTI, lượt quét và outbox. |
| Pipeline | Dùng bên trong Access vì thứ tự các kiểm tra ảnh hưởng kết quả. Bước hỏng ngăn lệnh cổng đi tiếp. |
| Ports and adapters | JDBC, ký JWT, cổng, clock và export đi qua hợp đồng phù hợp để ứng dụng kiểm thử được bằng adapter thay thế. |
| Tách thành dịch vụ | Có thể đánh giá lại theo tải và cách tổ chức đội. Tách lúc này làm tăng chi phí vận hành, độ trễ và bài toán nhất quán liên dịch vụ. |

### 2.2 Logical construction

1. Phân module theo nghiệp vụ QR, cho phép qua cổng, tính công và đồng bộ.
2. Dùng các kiểu dữ liệu riêng cho quyết định, trạng thái lệnh, passage, bằng chứng và kênh.
3. Chuyển truy cập hạ tầng qua port. Wire các triển khai trong `bootstrap`.
4. Ghi dữ liệu cần khôi phục trong giao dịch trước side effect. Các worker đồng bộ, đối soát và kết xuất hoạt động ngoài request quét.
5. Xác minh bằng unit, HTTP, contract, ArchUnit, PostgreSQL và kiểm thử trình duyệt.

## 3. Architecture decisions (ADR index)

| ADR | Quyết định đang dùng |
| --- | --- |
| [ADR 001](docs/adr/ADR-001-station-qr-presence.md) | Mã tại cổng, phiên thiết bị tin cậy, ràng buộc trạm và rủi ro ảnh chuyển tiếp. |
| [ADR 002](docs/adr/ADR-002-gate-command-lifecycle.md) | Vòng đời lệnh, hết hạn, đối soát, circuit breaker và giới hạn pool. |
| [ADR 003](docs/adr/ADR-003-shift-work-date.md) | Một lịch ngày công dùng chung và số cặp vào/ra cấu hình được. |
| [ADR 004](docs/adr/ADR-004-central-durable-sync.md) | Central lưu trạng thái đồng bộ trong DB và phát hiện JTI trùng giữa các site. |
| [ADR 005](docs/adr/ADR-005-payroll-supplements.md) | Lô CSV gốc bất biến và các lô bổ sung theo chênh lệch. |
| [ADR 006](docs/adr/ADR-006-attendance-channels-and-evidence.md) | Ba kênh chấm công, bằng chứng và hạn mức tự khai. |

## 4. Module view

~~~mermaid
flowchart LR
  Employee[Employee UI] --> Workplace[Workplace API]
  Kiosk[Guard UI] --> Access[Access API]
  HR[Attendance UI] --> Attendance[Attendance API]
  Workplace --> UserQr[UserQr use cases]
  Workplace --> Access
  Access --> Pipeline[Verification pipeline]
  Pipeline --> Store[AttemptStore and UsedTokenStore]
  Pipeline --> Gate[GateCommandSender]
  Store --> Broker[DB and outbox]
  Broker --> Mapper[AccessToAttendance]
  Mapper --> Contract[AttendanceEvent contract]
  Contract --> Attendance
  Broker --> Sync[Central outbox and receiver]
  Pipeline --> Calendar[ShiftCalendar]
  Attendance --> Calendar
~~~

| Module | Sở hữu và hợp đồng |
| --- | --- |
| `tmc.access` | Xác minh tại Edge, policy trạm và quyền, replay, hiện diện, trạng thái lệnh, audit lượt. Các port chính gồm `AttemptStore`, `UsedTokenStore`, `GateCommandSender`, `CommandStatusReader`, `SnapshotProvider`. |
| `tmc.userqr` | Cấp mã đã ký và thu hồi credential qua các port ký token và lưu credential. Link tự khai dùng TTL và audience riêng. |
| `tmc.attendance` | Nhận `AttendanceEvent`, gắn eligibility, tổng hợp ngày công, hiệu chỉnh và xuất dữ liệu. Chốt lô CSV dùng DB và scope của vai trò HR/admin. |
| `tmc.sync` | Nhận `EdgeSyncEvent`, lưu kết quả idempotent, phát hiện JTI khác site. `CentralEventStore` tách quyết định đồng bộ khỏi adapter JDBC. |
| `tmc.shared` | Clock, ID, quyền danh tính, lịch ca, hợp đồng outbox và audit, thông tin nhân viên dùng chung. |
| `tmc.bootstrap` | Wire các bean theo profile, ánh xạ Access sang Attendance, chạy các worker và API ghép luồng Workplace. |

## 5. Component-and-connector view

~~~mermaid
flowchart LR
  Phone[Điện thoại nhân viên] --> Frontend[React và TanStack Start]
  Desktop[Kiosk, quản trị, Nhân sự] --> Frontend
  Frontend -->|HTTPS cùng origin qua proxy| Edge[Spring Boot Edge]
  Edge -->|JDBC| Database[(PostgreSQL)]
  Edge --> Adapter[SoftwareAttendanceAdapter]
  Edge --> Writer[AttendanceOutboxWorker]
  Writer -->|AttendanceEvent| Timesheet[Bảng công]
  Timesheet --> Export[XLSX và CSV]
  Edge -.->|tùy chọn, HTTP có bearer| Central[Central sync receiver]
  Central --> Storage[(Central store)]
  File[Snapshot file tùy chọn] -.-> Edge
~~~

| Kết nối | Nội dung và xử lý lỗi |
| --- | --- |
| Frontend → Workplace | Phiên JWT trong cookie HttpOnly được kiểm tra với DB. QR tại cổng dùng cookie trình duyệt khớp phiên và trạng thái tin cậy. |
| Kiosk → Access | Gửi station ID và event ID. Retry dùng lại event ID. Scope được kiểm tra trước khi đọc kết quả của lần gửi trước. |
| Access → DB → adapter | Giao dịch ghi quyền truy cập và JTI trước khi gửi. ACK, timeout và chứng cứ vật lý được xử lý độc lập. |
| Outbox → Attendance | Worker nhận lượt đủ điều kiện hoặc cần xem, ghi idempotent theo event ID và tổng hợp lại ngày công. |
| Outbox → Central | Gửi bất đồng bộ khi được bật. Central lưu idempotency theo event ID. Tái sử dụng JTI khác site tạo cờ nghi vấn sau đồng bộ. |
| Snapshot file → Edge | Worker đọc lại file cấu hình. File lỗi giữ bản hợp lệ gần nhất. Snapshot quá tuổi hoặc đi trước đồng hồ quá 5 giây dẫn tới `SNAPSHOT_STALE`. |

## 6. Allocation view

~~~mermaid
flowchart LR
  subgraph Clients["Nhà máy Bình Dương và Long An"]
    A["Điện thoại / kiosk Bình Dương"]
    B["Điện thoại / kiosk Long An"]
  end
  subgraph Host["Máy chạy bản nộp"]
    V["Frontend và reverse proxy"]
    E["Docker Edge • workplace,postgres"]
    P[("Docker PostgreSQL • postgres-data")]
    S[("workplace-exports")]
    V --> E
    E --> P
    E --> S
  end
  A --> V
  B --> V
  E -.->|nếu bật| C["Central receiver"]
~~~

Bản nộp phân tách nhà máy theo dữ liệu và quyền trên một Edge. Docker Compose cấp PostgreSQL riêng và volume dữ liệu. H2 duy trì cho cấu hình legacy, demo và các test tương ứng. Profile PostgreSQL có bộ test Testcontainers áp dụng schema và kiểm tra luồng JDBC với PostgreSQL thật chạy trong container. Triển khai hai Edge vật lý, LAN tại từng nhà máy và khôi phục hệ thống sau sự cố là hạng mục nghiệm thu riêng.

## 7. Domain model, states, and data

| Chiều | Giá trị và ý nghĩa |
| --- | --- |
| Quyết định | `ALLOW` hoặc `DENY`. Quyết định cho phép vẫn có thể đi cùng kết quả cổng thất bại. |
| Lệnh | `NOT_SENT`, `SENT`, `ACKED`, `FAILED`, `UNKNOWN`, `EXPIRED`. Timeout có thể đã gửi lệnh dẫn đến `UNKNOWN`. Hết hạn giải phóng điều kiện chờ và mở đối soát. |
| Passage | `NOT_AVAILABLE`, `UNCONFIRMED`, `CONFIRMED`. Passage có endpoint riêng. |
| Kênh | `GATE_SCAN`, `STATION_QR`, `PERSONAL_SELF`. |
| Bằng chứng | `ACK_ONLY`, `PASSAGE_CONFIRMED`, `SELF_REPORTED`, `MANUAL_PASSAGE`, `MANUAL_NO_PASSAGE`. Điều kiện ghi công dùng bằng chứng, trạng thái và loại cổng. |

~~~mermaid
stateDiagram-v2
  [*] --> NOT_SENT
  NOT_SENT --> SENT: ghi lượt vật lý
  SENT --> ACKED: adapter xác nhận
  SENT --> FAILED: thất bại xác định
  SENT --> UNKNOWN: chưa rõ kết quả
  UNKNOWN --> ACKED: đối soát adapter
  UNKNOWN --> FAILED: đối soát adapter
  SENT --> EXPIRED: quá hạn
  UNKNOWN --> EXPIRED: quá hạn
~~~

`EXPIRED` giữ nguyên trạng thái lệnh sau khi HR duyệt. Kết quả thủ công nằm ở evidence và `gate_incident_review`. Đối soát có qua cổng chấm công lấy giờ từ lượt gốc. `ShiftCalendar` thống nhất ngày công giữa phép vào/ra, bảng công và hạn mức tự khai. [Vòng đời](docs/adr/ADR-002-gate-command-lifecycle.md) · [Ngày công](docs/adr/ADR-003-shift-work-date.md)

| Nhóm bảng | Trách nhiệm |
| --- | --- |
| `workplace_account`, `workplace_session`, `workplace_employee`, `workplace_browser_device` | Danh tính, scope, phiên và thiết bị tin cậy. |
| `credential`, `station_registry`, `employee_presence` | Thẻ, cấu hình trạm và trạng thái vào/ra gần nhất. |
| `access_attempt`, `used_token`, `idempotency_request`, `edge_outbox`, `edge_audit` | Lượt, chống trùng, xử lý lại, chuyển tiếp bền vững và audit. |
| `gate_incident_review`, `attendance_record`, `daily_timesheet`, `timesheet_adjustment`, `payroll_batch` | Đối soát, tổng hợp, sửa thiếu giờ ra và lưu lô lương. |
| `central_access_event`, `central_token_use`, `central_sync_lock` | Kết quả đồng bộ bền vững và JTI xuất hiện giữa các site. |

## 8. API and execution flows

| Điểm vào | Mục đích |
| --- | --- |
| `POST /auth/v1/login`, `GET /auth/v1/me` | Xác thực phiên và đọc vai trò. |
| `POST /workplace/v1/me/qr`, `POST /access/v1/verify` | Nhân viên lấy QR và bảo vệ xác minh tại trạm theo scope. |
| `POST /workplace/v1/stations/{stationId}/qr/{direction}`, `POST /workplace/v1/me/check-in` | Phát QR tại trạm và nhân viên gửi lượt từ trình duyệt tin cậy. |
| `POST /workplace/v1/me/check-in/personal` | Tự khai ở profile riêng. Kênh tắt mặc định, dùng ngày công và hạn mức, trả 429 khi vượt. |
| `GET /workplace/v1/me/check-in/status/{id}`, `GET /access/v1/scans/{id}` | Tra kết quả theo chủ lượt hoặc trạm được giao. |
| `GET /gates/v1/incidents`, `POST /gates/v1/incidents/{id}/review` | Xem hàng chờ theo quyền và đối soát có lý do. |
| `POST /edge/v1/sync` | Nhận batch Central qua bearer token nếu cấu hình. |
| `/attendance/v1/timesheets`, `/attendance/v1/timesheets/payroll/{month}` | Xem bảng công, hiệu chỉnh, chốt và tải lô theo scope. |

Luồng verify hiện tại lần lượt kiểm tra xác thực trạm và scope, xử lý idempotency, kiểm tra chữ ký/thời gian, credential, chính sách/snapshot, replay và anti-passback, rồi lưu và tiêu thụ JTI. Lệnh cổng chạy sau khi lưu. Kênh tự khai bỏ qua lệnh cổng. Bước cuối ghi outcome.

~~~mermaid
sequenceDiagram
  actor Guard as Trạm
  participant Access as Edge Access
  participant DB as DB
  participant Gate as Adapter
  participant Att as Worker bảng công
  Guard->>Access: Verify(eventId, station, QR)
  Access->>Access: Scope, token, policy, replay, presence
  alt Từ chối
    Access->>DB: Lưu lý do và audit
    Access-->>Guard: DENY
  else Cho phép
    Access->>DB: Ghi SENT, tiêu thụ JTI, lưu outbox
    Access->>Gate: Gửi lệnh theo eventId
    Gate-->>Access: ACKED, FAILED hoặc timeout
    Access->>DB: Ghi kết quả, finalize idempotency
    Access-->>Guard: Quyết định và trạng thái lệnh
    DB-->>Att: Lượt đủ điều kiện hoặc cần HR xem
  end
~~~

## 9. RBAC, audit, and privacy

`WorkplacePolicy` xác định quyền thao tác và scope đọc. Employee dùng Own. Manager dùng team cùng bộ phận và nhà máy. Guard chỉ tác nghiệp ở trạm được gán. HR và Admin quản lý bảng công hai nhà máy theo quyền đã cấp. Thu hồi credential là quyền QR của HR trong `workplace`.

Mật khẩu lưu bằng BCrypt. JWT và mã kỹ thuật nằm trong hợp đồng API nội bộ. Audit dùng actor, action, target, result, thời điểm, correlation ID và hash của bản ghi trước. Log vận hành dùng mã lý do và correlation ID. QR gốc và khóa ký được loại khỏi log. Tệp CSV escape trường có thể bị hiểu là công thức. XLSX ghi trường văn bản dưới dạng chuỗi.

## 10. Giới hạn đã biết

- Thu hồi credential tại một Edge được thấy ngay ở cùng DB. Hai Edge có DB riêng chỉ nhận thay đổi khi snapshot được phát và cập nhật. Giới hạn 15 phút là tuổi tối đa Edge chấp nhận của snapshot, tính từ thời điểm tạo. Đây là giới hạn chấp nhận bản cũ, chưa có bảo đảm phân phối tự động trong 15 phút. Khi bản hợp lệ hết hạn, Edge từ chối quét.
- Edge là điểm lỗi đơn trong topology bản nộp. Sự cố Edge hoặc DB làm cả hai nhà máy mất đường ghi công. Dữ liệu tồn tại trên volume, cần phương án khôi phục khi triển khai.
- Nhân viên có thể đóng phiên và quản lý thiết bị đã duyệt. Khi mất điện thoại, thu hồi credential do HR thực hiện.
- `HttpTurnstileAdapter` là stub trả `FAILED`. Adapter Workplace xác nhận bằng phần mềm. Cổng, cảm biến, mTLS và override ngoại tuyến hai người thuộc phần cần tích hợp và nghiệm thu với nhà máy.
- Ảnh QR tại trạm có thể chuyển tiếp trong 15 giây. Cookie thiết bị có thể bị sao chép nếu trình duyệt bị chiếm quyền. [ADR 001](docs/adr/ADR-001-station-qr-presence.md)
- Số liệu tải là phép đo HTTP loopback rút gọn. Chưa có phép đo 30 phút từ kiosk qua LAN và controller thật. Xác nhận nhập tệp từ Payroll thật cũng cần nghiệm thu riêng. [Kết quả đo tải và môi trường](backend/README.md#chạy-test-và-đo-tải)

## 11. SOLID application and reflection

| Nguyên tắc | Bằng chứng và phạm vi |
| --- | --- |
| SRP | `VerificationStep` xử lý một bước. `ShiftCalendar` tính ngày công. `JdbcPersonalCheckInQuota` kiểm tra hạn mức. `JdbcAttemptStore` giữ biên giao dịch ghi và điều phối các ràng buộc khi lưu. |
| OCP | Bước xác minh và adapter có thể bổ sung qua wiring. Cấu hình TTL, lịch ca và vòng đời lệnh nằm ở cấu hình triển khai. |
| LSP | Fake, phần mềm và adapter HTTP tuân hợp đồng outcome của `GateCommandSender`. Contract test kiểm tra nghĩa của outcome. |
| ISP | Gửi lệnh, đọc trạng thái, lưu lượt, đọc presence và audit có các hợp đồng tách riêng. |
| DIP | Use case nhận port, clock và ID qua constructor. JDBC và thành phần theo profile được nối ở bootstrap. |

`AttendanceEvent` và `EdgeSyncEvent` là hợp đồng trao đổi. Vẫn có các import qua module đã ghi trong checklist, như UserQr dùng port credential của Access. Bộ ArchUnit kiểm tra ranh giới hiện có. [Test kiến trúc](backend/src/test/java/tmc/architecture/ArchitectureRulesTest.java) · [Phân tích SOLID trong README](README.md#solid)

## 12. Traceability and verification plan

| AC | Thành phần và kiểm thử |
| --- | --- |
| AC-01 · Xác minh QR | Pipeline, `EdgeFlowTest`, test từng bước, test thuật toán ES256 và độ tươi của snapshot. |
| AC-02 · Lệnh và passage | `GateCommandSenderContractTest`, `GateIncidentFlowTest`, `TurnstileCircuitTest`. Cần hợp đồng và thiết bị thật để nghiệm thu. |
| AC-03 · Ghi công | `AttendanceEvent`, policy eligibility, `AttendanceEligibilityTest`, `ShiftCalendarTest`, luồng Employee/Guard/HR. |
| AC-04 · Độ bền | `JdbcAttemptStore`, `EdgeFlowTest` rollback khi outbox lỗi, `PostgresWorkplacePersistenceTest`, `CentralSyncReceiverTest`. |
| AC-05 · Idempotency | `EdgeFlowTest` với lượt đồng thời, `AntiPassbackStepTest` khi bên khác tiêu thụ JTI, unique key ở DB và kiểm thử API retry. |
| AC-06 · Tải kiosk | `scripts/access-load.mjs` và số liệu HTTP rút gọn. Nghiệm thu ở hai nhà máy dùng kịch bản 30 phút, ghi từng response và lỗi. |
| AC-07 · Lô HR/Payroll | `PayrollBatchesTest` kiểm tra lô gốc, bổ sung, gửi trùng và hủy dòng. `HrExportWorker` legacy mô phỏng ACK phục vụ test deadline. Để nghiệm thu mốc 12:00 ngày 1, cần đối chiếu giờ tạo, gửi và ACK với hợp đồng Payroll và lịch địa phương đã duyệt. |
| AC-08 · RBAC | `WorkplaceFlowTest`, `EdgeFlowTest` kiểm tra Own, scope nhà máy/trạm và quyền export. |
| AC-09 · Audit | `EdgeFlowTest` tính lại chuỗi hash. `GateIncidentFlowTest` và `WorkplaceFlowTest` kiểm tra audit nghiệp vụ. |
| AC-10 · Dữ liệu cá nhân | Test log loại QR/token và test export theo scope. Retention, xóa và tra cứu nhật ký theo policy TMC cần nghiệm thu. |

| Nhóm yêu cầu | Luồng tương ứng |
| --- | --- |
| FR-01.1–01.4 · UC-05 | Hồ sơ, credential và cấp/thu hồi QR. |
| FR-02.1–02.3 · UC-06, UC-07 | Cấu hình cổng, quyền tại trạm và trạng thái. |
| FR-03.1–03.7 · UC-01, UC-02 | Verify, lệnh, replay, đối soát và ghi nhận công. |
| FR-04.1–04.6 · UC-03, UC-04, UC-08 | Bảng công theo scope, audit và lô xuất. |
| FR-05.1–05.3 · UC-07 | Quản trị vai trò, tài khoản và cấu hình trạm theo quyền đã có. |
| NFR-01–04 | Tải kiosk, độ bền dữ liệu, RBAC/audit và bảo vệ dữ liệu cá nhân qua AC-04–06 và AC-08–10. |

## 13. Các câu hỏi mở của Phần I §1.5.5

| Chủ đề | Quyết định trong code | Bên cần xác nhận khi triển khai |
| --- | --- | --- |
| Vòng đời QR | Token ký có TTL theo kênh, kiểm tra phiên bản credential, phát hiện JTI đã dùng. | Security và HR duyệt chu kỳ thu hồi, cấp lại và quản lý khóa. |
| Quyền vào cổng | Policy xét hồ sơ, nhà máy, trạm, hướng và lịch hoạt động. | Vận hành và HR xác nhận nhóm vào cổng và lịch. |
| Danh mục thiết bị | Registry giữ trạm và hướng. Bản nộp phục vụ hai chi nhánh qua một Edge. | Facilities cung cấp danh mục thiết bị, LAN và phương án dự phòng. |
| Phản hồi Turnstile | Trạng thái lệnh, passage và duyệt thủ công được lưu riêng. | Nhà cung cấp xác nhận semantics ACK, cảm biến và truy vấn theo ID lệnh. |
| ID và đồng hồ | `eventId` nhận diện lượt, Edge dùng giờ nhận khi xác minh lượt mới. `ShiftCalendar` quy đổi ngày công. | Nhà cung cấp kiosk và HR duyệt đồng hồ, lịch ca và múi giờ. |
| Mất mạng và timeout | Edge xử lý cục bộ với dữ liệu còn dùng được. Lượt không chắc kết quả đi qua `UNKNOWN`, `EXPIRED` và hàng chờ. | Vận hành duyệt đường phân phối snapshot, thời hạn và xử lý ngoài hệ thống khi Edge mất dịch vụ. |
| Hợp đồng API | API nội bộ có scope, idempotency và mã lỗi. Đường HTTP tới controller còn là stub. | Turnstile và Payroll cung cấp hợp đồng và môi trường tích hợp. |
| Đối soát Payroll | Lưu lô gốc và lô chênh lệch theo tháng. | Payroll xác nhận schema, ACK, trường hợp chấp nhận một phần và mốc chốt. |
| Quy tắc chấm công | Lịch ca và số cặp vào/ra có cấu hình. Thiếu giờ ra có luồng hiệu chỉnh kèm audit. | HR duyệt lịch ca, số cặp và cách hiệu chỉnh. |
| Scope | Own, Team theo chi nhánh/bộ phận, trạm được gán và quyền HR/Admin nằm trong policy ứng dụng. | HR và quản trị dữ liệu duyệt nguồn, vai trò và quy trình cập nhật. |
| Riêng tư | Scope áp dụng cho màn hình, API và xuất báo cáo. | Chủ sở hữu dữ liệu duyệt mục đích, thời hạn lưu, xóa và quyền truy cập nhật ký. |
| Audit | Audit nghiệp vụ chứa actor, hành động, đối tượng, kết quả và thời điểm. Edge có chuỗi hash cho lượt truy cập. | Security duyệt danh mục sự kiện, thời hạn lưu và quyền tra cứu. |

## 14. Rủi ro, dữ liệu và vận hành

| Tình huống | Dữ liệu và biện pháp hiện có | Phần cần nghiệm thu |
| --- | --- | --- |
| Mất điện thoại hoặc nghi lộ QR | Kết thúc phiên, thu hồi credential qua HR, kiểm tra audit và nguồn xác nhận vào/ra. Bản ghi phiên giữ thông tin trình duyệt và IP khi được thu thập. | Quy trình báo mất, xác minh danh tính tại chỗ và luân chuyển snapshot. |
| Quét trùng hoặc trả kết quả muộn | JTI và ID lượt nằm trong DB. Trạm tra kết quả theo ID, Central gắn cờ JTI khác site, HR đối soát lệnh đã hết hạn. | Chính sách xử lý kết quả đến muộn từ controller và cờ nghi vấn sau khi Payroll đã nhập lô. |
| Lộ dữ liệu bảng công hoặc tệp xuất | Hồ sơ, giờ công và bằng chứng nằm trong DB. Scope giới hạn đọc và xuất. CSV xử lý đầu ô có thể là công thức, XLSX ghi chuỗi. | Quản lý quyền tới volume và tệp đã tải, mã hóa hạ tầng và chính sách lưu giữ. |
| Snapshot, cổng hoặc Edge lỗi | Có health và log theo mã lý do. Theo dõi thời gian còn lại của snapshot, độ sâu outbox, outcome cổng và sự kiện nghi vấn tại Central. | Ngưỡng cảnh báo, đường báo động và quy trình khôi phục trên LAN nhà máy. |

Nguồn yêu cầu là đề Assignment M2, Phần I và các tài liệu môn học đã đối chiếu ở README. [M2 Checklist](README.md#m2-checklist) · [Health và metrics](backend/README.md#health-metrics-và-log)
