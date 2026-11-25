# Nâng cấp mockup TMC FacilityOS

## Mục tiêu
Nâng cấp file HTML mockup đã tải lên thành một bản HTML độc lập, chạy trực tiếp trong trình duyệt. Giữ giao diện, bố cục, menu, vai trò và cấu trúc `render()` / `action()` / `state()` hiện có; không thay trang chính của dự án.

## Phạm vi thực hiện
1. **Trạng thái credential:** gom việc phân loại về `credStatus(c)`; phân biệt rõ ACTIVE, EXPIRED, REVOKED, USED và DEVICE_MISMATCH tại các bảng, Digital ID và hồ sơ nhân viên.
2. **QR động:** TTL demo 30 giây, countdown cập nhật DOM mỗi giây, cảnh báo 10 giây cuối, tự xoay token và ghi log; có chế độ tạm dừng, làm mới thủ công, claims giả lập không có PII/chữ ký thật, xử lý USED và tăng `cver` khi reissue/revoke.
3. **Kiosk:** thêm direction ENTRY/EXIT, trạng thái IN/OUT, bảy tình huống bảo mật được yêu cầu và cập nhật attendance ledger theo direction thực.
4. **Gateway trace:** hiển thị tuần tự 12 bước PASS/FAIL/SKIPPED, thời gian mô phỏng, tổng thời gian so với 2.000 ms, `NO_GATE_COMMAND` khi phù hợp và khối JSON request/response có thể thu gọn, sao chép.
5. **Offline và stations:** thêm STRICT / EDGE_VERIFY, giới hạn tuổi snapshot, tự rơi về STRICT khi quá hạn, queue và kết quả đồng bộ idempotent; quản lý bốn gate station cùng trạng thái kết nối.
6. **Audit & Security:** thêm trang, lọc/tìm kiếm audit log, cảnh báo SUSPICIOUS, đánh dấu đã xem, phân quyền theo vai trò/site và che một phần dữ liệu cá nhân ngoài HR.
7. **Architecture Blueprint:** bổ sung Module, C&C, Allocation và Sequence views; thể hiện dependency, interface/SOLID, connector có nhãn, hai vấn đề M2 và các giả định/câu hỏi mở.
8. **Giới hạn bảo mật:** công khai rằng các lớp bảo vệ chỉ giảm thiểu/phát hiện rủi ro QR, không ngăn tuyệt đối việc chia sẻ ảnh QR còn hiệu lực.

## Kỹ thuật và kiểm tra
- Chỉ dùng một file HTML, vanilla JavaScript/CSS; không backend, thư viện ngoài hay gọi mạng. Giữ dữ liệu nhập được escape bằng `esc()` và giữ responsive ở các breakpoint hiện có.
- Dọn interval countdown khi rời Digital ID; timer không gọi render toàn trang. Mọi token, chữ ký, edge storage, thời gian tải và kết quả tích hợp đều ghi rõ là mô phỏng.
- Mở file trong trình duyệt để kiểm tra countdown/tự xoay, trạng thái EXPIRED, các kịch bản kiosk và trace, hai chế độ offline, Audit, các Architecture views, bảo mật dữ liệu nhập và bố cục desktop/mobile.

## Bàn giao
Lưu HTML hoàn chỉnh có thể tải xuống trong Files. Kèm danh sách thay đổi theo A–I và checklist tự kiểm ngắn trong phần trả lời.