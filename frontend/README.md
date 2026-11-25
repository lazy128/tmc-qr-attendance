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

```sh
npm test
npm run typecheck
npm run build
```

Các test kiểm tra route `/` và hợp đồng API qua `fetch` giả lập. [Test frontend](src/test)

Lần kiểm tra gần nhất chạy qua **5 test**, kiểm tra TypeScript và build client/server.

## Tìm code theo chức năng

| Vị trí | Trách nhiệm |
| --- | --- |
| [`src/routes/`](src/routes/) | Route TanStack và layout của ứng dụng. |
| [`modules/console/`](src/modules/console/) | Ghép các tab và điều phối tương tác bằng `useEdgeConsole`. |
| [`modules/access/`](src/modules/access/) | Form scan, API verify và kết quả tách riêng decision, command, passage. |
| [`modules/userqr/api/`](src/modules/userqr/api/) | Client cấp và thu hồi QR. Nút demo được đặt cạnh form scan để thao tác liền mạch. |
| [`modules/attendance/`](src/modules/attendance/) | API tra cứu theo scope, bảng chấm công và tải CSV. |
| [`modules/shared/`](src/modules/shared/) | HTTP client, địa chỉ Edge, chuẩn hóa lỗi và thành phần UI dùng chung. |
| [`src/components/ui/`](src/components/ui/) | Thư viện thành phần giao diện có thể tái sử dụng. |

## Hợp đồng với Edge

| Tác vụ | API |
| --- | --- |
| Cấp QR ngắn hạn | `POST /qr/v1/tokens` |
| Thu hồi credential | `POST /qr/v1/credentials/{id}/revoke` |
| Quét cổng | `POST /access/v1/verify` với `X-Station-Id`, `Idempotency-Key` và `X-Correlation-Id` |
| Đọc chấm công | `GET /attendance/v1/records` với bearer token |
| Tải CSV | `GET /reports/v1/exports?month=YYYY-MM` với bearer token HR |

[Cấu hình và vận hành Edge](../backend/README.md) · [Ba view kiến trúc](../README.md#architecture-views)
