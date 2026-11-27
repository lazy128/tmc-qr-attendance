# ADR 001 · Xác minh chấm công bằng QR tại cổng

## Quyết định

Trạm bảo vệ xác minh QR cá nhân qua kênh `GATE_SCAN`. JWT ES256 của kênh này có thời hạn 30 giây. Bảo vệ đối chiếu người xuất trình QR với hồ sơ nhân viên ở chi nhánh được phân công.

Kênh `STATION_QR` hiển thị mã ký HS256 tại cổng. Mã có hiệu lực 15 giây, làm mới mỗi 5 giây và ràng buộc chi nhánh, trạm và hướng quét. Bộ kiểm tra chữ ký cho phép lệch đồng hồ 5 giây. Backend vẫn yêu cầu hạn 15 giây còn hiệu lực để ghi lượt mới. Mỗi tài khoản phát mã tối đa 30 lần trong một phút. Nhật ký lưu tài khoản, trạm và hướng phát mã. Phiên nhân viên phải đi kèm cookie của trình duyệt đã được duyệt. Trình duyệt đầu tiên được tin cậy, các trình duyệt sau cần nhân viên trên thiết bị tin cậy hoặc Nhân sự duyệt. Giới hạn ba thiết bị được kiểm tra trong giao dịch cùng với audit thêm và gỡ.

Kênh `PERSONAL_SELF` tắt mặc định. Profile `personal-self-demo` bật QR tự khai 60 giây dành riêng cho kênh này. Lượt được gắn `SELF_REPORTED` để Nhân sự xem, không gửi lệnh mở cổng và không cộng giờ lương.

Ngày công là ngày bắt đầu ca nếu thời điểm quét thuộc cửa sổ của ca, gồm thời gian cho phép đến sớm và ra muộn. Ngoài cửa sổ, hệ thống dùng mốc ngày công cấu hình. Bộ kiểm tra vào/ra và bảng công dùng chung cách xác định ngày. Quy tắc nghiệp vụ mặc định là một cặp vào/ra mỗi ngày công. Có thể đặt `tmc.attendance.pairs-per-day` lớn hơn một để ghi nhiều cặp và trừ thời gian giữa các cặp. Bảo vệ vẫn có thể sửa hiện diện kèm audit. Lượt trước đang chờ cổng được báo bằng `COMMAND_PENDING`.

Bản ghi cũ và payload đồng bộ cũ thiếu thông tin kênh dùng giá trị tương thích `GATE_SCAN`. Nhãn đó không đủ để xác định nguồn của các lượt phát sinh trước thay đổi.

## Rủi ro và lựa chọn khác

Người có mặt có thể chụp và chuyển ảnh mã cổng cho một nhân viên cùng chi nhánh có trình duyệt đã duyệt. Người nhận vẫn có thể gửi lượt trong cửa sổ 15 giây. Cookie nhận diện trình duyệt đã duyệt và có thể bị sao chép nếu thiết bị bị chiếm quyền. Khi cần xác minh người thật tại cổng, bảo vệ phải đối chiếu danh tính trực tiếp.

Wi-Fi SSID và geofence chưa được áp dụng. Trình duyệt web thường không cung cấp SSID cho ứng dụng. Geofence cần quyền vị trí, phụ thuộc tín hiệu và không xác minh được một người đã đi qua đúng cổng.
