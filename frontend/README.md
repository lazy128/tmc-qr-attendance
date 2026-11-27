# Frontend TMC Chấm công

Ứng dụng React và TanStack Start kết nối Edge cùng origin qua `/edge-api`. Edge xác minh phiên, quyền và mã QR.

## Chạy và kiểm thử

Cần Node.js 24. Sau khi chạy backend bằng Docker Compose, thực hiện trong `frontend/`.

```powershell
npm ci
$env:VITE_EDGE_API_URL = '/edge-api'
npm run dev -- --strictPort
```

Mở [TMC Chấm công](http://localhost:5173). Các mục điều hướng xuất hiện theo quyền của tài khoản đang đăng nhập. `/employee` cấp QR cho chính người dùng. `/gate` xác minh tại trạm đã gán cho Bảo vệ. `/attendance` hiển thị bảng công theo phạm vi. `/stations` quản lý tài khoản và trạm.

`TMC_DEV_PROXY_TARGET` mặc định là `http://127.0.0.1:8085`. Đặt `VITE_QR_SCAN_ORIGIN` khi màn hình hiển thị mã dùng localhost nhưng thiết bị quét mở liên kết qua LAN hoặc HTTPS. Triển khai qua proxy cùng origin và giữ cookie bảo mật. [Cách thiết lập bản nộp](../README.md#chạy-bản-nộp)

```powershell
npm run typecheck
npm test
npm run build
```

Bài Playwright dùng tài khoản do quản trị viên tạo, mô phỏng mất phản hồi từ máy chủ và kiểm tra lại kết quả chấm công. [Thiết lập kiểm thử](../README.md#kiểm-thử)

## Tìm code

| Vị trí                    | Chức năng                                               |
| ------------------------- | ------------------------------------------------------- |
| `src/modules/shared/ui/`  | Phiên đăng nhập, đổi mật khẩu và điều hướng theo quyền. |
| `src/modules/userqr/`     | Hồ sơ và QR của nhân viên đang đăng nhập.               |
| `src/modules/access/`     | Trạm quét, thử lại theo mã lượt và quản trị.            |
| `src/modules/attendance/` | Bảng công, hiệu chỉnh và xuất báo cáo.                  |

[API và cấu hình Edge](../backend/README.md) · [Ba view kiến trúc](../README.md#architecture-views)
