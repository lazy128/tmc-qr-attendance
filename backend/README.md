# Backend Edge

Java 21, Spring Boot 3, Maven và H2. Profile mặc định `workplace` phục vụ đăng nhập, kiểm soát QR, chấm công và báo cáo.

## Chạy Edge

Trên Windows, chạy `.\scripts\start-workplace.ps1 -Initialize -AllowHttp` từ thư mục `backend`. Các lần sau bỏ `-Initialize`. Trên macOS/Linux, đặt `TMC_SESSION_KEY` là khóa bí mật ngẫu nhiên ít nhất 32 byte, mã hóa Base64, cùng `TMC_ADMIN_PASSWORD` cho lần đầu chạy `./mvnw spring-boot:run`. Giữ lại cùng khóa cho các lần khởi động sau.

Với Docker, khai báo cùng biến môi trường rồi chạy `docker compose up --build`. Backend được publish tại `127.0.0.1:8084` và ghi dữ liệu vào volume `workplace-data`. Volume `workplace-exports` lưu bảng tính. Đặt `EDGE_PORT` để đổi cổng publish. `docker compose down` giữ cả hai volume.

| Tham số | Mục đích |
| --- | --- |
| `TMC_SESSION_KEY` | Khóa ký JWT đăng nhập. Không ghi khóa vào Git. |
| `TMC_ADMIN_USERNAME`, `TMC_ADMIN_PASSWORD` | Khởi tạo quản trị viên đầu tiên. Mặc định username là `admin`; mật khẩu do người triển khai đặt. |
| `TMC_SESSION_SECURE`, `TMC_CORS_ORIGINS` | Cookie mặc định Secure. Đặt origin chính xác và chỉ tắt Secure khi kiểm tra HTTP cục bộ. |
| `TMC_DB_USERNAME`, `TMC_DB_PASSWORD` | Thông tin kết nối H2 dạng tệp. Mặc định `sa` với mật khẩu trống cho máy cá nhân. |
| `tmc.edge.site`, `tmc.edge.station` | `SITE-BD` và `BD-G01`. Trạm ra khởi tạo là `BD-G01-OUT`. |
| `tmc.qr.ttl-seconds`, `tmc.qr.keystore` | QR hiệu lực 30 giây. Có thể cấu hình PKCS#12 và mật khẩu qua `TMC_QR_KEYSTORE_PASSWORD` để giữ khóa ES256 khi khởi động lại. QR đã phát trước khi đổi khóa cần được cấp mới. |

## API bản nộp

| API | Quyền và dữ liệu |
| --- | --- |
| `POST /auth/v1/login` | Kiểm tra BCrypt trong DB, cấp JWT phiên qua cookie HttpOnly. |
| `GET /auth/v1/me`, `POST /auth/v1/password`, `POST /auth/v1/logout` | Đọc danh tính, đổi mật khẩu và kết thúc phiên trong DB. |
| `GET /workplace/v1/me/employee`, `POST /workplace/v1/me/qr`, `GET /workplace/v1/me/attendance` | Lấy hồ sơ, cấp QR và xem trạng thái công của chính nhân viên trong phiên. |
| `GET /workplace/v1/station` | Chỉ Bảo vệ, trả trạm đang hoạt động được gán. |
| `POST /access/v1/verify` | Kiểm tra phiên Bảo vệ, trạm, JWT QR, quyền vào/ra và idempotency. `Idempotency-Key` trùng `eventId`. |
| `GET /access/v1/scans/{id}` | Khôi phục kết quả theo mã lượt và trạm được gán. |
| `GET/POST /workplace/v1/accounts`, `PATCH /workplace/v1/accounts/{username}` | Chỉ Quản trị viên, quản lý tài khoản và trạng thái. |
| `GET/POST /admin/v1/stations`, `PATCH /admin/v1/stations/{id}` | Quản lý registry trạm, hướng, nhóm và giờ hoạt động. |
| `GET /attendance/v1/timesheets?month=YYYY-MM` | Tra bảng công trong phạm vi của phiên. |
| `POST /attendance/v1/timesheets/corrections`, `GET /attendance/v1/timesheets/{csv,xlsx}` | Chỉ Nhân sự, bổ sung giờ ra và xuất báo cáo. |

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
| `POST /edge/v1/sync` | Nhận payload outbox tại Central mô phỏng. |

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
| `tmc.edge.snapshot.fresh` | `1` là FRESH, `0` là STALE. |
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

Trên macOS/Linux, thay bằng `./mvnw`. Nếu đã có Maven, dùng `mvn -B test`. Test tích hợp mở cổng loopback tạm và dùng H2 trong bộ nhớ. Workflow CI chạy các test backend trên Ubuntu. [Bộ test backend](src/test/java/tmc/) · [Đối chiếu test theo AC](../README.md#m2-checklist)

Ngày 25/11/2026, `mvnw.cmd clean verify` chạy trên Windows với JDK 21 và một kho dependency Maven trống. Kết quả là **79 test đạt** và JAR được đóng gói thành công. Log đầy đủ của lần chạy local nằm ở `verification-logs/maven-verify.log` từ thư mục gốc. [Test từng bước](src/test/java/tmc/access/application/step/) · [Workflow CI](../.github/workflows/ci.yml)

Để đo tải trên DB mới, đặt `TMC_EMPLOYEE_DEMO_TOKEN` và `TMC_DEVICE_PROOF` đã đăng ký trên Edge, rồi chạy `node scripts/access-load.mjs` từ `backend/`. Script mặc định phát 800 scan trong 30 phút, đo HTTP verify và ghi JSON Lines. Khi chạy hai site, đặt `EDGE_URL`, `SITE_ID`, `STATION_ID`, `TMC_DEVICE_PROOF` và `LOAD_REPORT` cho từng tiến trình. Tạo đường dẫn báo cáo mới cho mỗi lần chạy. Có thể đặt `DURATION_SECONDS=30` để thử nhanh. [Khởi động và kiểm tra hai site](../README.md#demo-hai-nhà-máy)

| Lần đo local Phase 2, H2 file và fake Turnstile | ACKED | Lỗi hoặc timeout | Trên 2 giây | Max |
| --- | ---: | ---: | ---: | ---: |
| SITE-1, 30 giây | 800 / 800 | 0 | 0 | 74,55 ms |
| SITE-2, 30 giây | 800 / 800 | 0 | 0 | 58,35 ms |

Số liệu trên là lần đo trước khi bổ sung observability. Phép đo tính từ HTTP request đến khi Node đọc được response Edge, chưa gồm thời gian render kiosk, LAN và Turnstile thật. Kịch bản nghiệm thu 30 phút chưa được chạy.

[Ba view kiến trúc](../README.md#architecture-views) · [SOLID](../README.md#solid) · [Checklist và sai lệch](../README.md#m2-checklist)
