# Backend Edge

Java 21, Spring Boot 3 và Maven. Docker Compose chạy profile `workplace,postgres` cho đăng nhập, chấm công và báo cáo.

## Chạy Edge

Sao chép `.env.example` thành `.env`, điền thông tin bên dưới rồi chạy `docker compose up -d --build`. Backend dùng `127.0.0.1:8085`. PostgreSQL dùng `127.0.0.1:55432` cho TablePlus và lưu dữ liệu trong volume `postgres-data`. Volume `workplace-exports` lưu bảng tính. `docker compose down` giữ cả hai volume.

Để chạy cấu hình H2 cũ trên Windows, dùng `.\scripts\start-workplace.ps1 -Initialize -AllowHttp` từ thư mục `backend`.

| Tham số | Mục đích |
| --- | --- |
| `TMC_SESSION_KEY` | Khóa ký JWT đăng nhập. Không ghi khóa vào Git. |
| `TMC_ADMIN_USERNAME`, `TMC_ADMIN_PASSWORD` | Khởi tạo quản trị viên đầu tiên. Mặc định username là `admin`; mật khẩu do người triển khai đặt. |
| `TMC_SESSION_SECURE`, `TMC_CORS_ORIGINS` | Cookie mặc định Secure. Đặt origin chính xác và chỉ tắt Secure khi kiểm tra HTTP cục bộ. |
| `TMC_TRUSTED_PROXY_IP` | IP proxy được phép chuyển địa chỉ máy khách cho lịch sử đăng nhập. Với Vite trên host và Compose publish loopback, lấy gateway bằng `docker network inspect tmc-attendance_default --format '{{(index .IPAM.Config 0).Gateway}}'`. |
| `TMC_DB_NAME`, `TMC_DB_USERNAME`, `TMC_DB_PASSWORD`, `TMC_POSTGRES_PORT` | Tên DB, tài khoản, mật khẩu và cổng PostgreSQL trên host. Tên mặc định `tmc_attendance`, user `tmc`, cổng `55432`. |
| `tmc.edge.site`, `tmc.edge.station` | Nhà máy mặc định là `SITE-BD`, với `BD-G01` và `BD-G01-OUT`. Long An có `LA-G01` và `LA-G01-OUT`. |
| `tmc.checkin.personal-self.max-per-day` | Hạn mức tự khai, mặc định 4 lượt hợp lệ mỗi nhân viên mỗi ngày công khi profile tự khai được bật. |
| `tmc.qr.ttl-seconds` | JWT bảo vệ và kiosk đọc có hiệu lực 30 giây. |
| `tmc.qr.personal-link-ttl-seconds`, `tmc.checkin.personal-self.enabled` | Link tự khai có hiệu lực 60 giây và tính năng tắt mặc định. Bật cho DB kiểm thử qua profile `workplace,personal-self-demo`. |
| `tmc.attendance.pairs-per-day` | Số cặp vào/ra tối đa mỗi ngày công. Mặc định 1. |
| `tmc.access.shift-grace-minutes`, `tmc.access.shift-late-grace-minutes` | Thời gian gia hạn của ca. Mặc định cho vào sớm 10 phút và ra muộn 30 phút. |
| `tmc.qr.keystore` | Cấu hình PKCS#12 và mật khẩu `TMC_QR_KEYSTORE_PASSWORD` để giữ khóa ES256 qua lần khởi động lại. |

Nếu Edge dùng snapshot, theo dõi gauge `tmc.edge.snapshot.seconds.remaining`. Mặc định Edge ghi cảnh báo khi còn 120 giây qua `tmc.edge.snapshot-warning-seconds`. Khi snapshot hết hạn, Edge chờ dữ liệu policy mới trước khi cho phép quét.

## Chuyển dữ liệu H2 sang PostgreSQL

Dừng Edge đang kết nối vào H2. Giữ khóa phiên từ `data/workplace-session.key` trong `TMC_SESSION_KEY`. Chuẩn bị database PostgreSQL mới bằng `docker compose up -d --wait db`. Chạy lệnh dưới đây từ `backend/` sau khi đặt `TMC_DB_USERNAME` và `TMC_DB_PASSWORD` theo `.env`. Nếu H2 dùng thông tin khác mặc định, đặt thêm `TMC_H2_USERNAME` và `TMC_H2_PASSWORD`.

