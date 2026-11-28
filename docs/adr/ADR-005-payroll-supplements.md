# ADR 005 · Lô gốc và lô lương bổ sung

## Quyết định

Nhân sự hoặc quản trị viên chốt lô CSV theo tháng cho cả hai nhà máy. Bảng `payroll_batch` lưu số lô, CSV, ảnh dữ liệu và người tạo. Lô sau so sánh bảng công tại thời điểm chốt với ảnh dữ liệu của lô gần nhất dựa trên khóa nhân viên và ngày công. `UPSERT` thay toàn bộ dòng bằng giá trị tuyệt đối, `VOID` hủy dòng đã xuất trước đó.

Giao dịch tạo lô giữ khóa trong DB. Nếu dữ liệu không đổi, lần chốt lại dùng cùng số lô. Việc tạo và tải CSV được ghi audit.

## Hệ quả

Lượt đồng bộ muộn hoặc hiệu chỉnh xuất riêng trong lô bổ sung. Lô gốc và các lô đã xuất vẫn tải lại theo số thứ tự. Lượt tự khai chưa có giờ vào đủ điều kiện không sinh dòng lương. Nhân sự có thể đối chiếu chính xác dữ liệu đã xuất.

## Rủi ro và lựa chọn khác

Payroll cần áp dụng các lô theo số thứ tự và bỏ qua lô đã nhận với cùng khóa tháng và số lô. Bản nộp tạo tệp và lịch sử tải. Tự gửi, xác nhận nhận tệp và xử lý từ chối phụ thuộc hợp đồng Payroll thực tế.
