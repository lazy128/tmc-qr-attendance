# ADR 002 · Vòng đời lệnh cổng và đối soát

## Quyết định

Edge lưu lượt `SENT`, JTI và outbox trong cùng giao dịch trước khi gửi lệnh. Phản hồi xác định của adapter ghi `ACKED` hoặc `FAILED`. Timeout sau khi lệnh có thể đã được gửi ghi `UNKNOWN`. Worker hỏi lại trạng thái qua `CommandStatusReader`. Lượt `SENT` hoặc `UNKNOWN` quá `tmc.turnstile.reconciliation-seconds` chuyển sang `EXPIRED`. Mặc định là 120 giây từ lúc Edge nhận lượt.

Bảo vệ xem hàng chờ của trạm được giao. Nhân sự hoặc quản trị viên đối soát, nhập lý do và ghi `MANUAL_PASSAGE` hoặc `MANUAL_NO_PASSAGE` vào lượt đã hết hạn. Bảng `gate_incident_review` lưu kết luận, người duyệt và thời điểm. Audit ghi việc hết hạn và kết quả đối soát.

Mỗi trạm có circuit breaker riêng. Mặc định ba lỗi liên tiếp làm mạch của trạm mở trong 15 giây. Sau đó mạch cho một lệnh thử. `TimeoutGateCommandSender` giới hạn xử lý đồng thời ở bốn luồng và một hàng đợi chung 64 lệnh. Khi mạch mở, hàng đợi đầy hoặc một lệnh bị lấy ra khỏi hàng đợi trước khi chạy, adapter trả `FAILED`.

## Hệ quả

Khi lượt chuyển `EXPIRED`, lượt cũ ngừng chặn yêu cầu mới. Xác nhận thủ công đã qua cổng chấm công dùng thời điểm của lượt gốc và giữ trạng thái lệnh `EXPIRED`. Đối soát lượt cũ giữ hiện diện của lượt mới hơn. Lượt xác nhận chưa qua cổng giữ nguyên giờ công.

Mạch riêng giới hạn tác động của chuỗi lỗi tại một trạm. Pool chung giới hạn tài nguyên Edge. Trường hợp pool bị chiếm hết vẫn có thể ảnh hưởng trạm khỏe.

## Rủi ro và lựa chọn khác

Bulkhead riêng theo trạm là một lựa chọn nếu tích hợp vendor cho thấy cần phân bổ luồng riêng. Cổng và cảm biến thật cần kiểm tra timeout, tính duy nhất của ID lệnh và kết quả đến sau hạn. Adapter HTTP hiện là stub chờ hợp đồng vendor.