~~~powershell
.\mvnw.cmd dependency:copy-dependencies "-DincludeArtifactIds=h2,postgresql" "-DoutputDirectory=target/migration-drivers"
$drivers = (Get-ChildItem target/migration-drivers/*.jar).FullName -join ';'
$backup = 'data/tmc-workplace-before-postgres-' + (Get-Date -Format yyyyMMdd-HHmmss) + '.mv.db'
java -cp $drivers scripts/CopyWorkplaceDatabase.java data/tmc-workplace.mv.db jdbc:postgresql://127.0.0.1:55432/tmc_attendance $backup src/main/resources/schema.sql
docker compose up -d --build edge
~~~

Lệnh tạo bản sao H2, chép vào PostgreSQL trống và đối chiếu từng trường của các bảng đã có trong H2. Toàn bộ dữ liệu được ghi trong một giao dịch. Hãy dùng lại tài khoản ứng dụng đã chuyển. Trong TablePlus, tạo kết nối PostgreSQL theo các giá trị trong `.env`.

## Đối soát lượt cổng

Edge hỏi lại trạng thái của lệnh đang chờ. Mặc định sau 120 giây kể từ khi nhận lượt, lệnh chưa có kết quả chuyển sang `EXPIRED`. Edge ghi audit `GATE_COMMAND_EXPIRED`, ngừng hỏi trạng thái và cho phép nhân viên quét mã mới. Điều chỉnh thời hạn qua `tmc.turnstile.reconciliation-seconds`. Mặc định một trạm tạm ngừng gửi lệnh 15 giây sau 3 lần lỗi liên tiếp. Khi hết thời gian chờ, một lượt đi qua để kiểm tra cổng đã phản hồi. Điều chỉnh qua `tmc.turnstile.breaker-failures` và `tmc.turnstile.breaker-reset-seconds`.

Danh sách **Lượt cổng cần đối soát** nằm ở Trạm quét và Bảng công. Bảo vệ xem các lượt tại trạm được giao. Nhân sự hoặc quản trị viên xác minh đã qua cổng hay chưa rồi lưu lý do. Chỉ lượt được xác nhận qua cổng chấm công mới được đưa vào bảng công. Giờ công lấy từ lượt gốc. Lệnh mở cổng vẫn ghi `EXPIRED`, và xác nhận thủ công có evidence riêng. Đối soát lượt cũ giữ nguyên trạng thái hiện diện của lượt mới hơn.

## Lô CSV cho lương

Nhân sự hoặc quản trị viên mở **Bảng công → Các lô CSV cho lương** và chọn tháng. Chốt lô đầu tiên để lưu bản gốc cho cả hai nhà máy. Các lô sau chỉ chứa những dòng có thay đổi kể từ lần chốt gần nhất. Lịch sử lưu bản CSV của từng lô và cho tải lại theo số thứ tự. Bấm chốt nhiều lần khi dữ liệu chưa đổi trả về cùng lô.

Mỗi dòng có khóa `employee_id` và `work_date`. `UPSERT` thay toàn bộ giá trị của dòng ngày công, gồm tổng phút tuyệt đối. `VOID` hủy dòng đã xuất trước đó. Áp dụng các lô theo số thứ tự, một lần cho mỗi cặp tháng và số lô. Lượt tự khai chưa có giờ vào đủ điều kiện không tạo dòng lương. Việc tạo và tải lô được ghi audit. Đây là hợp đồng tệp CSV của bản nộp, giao nhận tự động phụ thuộc định dạng import và ACK của Payroll tại nơi triển khai.

## API bản nộp

| API | Quyền và dữ liệu |
| --- | --- |
| `POST /auth/v1/login` | Kiểm tra BCrypt trong DB, cấp JWT phiên qua cookie HttpOnly. |
| `GET /auth/v1/me`, `POST /auth/v1/password`, `POST /auth/v1/logout` | Đọc danh tính, đổi mật khẩu và kết thúc phiên trong DB. |
| `GET /workplace/v1/me/employee`, `POST /workplace/v1/me/qr`, `GET /workplace/v1/me/attendance` | Lấy hồ sơ, cấp QR và xem trạng thái công của chính nhân viên trong phiên. |
| `GET /workplace/v1/config` | Trả cờ tự khai cho giao diện. |
| `POST /workplace/v1/stations/{id}/qr/{direction}` | Bảo vệ của trạm hoặc quản trị viên phát mã cổng 15 giây. Lưu audit `STATION_QR_ISSUED`, tối đa 30 lần/phút/tài khoản. |
| `POST /workplace/v1/me/check-in` | Xác thực thiết bị đã duyệt, chi nhánh và mã đã ký tại cổng. Lưu kênh `STATION_QR`. |
| `POST /workplace/v1/me/check-in/personal` | Profile tự khai kiểm tra thiết bị và chủ mã rồi ghi `PERSONAL_SELF` với bằng chứng `SELF_REPORTED` để Nhân sự xem. Mỗi nhân viên mặc định có bốn lượt mỗi ngày công, API trả `429 PERSONAL_SELF_LIMIT` khi vượt. |
| `GET /workplace/v1/me/devices`, `PATCH /workplace/v1/me/devices/{reference}` | Xem thiết bị của tài khoản và duyệt hoặc gỡ qua trình duyệt tin cậy. Nhân sự và quản trị viên dùng `/workplace/v1/hr/employees/{employeeId}/devices`. |
| `GET /workplace/v1/station` | Chỉ Bảo vệ, trả trạm đang hoạt động được gán. |
| `POST /access/v1/verify` | Kiểm tra phiên Bảo vệ, trạm, JWT QR, quyền vào/ra và idempotency. `Idempotency-Key` trùng `eventId`. |
| `GET /access/v1/scans/{id}` | Khôi phục kết quả theo mã lượt và trạm được gán. |
| `GET /workplace/v1/me/check-in/status/{id}` | Nhân viên đọc kết quả lượt của mình từ trình duyệt tin cậy, kể cả khi QR đã hết hạn. |
| `GET /gates/v1/incidents`, `POST /gates/v1/incidents/{id}/review` | Bảo vệ xem trạm được giao. Nhân sự và quản trị viên đối soát kèm lý do. |
| `GET/POST /workplace/v1/accounts`, `PATCH /workplace/v1/accounts/{username}` | Chỉ Quản trị viên, quản lý tài khoản và trạng thái. |
| `GET/POST /admin/v1/stations`, `PATCH /admin/v1/stations/{id}` | Quản lý registry trạm, hướng, nhóm và giờ hoạt động. |
| `GET /attendance/v1/timesheets?month=YYYY-MM` | Tra bảng công trong phạm vi của phiên. |
| `GET/POST /attendance/v1/timesheets/payroll/{month}`, `GET /attendance/v1/timesheets/payroll/{month}/{number}` | Xem lịch sử, chốt lô gốc hoặc bổ sung, tải lại CSV theo số lô. |
| `POST /attendance/v1/timesheets/corrections`, `GET /attendance/v1/timesheets/{csv,xlsx}` | Quản trị viên và Nhân sự bổ sung giờ ra và xuất báo cáo. Tệp lọc theo `month`, `siteId` và `employeeId` đã chọn. |

Xác minh dùng giờ Edge nhận yêu cầu mới. Yêu cầu đã lưu được tra lại bằng `eventId` và dữ liệu gốc, kể cả khi QR đã hết hạn. Bảng công nhận kết quả qua outbox sau khi Edge ghi quyết định và xác nhận phần mềm.

Các hợp đồng cũ phía dưới dùng cho profile kiểm thử kiến trúc `legacy`, `workflow`, `bd` và `la`. Chạy riêng với DB kiểm thử. Bản nộp mặc định chạy `workplace`.

## Luồng và API demo

| API | Trách nhiệm |
| --- | --- |
| `POST /qr/v1/tokens` | Nhân viên gửi `{ "credentialId": "demo-credential" }`, bearer và `X-Device-Proof`. Edge xác minh quyền sở hữu, lấy định danh thiết bị từ bảng đăng ký và trả `token`, `expiresAt`. |
| `POST /qr/v1/credentials/{id}/devices` | HR cấp hoặc thay mã đăng ký. Response chứa `deviceProof` cần chuyển cho nhân viên. |
| `POST /qr/v1/credentials/{id}/revoke` | HR thu hồi credential và tăng phiên bản. |
| `GET /admin/v1/stations` | Trả `{ stations, canManage }`. Admin xem trạm của Edge. Bảo vệ chỉ xem trạm được giao. |
| `POST /admin/v1/stations` | Admin đưa `siteId`, `stationId`, `gateId` vào registry. Site phải trùng cấu hình Edge. |
| `PATCH /admin/v1/stations/{id}` | Admin cập nhật `gateId` hoặc `status` (`ACTIVE`, `DISABLED`). |
| `POST /access/v1/verify` | Xác minh scan tại Edge. Gửi `X-Station-Id: GATE-1`, `siteId=SITE-1`, hướng và thời gian scan. `Idempotency-Key`, nếu gửi, phải trùng `eventId`. |
| `POST /gates/v1/passage-events` | Nhận chứng cứ passage riêng. Demo yêu cầu bearer của guard và ID trạm. |
| `POST /gates/v1/presence-corrections` | Guard sửa quên quét ra bằng `lastEntryEventId` và `reason=MISSED_EXIT`. Hành động được audit và chỉ đổi presence. |
| `GET /attendance/v1/records` | Trả dữ liệu trong scope của bearer token demo. |
| `GET /reports/v1/exports?month=YYYY-MM` | Tải CSV tháng bằng bearer token HR. Chỉ bản ghi ACKED đủ điều kiện và không có cờ suspicious được đưa vào CSV. |
| `POST /edge/v1/sync` | Central nhận outbox qua token `TMC_EDGE_SYNC_TOKEN`. Lưu event ID và site đầu tiên của mỗi `jti` trong DB để chống trùng qua lần khởi động lại. |

### Danh tính và cấu hình

| Danh tính demo | Biến môi trường | Scope hoặc tác vụ |
| --- | --- | --- |
| Employee | `TMC_EMPLOYEE_DEMO_TOKEN`, `TMC_EMPLOYEE_2_DEMO_TOKEN` | Cấp QR và đọc chấm công của chính mình |
| Manager | `TMC_MANAGER_DEMO_TOKEN` | Team attendance |
| HR | `TMC_HR_DEMO_TOKEN` | Authorized attendance, tải CSV, đăng ký thiết bị, thu hồi credential |
| Guard | `TMC_GUARD_DEMO_TOKEN` | Xem trạm được giao, gửi chứng cứ passage và sửa presence |
| System Administrator | `TMC_ADMIN_DEMO_TOKEN` | Đọc và quản lý registry trạm |

Mỗi token demo phải khác nhau. Token rỗng vô hiệu hóa danh tính tương ứng. Gửi token qua `Authorization: Bearer <token>`. Dùng dữ liệu mẫu khi chạy demo.

| Cấu hình | Mặc định và cách dùng |
| --- | --- |
| `tmc.edge.site`, `tmc.edge.station` | `SITE-1`, `GATE-1`. |
| `tmc.edge.local-demo-authority`, `tmc.edge.snapshot-file` | Demo đọc credential và revocation trực tiếp từ H2. Có thể dùng file snapshot có `generatedAt`, `revoked`, `stationSites`. Tắt demo authority khi cần mô phỏng fail-closed. |
| `tmc.edge.passage-sensor` | `false`, nên scan mặc định trả passage `NOT_AVAILABLE`. Kích hoạt sensor đặt passage ban đầu là `UNCONFIRMED`. |
| `tmc.qr.keystore`, `tmc.qr.signing-kid` | Demo sinh khóa ES256 khi khởi động. Có thể dùng PKCS#12 với `TMC_QR_KEYSTORE_PASSWORD`; các alias hợp lệ cung cấp public key cho verify. |
| `tmc.central.enabled`, `tmc.central.url` | Dispatch tắt mặc định. Khi bật, worker gọi URL Central bất đồng bộ. |
| `tmc.hr.zone`, `tmc.hr.schedule` | `Asia/Ho_Chi_Minh` và job chạy mỗi 5 phút trong ngày 1. Dùng `tmc.hr.schedule=-` để tắt. |
| `SERVER_ADDRESS`, `TMC_CORS_ORIGINS` | Bind loopback mặc định. Đặt `SERVER_ADDRESS=0.0.0.0` và danh sách origin phân cách bằng dấu phẩy khi demo trực tiếp qua LAN. |

TTL 30 giây, skew 5 giây, snapshot tối đa 15 phút, timeout Turnstile 800 ms và lease 60 giây là tham số demo, chờ TMC xác nhận. Job HR lưu batch tháng trước cùng các mốc ready, sent và ACK mô phỏng.

## Health, metrics và log

`GET /actuator/health` hiện trạng thái tổng. Snapshot stale trả `503 OUT_OF_SERVICE`. Khi truy vấn outbox thất bại, health báo `DOWN`.

| Metric | Xem qua `/actuator/metrics/<tên>` |
| --- | --- |
| `tmc.edge.snapshot.fresh` | `1` khi snapshot còn dùng được, `0` khi STALE. |
| `tmc.edge.snapshot.seconds.remaining` | Số giây đến lúc snapshot hết hạn. Giá trị `0` khi snapshot hết hạn hoặc đi trước đồng hồ quá 5 giây. |
| `tmc.edge.outbox.depth` | Số dòng đang chờ xử lý. Chỉ tính backlog Central khi dispatch được bật. |
| `tmc.edge.verify.decisions` | Kết quả ALLOW/DENY, kể cả response idempotent. Lọc bằng `?tag=decision:DENY` hoặc `?tag=reasonCode:REPLAY`. |
| `tmc.edge.gate.commands` | Outcome ban đầu theo `status`, gồm ACKED, UNKNOWN và FAILED. Replay idempotent không gửi thêm lệnh. |
| `tmc.central.sync.suspicious` | Số sự kiện đồng bộ mới bị đánh dấu `SUSPICIOUS`. |
| `http.server.requests` | Thời gian server. Lọc verify bằng `?tag=method:POST&tag=uri:%2Faccess%2Fv1%2Fverify`. |

Metric quyết định và timer xuất hiện sau khi có request tương ứng. Log verify có correlation ID và mã kết quả. Client có thể gửi một ID vô danh hợp lệ trong `X-Correlation-Id` hoặc đọc ID do server sinh từ response.

## Chạy test và đo tải

```powershell
.\mvnw.cmd -B test
.\mvnw.cmd -B verify
```

Trên macOS/Linux, thay bằng `./mvnw`. Nếu đã có Maven, dùng `mvn -B test`. Test tích hợp mở cổng loopback tạm. Hầu hết ca dùng H2. `PostgresWorkplacePersistenceTest` chạy profile `workplace,postgres` trên PostgreSQL 15 thật qua Testcontainers và kiểm tra schema, `JdbcAttemptStore`, `JdbcPresenceTracker`, rollback JTI và ngày công của hạn mức tự khai. Cần Docker Linux engine để chạy cả bộ. Workflow CI chạy các test backend trên Ubuntu. [Bộ test backend](src/test/java/tmc/) · [Đối chiếu test theo AC](../README.md#m2-checklist)

Commit [`a65cd2d`](https://github.com/lazy128/tmc-qr-attendance/commit/a65cd2dc1c8f304b3c67cd2a1439a97906cf56dd) có 117 test Maven đạt trên Windows và CI GitHub đạt cả backend và frontend. Bộ Testcontainers bổ sung sau commit này. [Test từng bước](src/test/java/tmc/access/application/step/) · [Workflow CI](../.github/workflows/ci.yml)

Để đo tải trên DB mới, đặt `TMC_EMPLOYEE_DEMO_TOKEN` và `TMC_DEVICE_PROOF` đã đăng ký trên Edge, rồi chạy `node scripts/access-load.mjs` từ `backend/`. Script mặc định phát 800 scan trong 30 phút, đo HTTP verify và ghi JSON Lines. Khi chạy hai site, đặt `EDGE_URL`, `SITE_ID`, `STATION_ID`, `TMC_DEVICE_PROOF` và `LOAD_REPORT` cho từng tiến trình. Tạo đường dẫn báo cáo mới cho mỗi lần chạy. Có thể đặt `DURATION_SECONDS=30` để thử nhanh.

Bảng cũ dưới đây đã được ghi trong commit `d4f0d8e` và áp dụng cho bản Phase 2 trước thay đổi về circuit breaker, đối soát và lô lương.

| Lần đo local Phase 2, H2 file và fake Turnstile | ACKED | Lỗi hoặc timeout | Trên 2 giây | Max |
| --- | ---: | ---: | ---: | ---: |
| SITE-1, 30 giây | 800 / 800 | 0 | 0 | 74,55 ms |
| SITE-2, 30 giây | 800 / 800 | 0 | 0 | 58,35 ms |

Lần đo mới dùng mã từ `a65cd2d` cùng các thay đổi trong đợt rà soát kiến trúc, image Edge `sha256:0b5429d090c2`. Hai container `bd` và `la` chạy đồng thời, mỗi container dùng H2 riêng và fake Turnstile. Đồng bộ Central tắt trong phép đo này.

| Lần đo local đồng thời, 30 giây mỗi Edge | ACKED | Lỗi hoặc timeout | UNKNOWN | Trên 2 giây | Max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Bình Dương, `BD-G01` | 800 / 800 | 0 | 0 | 0 | 427,03 ms |
| Long An, `LA-G01` | 800 / 800 | 0 | 0 | 0 | 434,19 ms |

Phép đo rút gọn tính từ lúc gửi HTTP request đến khi Node đọc xong phản hồi Edge. Nó đo luồng xác minh và ACK bình thường. Test tích hợp kiểm tra đối soát `EXPIRED`, circuit breaker và lô lương riêng. Nghiệm thu 30 phút cần chạy tại từng nhà máy với kiosk, LAN và Turnstile thật.

[Ba view kiến trúc](../README.md#architecture-views) · [SOLID](../README.md#solid) · [Checklist và sai lệch](../README.md#m2-checklist)
