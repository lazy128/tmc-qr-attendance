# ADR 006 · Kênh chấm công và mức bằng chứng

## Quyết định

`GATE_SCAN` là lượt bảo vệ hoặc kiosk đọc QR cá nhân của nhân viên. Token ES256 dùng cho kênh này có hiệu lực mặc định 30 giây. `STATION_QR` là lượt nhân viên đăng nhập trên trình duyệt đã duyệt và đọc mã tại trạm. QR tại trạm có hiệu lực 15 giây, gắn site, trạm và hướng. [ADR 001](ADR-001-station-qr-presence.md)

`PERSONAL_SELF` là kênh tự khai của profile `personal-self-demo`. Kênh tắt mặc định, dùng token 60 giây với audience riêng và ghi `SELF_REPORTED`. Mặc định tối đa bốn lượt tự khai hợp lệ cho mỗi nhân viên trong một ngày công. Kiểm tra hạn mức và lưu lượt chạy trong cùng giao dịch, giữ khóa credential của nhân viên. API trả 429 khi hết lượt. Gửi lại cùng lượt giữ kết quả cũ.

`ACK_ONLY` ghi nhận phản hồi của adapter, đủ điều kiện dự tính giờ công nhưng có cờ Nhân sự xem. `PASSAGE_CONFIRMED` là chứng cứ passage riêng. `MANUAL_PASSAGE` là lượt Nhân sự đã đối soát có qua cổng. `SELF_REPORTED` vào danh sách cần xem và không cộng giờ lương. `MANUAL_NO_PASSAGE` đóng đối soát mà không tạo giờ công.

## Hệ quả

Kênh, quyết định, trạng thái lệnh và chứng cứ được lưu riêng. Kênh tự khai bỏ qua bước gửi lệnh mở cổng. Hạn mức áp dụng chung cho các trình duyệt và cả hai hướng của một tài khoản trong cùng ngày công. Các lượt thuộc cổng khu vực được lưu cho kiểm soát truy cập và không cộng giờ công.

## Rủi ro và lựa chọn khác

Ảnh QR tại trạm vẫn có thể bị chuyển tiếp trong 15 giây. Cookie tin cậy có thể bị sao chép nếu trình duyệt bị chiếm quyền. Việc đối chiếu danh tính tại chỗ vẫn thuộc trách nhiệm bảo vệ. Bản nộp dùng xác nhận phần mềm cho luồng chính. Chứng cứ cảm biến vật lý cần tích hợp và nghiệm thu riêng.
