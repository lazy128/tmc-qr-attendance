# ADR về xác minh khuôn mặt tại cổng

**Trạng thái** · Đề xuất sau M2. Luồng truy cập hiện tại không bật sinh trắc. Port `BiometricVerifier` và `FakeBiometricVerifier` mô phỏng `MATCH`, `NO_MATCH`, `SKIPPED` trong test. Fake mặc định trả `SKIPPED`.

## Quyết định đề xuất

- Chỉ xem xét so khớp 1:1 sau khi QR xác định credential. So khớp tại Edge hoặc ngay trên thiết bị. Đây là yếu tố thứ hai để giảm rủi ro đưa QR cho người khác.
- Không lưu ảnh, video hoặc khung hình. Sinh template tại nơi thu nhận, mã hóa bằng khóa được quản lý riêng và lưu trong kho biệt lập với dữ liệu chấm công. Giới hạn quyền truy cập, ghi nhận đồng ý khi áp dụng và ngày hết hạn lưu. Xóa template khi hết hạn hoặc khi rút đồng ý theo chính sách được duyệt.
- Trường sinh trắc duy nhất trong audit là `faceMatch` (`MATCH`, `NO_MATCH`, `SKIPPED`). Dữ liệu ảnh, template và điểm so khớp không được đưa vào audit, log hoặc sự kiện đồng bộ.
- Trước khi thử nghiệm với nhân viên, TMC và pháp chế xác nhận mục đích, căn cứ xử lý, quy trình đồng ý hoặc rút lại và thời hạn lưu. Có quy trình hỗ trợ tại cổng và phương án xác minh thay thế cho người không tham gia.

## Điều kiện xem xét triển khai

Đo tỷ lệ từ chối nhầm và chấp nhận nhầm ở các điều kiện ánh sáng và nhóm người dùng khác nhau. Kiểm tra giả mạo qua ảnh hoặc video và đánh giá nguy cơ lộ template. Đo tổng độ trễ tại cổng so với ngân sách ≤ 2 giây. Cần xác nhận nguồn camera, quy trình hỗ trợ của bảo vệ và trách nhiệm vận hành trước khi đưa dữ liệu thật vào thử nghiệm.
