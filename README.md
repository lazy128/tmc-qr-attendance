# TMC QR Attendance & Access Control

[![Backend CI](https://github.com/lazy128/tmc-qr-attendance/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/lazy128/tmc-qr-attendance/actions/workflows/ci.yml)

Ứng dụng mẫu cho Assignment M2. **Access** là module chính của Task 2. Demo cho phép cấp QR ngắn hạn, kiểm tra truy cập tại Edge, điều khiển cổng mô phỏng, ghi nhận công và tải CSV theo tháng.

## Chạy demo

Cần **Java 21** và **Node.js 24**. Maven Wrapper tự tải Maven trong lần chạy đầu. Mở hai terminal từ thư mục gốc.

**Terminal 1 · Backend**

```powershell
cd backend
$env:TMC_HR_DEMO_TOKEN = 'tmc-local-hr'
.\mvnw.cmd spring-boot:run
```

Trên macOS/Linux, đặt token bằng `export TMC_HR_DEMO_TOKEN=tmc-local-hr` và chạy `./mvnw spring-boot:run`. Docker Compose cũng có sẵn. [Cấu hình backend và Docker](backend/README.md)

**Terminal 2 · Frontend**

```powershell
cd frontend
Copy-Item .env.example .env
npm ci
npm run dev
```

Mở [console](http://localhost:5173). Ở **Access check**, giữ `SITE-1` / `GATE-1` và `demo-credential` / `demo-device`. Bấm **Issue QR token**, rồi **Verify at gate** trong 30 giây. Ở **Attendance records**, nhập `tmc-local-hr`, bấm **Load records** và chọn tháng cần tải CSV. Bản ghi xuất hiện sau khi worker xử lý outbox.

## Chạy kiểm thử

| Phần | Thư mục | Lệnh |
| --- | --- | --- |
| Backend | `backend/` | `.\mvnw.cmd test` trên Windows hoặc `./mvnw test` trên macOS/Linux. Dùng `verify` để chạy thêm bước đóng gói JAR. |
| Frontend | `frontend/` | `npm test`, `npm run typecheck`, `npm run build`. |
| GitHub Actions | `.github/workflows/ci.yml` | Chạy `mvn -B test` cho backend trên Ubuntu với Java 21. |

## Cách tổ chức dự án

```text
backend/                 Edge API, nghiệp vụ, H2 và các worker
frontend/                Console React, chia theo access, userqr và attendance
docs/                    Kiến trúc hiện thực, bằng chứng SOLID và checklist M2
Phase1_Architecture.md   Kiến trúc thiết kế, ADR và traceability Phase 0–1
.github/workflows/        CI backend
```

Backend dùng **modular monolith** với **ports và adapters**. Console gọi Edge. Edge kiểm tra QR và lưu quyết định ở H2 cục bộ trước khi gửi lệnh cổng. Attendance và Central nhận dữ liệu qua outbox ngoài đường xử lý scan.

### SOLID trong code

| Nguyên lý | Điểm bắt đầu để đọc code |
| --- | --- |
| S | [Access steps](backend/src/main/java/tmc/access/application/step/) chia từng bước xác minh theo trách nhiệm. |
| O | [VerifyAccessUseCase](backend/src/main/java/tmc/access/application/VerifyAccessUseCase.java) nhận danh sách step qua constructor. |
| L | [Contract test](backend/src/test/java/tmc/access/infrastructure/turnstile/GateCommandSenderContractTest.java) áp dụng cùng quy tắc command/passage cho các adapter. |
| I | [Access ports](backend/src/main/java/tmc/access/domain/port/) tách gửi lệnh, đọc trạng thái và nhận chứng cứ. |
| D | [Bootstrap](backend/src/main/java/tmc/bootstrap/) ghép adapter với port. [ArchUnit](backend/src/test/java/tmc/architecture/ArchitectureRulesTest.java) kiểm tra lớp và chiều phụ thuộc. |

| Muốn tìm | Tài liệu |
| --- | --- |
| Cấu hình, API, health, metrics, log và đo tải | [Backend Edge](backend/README.md) |
| Cấu trúc UI, API client và cấu hình kết nối | [Frontend console](frontend/README.md) |
| Module, luồng xử lý và triển khai của code hiện tại | [Kiến trúc hiện thực Phase 2](docs/architecture.md) |
| Code và test chứng minh từng nguyên lý SOLID, mức bao phủ AC-01 đến AC-10 | [SOLID và đối chiếu yêu cầu M2](docs/solid-and-traceability.md) |

Bản nộp sử dụng danh tính, Turnstile và gửi HR mô phỏng. Kết quả đo tải local có ranh giới riêng và chưa xác nhận NFR-01 ở nhà máy.
