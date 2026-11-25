# Backend Edge

Java 21, Spring Boot 3, Maven và H2. Service này chạy Access tại Edge và cung cấp các API QR, attendance và báo cáo của demo.

## Chạy Edge

Chạy trong `backend/`. Dùng Java 21 và chọn một cách khởi động.

**Maven Wrapper trên Windows**

```powershell
$env:TMC_HR_DEMO_TOKEN = 'tmc-local-hr'
.\mvnw.cmd spring-boot:run
```

Trên macOS/Linux, dùng `export TMC_HR_DEMO_TOKEN=tmc-local-hr` và `./mvnw spring-boot:run`. Backend mặc định nghe tại `127.0.0.1:8080` và lưu dữ liệu H2 ở `data/`. Khi dùng JAR, chạy `.\mvnw.cmd verify` rồi `java -jar target/qr-attendance-edge-1.0.0.jar` với cùng cấu hình.

**Docker Compose**

```powershell
$env:TMC_HR_DEMO_TOKEN = 'tmc-local-hr'
docker compose up --build
```

Compose build JAR bằng Maven, chạy bằng JRE 21 và publish cổng trên loopback. Đây là cấu hình demo với fake Turnstile. Đặt `EDGE_DEMO_PORT` nếu cổng 8080 đang được dùng. `docker compose down` dừng service và giữ volume H2 `edge-data`.

## Luồng và API demo

| API | Trách nhiệm |
| --- | --- |
| `POST /qr/v1/tokens` | Cấp token cho cặp credential và device đã có. Cặp mẫu là `demo-credential` / `demo-device`. |
| `POST /qr/v1/credentials/{id}/revoke` | Thu hồi credential và tăng phiên bản. |
| `POST /access/v1/verify` | Xác minh scan tại Edge. Gửi `X-Station-Id: GATE-1`, `siteId=SITE-1`, hướng và thời gian scan. `Idempotency-Key`, nếu gửi, phải trùng `eventId`. |
| `POST /gates/v1/passage-events` | Nhận chứng cứ passage riêng. Demo yêu cầu bearer của guard và ID trạm. |
| `POST /gates/v1/presence-corrections` | Guard sửa quên quét ra bằng `lastEntryEventId` và `reason=MISSED_EXIT`. Hành động được audit và chỉ đổi presence. |
| `GET /attendance/v1/records` | Trả dữ liệu trong scope của bearer token demo. |
| `GET /reports/v1/exports?month=YYYY-MM` | Tải CSV tháng bằng bearer token HR. Chỉ bản ghi ACKED đủ điều kiện và không có cờ suspicious được đưa vào CSV. |
| `POST /edge/v1/sync` | Nhận payload outbox tại Central mô phỏng. |

### Danh tính và cấu hình

| Danh tính demo | Biến môi trường | Scope hoặc tác vụ |
| --- | --- | --- |
| Employee | `TMC_EMPLOYEE_DEMO_TOKEN` | Own attendance |
| Manager | `TMC_MANAGER_DEMO_TOKEN` | Team attendance |
| HR | `TMC_HR_DEMO_TOKEN` | Authorized attendance, tải CSV |
| Guard | `TMC_GUARD_DEMO_TOKEN` | Chứng cứ passage và sửa presence ở trạm được giao |
| System Administrator | `TMC_ADMIN_DEMO_TOKEN` | Có policy cấu hình demo. API attendance từ chối role này. |

Mỗi token demo phải khác nhau. Token rỗng vô hiệu hóa danh tính tương ứng. Gửi token qua `Authorization: Bearer <token>`. Dùng dữ liệu mẫu khi chạy demo.

| Cấu hình | Mặc định và cách dùng |
| --- | --- |
| `tmc.edge.site`, `tmc.edge.station` | `SITE-1`, `GATE-1`. |
| `tmc.edge.local-demo-authority`, `tmc.edge.snapshot-file` | Demo đọc credential và revocation trực tiếp từ H2. Có thể dùng file snapshot có `generatedAt`, `revoked`, `stationSites`. Tắt demo authority khi cần mô phỏng fail-closed. |
| `tmc.edge.passage-sensor` | `false`, nên scan mặc định trả passage `NOT_AVAILABLE`. Kích hoạt sensor đặt passage ban đầu là `UNCONFIRMED`. |
| `tmc.qr.keystore`, `tmc.qr.signing-kid` | Demo sinh khóa ES256 khi khởi động. Có thể dùng PKCS#12 với `TMC_QR_KEYSTORE_PASSWORD`; các alias hợp lệ cung cấp public key cho verify. |
| `tmc.central.enabled`, `tmc.central.url` | Dispatch tắt mặc định. Khi bật, worker gọi URL Central bất đồng bộ. |
| `tmc.hr.zone`, `tmc.hr.schedule` | `Asia/Ho_Chi_Minh` và job chạy mỗi 5 phút trong ngày 1. Dùng `tmc.hr.schedule=-` để tắt. |
| `tmc.cors.origins` | Cho phép frontend Vite ở `localhost:5173` và `127.0.0.1:5173`. |

