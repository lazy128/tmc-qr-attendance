# Frontend TMC Chấm công

Ứng dụng React và TanStack Start hiển thị mã QR, màn hình quét cổng và bảng công. Edge kiểm tra QR, ra quyết định và áp dụng quyền đọc dữ liệu.

## Chạy và kiểm thử

Cần Node.js 24. Chạy trong `frontend/`.

```powershell
Copy-Item .env.example .env
npm ci
npm run dev
```

`.env.example` trỏ tới profile nghiệp vụ ở `http://127.0.0.1:8084`. Frontend chạy ở `http://localhost:5173`. Nếu đổi host hoặc cổng frontend, đặt `tmc.cors.origins` ở Edge theo origin tương ứng.

Mở `/employee` để chọn nhân viên và hiển thị QR. Mở `/gate` để chọn cổng và quét bằng camera hoặc ảnh. Mở `/attendance` để xem bảng công, sửa lượt thiếu giờ ra và tải báo cáo theo quyền. Registry trạm có ở `/stations`. [Hướng dẫn demo LAN, HTTPS và hai site](../README.md#chạy-demo)

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
| [`modules/access/`](src/modules/access/) | Form scan, API verify và kết quả tách riêng decision, command, passage. |
| [`modules/userqr/`](src/modules/userqr/) | Trang nhân viên và mã QR tự làm mới. |
| [`modules/attendance/`](src/modules/attendance/) | API tra cứu theo scope, bảng chấm công và tải CSV. |
| [`modules/shared/`](src/modules/shared/) | HTTP client, địa chỉ Edge, chuẩn hóa lỗi và thành phần UI dùng chung. |

## Hợp đồng với Edge

| Tác vụ | API |
| --- | --- |
| Chọn tài khoản và cấp QR demo | `GET /demo/v1/employees` và `POST /demo/v1/employees/{id}/qr` |
| Quản lý trạm | `GET/POST /admin/v1/stations` và `PATCH /admin/v1/stations/{id}` |
| Quét cổng | `POST /access/v1/verify` với `X-Station-Id`, `Idempotency-Key` và `X-Correlation-Id` |
| Đọc chấm công | `GET /attendance/v1/timesheets?month=YYYY-MM` với bearer token |
| Tải CSV hoặc XLSX | `GET /attendance/v1/timesheets/{csv,xlsx}?month=YYYY-MM` với bearer token HR |

[Cấu hình và vận hành Edge](../backend/README.md) · [Ba view kiến trúc](../README.md#architecture-views)
