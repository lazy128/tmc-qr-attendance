# ADR 003 · Ngày công theo ca

## Quyết định

`ShiftCalendar` là logic dùng chung cho phép vào/ra, trạng thái công, bảng công và hạn mức tự khai. Ngày công là ngày bắt đầu lần ca có cửa sổ chứa thời điểm quét, gồm khoảng cho đến sớm và ra muộn. Khi cửa sổ chồng nhau, chọn ca bắt đầu gần nhất. Ngoài cửa sổ, dùng mốc ngày công cấu hình. Mốc mặc định là 06:00 giờ Việt Nam.

Bản nộp mặc định cho một cặp vào/ra mỗi ngày công. Tăng `tmc.attendance.pairs-per-day` khi nghiệp vụ cho phép ra giữa ca rồi vào lại. Khi đã đủ số cặp, lượt vào mới nhận `SHIFT_COMPLETED`. Ngoại lệ sửa hiện diện có audit vẫn áp dụng.

## Hệ quả

Vào sớm trước mốc toàn cục và ra muộn sau ca đêm được phân về ngày của ca tương ứng. Bộ ghép cặp trong bảng công cộng thời gian làm việc của từng cặp và trừ khoảng nghỉ giữa các cặp. Lượt hiệu chỉnh giờ ra cũng kiểm tra cùng quy tắc ngày công.

## Rủi ro và lựa chọn khác

Bản nộp dùng cửa sổ ca từ cấu hình và ca được gán cho nhân viên. Lịch thay ca theo ngày hoặc đổi múi giờ giữa nhà máy cần một nguồn lịch đủ chi tiết và đối soát lại dữ liệu trước khi triển khai.