TTL 30 giây, skew 5 giây, snapshot tối đa 15 phút, timeout Turnstile 800 ms và lease 60 giây là tham số demo, chờ TMC xác nhận. Job HR lưu batch tháng trước cùng các mốc ready, sent và ACK mô phỏng.

## Health, metrics và log

`GET /actuator/health` hiện trạng thái tổng. Snapshot stale trả `503 OUT_OF_SERVICE`. Khi truy vấn outbox thất bại, health báo `DOWN`.

| Metric | Xem qua `/actuator/metrics/<tên>` |
| --- | --- |
| `tmc.edge.snapshot.fresh` | `1` là FRESH, `0` là STALE. |
| `tmc.edge.outbox.depth` | Số dòng đang chờ xử lý. Chỉ tính backlog Central khi dispatch được bật. |
| `tmc.edge.verify.decisions` | Kết quả ALLOW/DENY, kể cả response idempotent. Lọc bằng `?tag=decision:DENY` hoặc `?tag=reasonCode:REPLAY`. |
| `tmc.edge.gate.commands` | Outcome ban đầu theo `status`, gồm ACKED, UNKNOWN và FAILED. Replay idempotent không gửi thêm lệnh. |
| `http.server.requests` | Thời gian server. Lọc verify bằng `?tag=method:POST&tag=uri:%2Faccess%2Fv1%2Fverify`. |

Metric quyết định và timer xuất hiện sau khi có request tương ứng. Log verify có correlation ID và mã kết quả. Client có thể gửi một ID vô danh hợp lệ trong `X-Correlation-Id` hoặc đọc ID do server sinh từ response.

## Chạy test và đo tải

```powershell
.\mvnw.cmd -B test
.\mvnw.cmd -B verify
```

Trên macOS/Linux, thay bằng `./mvnw`. Nếu đã có Maven, dùng `mvn -B test`. Test tích hợp mở cổng loopback tạm và dùng H2 trong bộ nhớ. Workflow CI chạy các test backend trên Ubuntu. [Bộ test backend](src/test/java/tmc/) · [Đối chiếu test theo AC](../docs/solid-and-traceability.md)

Sau lần tổ chức lại package, `mvnw.cmd verify` chạy thành công **29 test** và đóng gói JAR. Test mới kiểm tra vòng phụ thuộc, tính tương thích của payload đang lưu và việc Central nhận lại cùng payload mà không xử lý trùng.

Để đo tải fresh DB đang chạy, dùng `node scripts/access-load.mjs` từ `backend/`. Mặc định script phát 800 scan trong 30 phút cho một site, đo HTTP verify và ghi JSON Lines. Để chạy đồng thời hai site, dùng hai instance và DB riêng, đặt `EDGE_URL`, `SITE_ID`, `STATION_ID` và `LOAD_REPORT` cho từng tiến trình. Tạo đường dẫn báo cáo mới cho mỗi lần chạy. Có thể đặt `DURATION_SECONDS=30` để thử nhanh.

| Lần đo local Phase 2, H2 file và fake Turnstile | ACKED | Lỗi hoặc timeout | Trên 2 giây | Max |
| --- | ---: | ---: | ---: | ---: |
| SITE-1, 30 giây | 800 / 800 | 0 | 0 | 74,55 ms |
| SITE-2, 30 giây | 800 / 800 | 0 | 0 | 58,35 ms |

Số liệu trên là lần đo trước khi bổ sung observability. Phép đo tính từ HTTP request đến khi Node đọc được response Edge, chưa gồm thời gian render kiosk, LAN và Turnstile thật. Kịch bản nghiệm thu 30 phút chưa được chạy.

[Module và luồng xử lý](../docs/architecture.md) · [SOLID, checklist và sai lệch so với thiết kế](../docs/solid-and-traceability.md)
