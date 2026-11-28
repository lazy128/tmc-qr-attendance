# ADR 004 · Central lưu trạng thái đồng bộ trong DB

## Quyết định

Edge giữ outbox cục bộ và gửi bất đồng bộ khi bật đồng bộ Central. API nhận batch yêu cầu bearer token của kết nối Edge. Central lưu payload theo `eventId` trong `central_access_event` và site đầu tiên của mỗi JTI trong `central_token_use`. Kho JDBC dùng hàng khóa `central_sync_lock` để tuần tự hóa đọc, so sánh và ghi kết quả trong giao dịch.

Gửi cùng event và cùng định danh trả kết quả trùng. Nếu cùng JTI xuất hiện ở site khác, lượt đến sau được gắn `SUSPICIOUS`. Central nhận các thay đổi hợp lệ của trạng thái lệnh và chứng cứ theo vòng đời của lượt.

## Hệ quả

Các khóa chống trùng còn sau khi backend khởi động lại. Edge có thể gửi lại outbox khi mất phản hồi. Kết quả nghi vấn được giữ để đối soát và loại khỏi tổng giờ tính lương. Không cần broker riêng cho luồng của bản nộp.

## Rủi ro và lựa chọn khác

Phát hiện JTI khác site diễn ra sau khi đồng bộ. Cổng có thể đã xử lý lượt trước khi Central nhận được cảnh báo. Khóa chung đơn giản hóa nhất quán và giới hạn mức song song tại Central. Hai Edge độc lập cần đo tải và kiểm tra quy trình thu hồi qua snapshot.
