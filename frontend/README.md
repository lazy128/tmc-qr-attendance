# Frontend console

Console React và TanStack Start mô phỏng Gate Station và giao diện tra cứu chấm công. UI chỉ gửi request và hiển thị kết quả. Edge là nơi kiểm tra QR, ra quyết định và áp dụng quyền đọc dữ liệu.

## Chạy và kiểm thử

Cần Node.js 24. Chạy trong `frontend/`.

```powershell
Copy-Item .env.example .env
npm ci
npm run dev
```

`.env.example` đặt `VITE_EDGE_API_URL=http://localhost:8080`. Console chạy ở `http://localhost:5173`. Nếu đổi host hoặc cổng frontend, cấu hình `tmc.cors.origins` ở Edge cho đúng origin. Nhập bearer token demo do backend cấu hình ở tab Attendance. Nút tải CSV yêu cầu token HR.

Mở `/employee` để dùng mã đăng nhập nhân viên và mã đăng ký do HR cấp. Mở `/gate` để chọn site/trạm, quét QR bằng camera hoặc nhập tay và xem lịch sử. Mở `/stations` để xem registry. Console ở `/` vẫn hỗ trợ thao tác QR, quét ảnh và báo cáo trên cùng một máy. [Hướng dẫn demo LAN, HTTPS và hai site](../README.md#chạy-demo)

```sh
npm test
npm run typecheck
npm run build
```

Các test kiểm tra route, hợp đồng HTTP, tự làm mới mã và giao diện khi mã hết hạn hay credential bị thu hồi. [Test frontend](src/test)

## Tìm code theo chức năng

| Vị trí | Trách nhiệm |
| --- | --- |
| [`src/routes/`](src/routes/) | Route TanStack và layout của ứng dụng. |
| [`modules/console/`](src/modules/console/) | Ghép các tab và điều phối tương tác bằng `useEdgeConsole`. |
| [`modules/access/`](src/modules/access/) | Form scan, API verify và kết quả tách riêng decision, command, passage. |
| [`modules/userqr/`](src/modules/userqr/) | Trang nhân viên, hiển thị QR và API cấp, đăng ký thiết bị, thu hồi. |
| [`modules/attendance/`](src/modules/attendance/) | API tra cứu theo scope, bảng chấm công và tải CSV. |
| [`modules/shared/`](src/modules/shared/) | HTTP client, địa chỉ Edge, chuẩn hóa lỗi và thành phần UI dùng chung. |
| [`src/components/ui/`](src/components/ui/) | Thư viện thành phần giao diện có thể tái sử dụng. |

## Hợp đồng với Edge

| Tác vụ | API |
| --- | --- |
| Cấp QR ngắn hạn | `POST /qr/v1/tokens` với bearer nhân viên và `X-Device-Proof` |
| Đăng ký thiết bị hoặc thu hồi credential | `POST /qr/v1/credentials/{id}/devices` hoặc `/revoke` với bearer HR |
| Quản lý trạm | `GET/POST /admin/v1/stations` và `PATCH /admin/v1/stations/{id}` |
| Quét cổng | `POST /access/v1/verify` với `X-Station-Id`, `Idempotency-Key` và `X-Correlation-Id` |
| Đọc chấm công | `GET /attendance/v1/records` với bearer token |
| Tải CSV | `GET /reports/v1/exports?month=YYYY-MM` với bearer token HR |

[Cấu hình và vận hành Edge](../backend/README.md) · [Ba view kiến trúc](../README.md#architecture-views)
