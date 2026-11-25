# SOLID và đối chiếu yêu cầu M2

**Phạm vi nộp Task 2 là Access.** Đề M2 trang 3 yêu cầu hiện thực ít nhất một module chính và áp dụng SOLID. Bản demo hiện thực luồng Access tại Edge, cùng UserQr, Attendance và Sync ở mức hỗ trợ luồng chính. Mức bao phủ **Task 2** và mức nghiệm thu **toàn hệ thống** được đánh giá riêng theo Phần I.

## Bằng chứng SOLID

| Nguyên lý | Áp dụng ở đâu | Bằng chứng trong code và test |
| --- | --- | --- |
| **S · Single Responsibility** | Access chia logic xác minh thành các bước có một lý do thay đổi. Attendance tách áp dụng policy khỏi định dạng CSV. | [Các VerificationStep](../backend/src/main/java/tmc/access/application/step/), [RecordAttendance](../backend/src/main/java/tmc/attendance/application/RecordAttendance.java), [AttendanceCsv](../backend/src/main/java/tmc/attendance/application/AttendanceCsv.java). [VerificationPipelineTest](../backend/src/test/java/tmc/access/application/VerificationPipelineTest.java) kiểm tra nhóm lỗi token và policy. |
| **O · Open/Closed** | Access nhận danh sách `VerificationStep` có thứ tự. Cổng và chính sách eligibility được thay qua interface. | [VerifyAccessUseCase](../backend/src/main/java/tmc/access/application/VerifyAccessUseCase.java), [EligibilityPolicy](../backend/src/main/java/tmc/attendance/domain/EligibilityPolicy.java). Test `acceptsAnExtraStepAndReturnsInProgressWhileCommandIsPending` đăng ký bước mới qua constructor. |
| **L · Liskov Substitution** | Access dùng fake, simulator và HTTP skeleton qua cùng `GateCommandSender`. Outcome được biểu diễn bằng `GateResult` cho mọi adapter. | [GateCommandSenderContractTest](../backend/src/test/java/tmc/access/infrastructure/turnstile/GateCommandSenderContractTest.java) chạy với ba adapter. Test áp dụng outcome lên `AccessAttempt.withCommand` và xác nhận ACK chỉ tạo `ACK_ONLY`. HTTP skeleton trả FAILED cho đến khi có vendor contract. |
| **I · Interface Segregation** | Access tách gửi lệnh, đọc trạng thái và nhận chứng cứ. UserQr dùng reader, writer, signer theo tác vụ. Worker sync chỉ cần lấy và đánh dấu payload. | [Access ports](../backend/src/main/java/tmc/access/domain/port/), [CentralOutbox](../backend/src/main/java/tmc/shared/outbox/CentralOutbox.java), [CentralOutboxWorker](../backend/src/main/java/tmc/bootstrap/worker/CentralOutboxWorker.java). Contract test gửi lệnh chỉ cần `GateCommandSender`. |
| **D · Dependency Inversion** | Các use case Access, UserQr và Attendance nhận port qua constructor, gồm `ClockProvider` và `IdGenerator` ở những chỗ cần thời gian hoặc ID. Bootstrap ghép các implementation. | [AccessConfiguration](../backend/src/main/java/tmc/bootstrap/AccessConfiguration.java), [UserQrConfiguration](../backend/src/main/java/tmc/bootstrap/UserQrConfiguration.java), [AttendanceConfiguration](../backend/src/main/java/tmc/bootstrap/AttendanceConfiguration.java). [ArchitectureRulesTest](../backend/src/test/java/tmc/architecture/ArchitectureRulesTest.java) kiểm tra hướng phụ thuộc, vòng giữa các package cấp module, và ngăn Access, UserQr, Attendance import infrastructure của nhau. |

### Thay đổi trước và sau có trong repo

Các ví dụ dưới đây dựa trên source đã lưu trong Git. Chúng có thể dùng để mở code tương ứng khi bảo vệ.

| Nguyên lý | Trước | Sau |
| --- | --- | --- |
| S | [`JdbcAttemptStore` cũ][old-store] vừa điều khiển transaction vừa dựng tay từng key JSON của outbox trong `payload()`. | [`EdgeOutboxCodec`](../backend/src/main/java/tmc/access/infrastructure/persistence/EdgeOutboxCodec.java) đảm nhiệm format. Store gọi `outboxCodec.encode(attempt)` bên trong transaction. [Test tương thích payload cũ](../backend/src/test/java/tmc/access/infrastructure/persistence/EdgeOutboxCodecTest.java) so sánh cả chuỗi vì outbox dùng nội dung payload để xác nhận phiên bản. |
| O | [`AccessConfiguration` trước observability][old-wiring] truyền `gate` vào pipeline. | Bootstrap truyền `new MeteredGateCommandSender(gate, metrics)`. Bộ đếm được thêm qua decorator, `GateCommandStep` vẫn nhận cùng port. Đây là một lần mở rộng thực tế của thiết kế. |
| L | [Store JDBC trước refactor][old-store] khai báo `implements OutboxPublisher` nhưng `publish(…)` ném `UnsupportedOperationException`. | JDBC bỏ khai báo port không thực hiện được. Việc ghi outbox là phần của hợp đồng `AttemptStore.persistPrepared`, cùng transaction với attempt và JTI. Contract Turnstile kiểm tra quy tắc thay thế ở đường điều khiển cổng. |
| I | [Worker Central cũ][old-worker] nhận toàn bộ `JdbcAttemptStore` để gọi đúng hai phương thức. | Worker nhận `CentralOutbox` với `pendingCentral(limit)` và `centralDone(payload)`. Port phản ánh đúng nhu cầu của phía gửi. |
| D | [Receiver cũ][old-sync] nằm trong `tmc.shared.outbox` và import `AccessAttempt`. Access cũng import clock và audit từ `shared`, sinh phụ thuộc hai chiều. | Receiver nằm trong `tmc.sync`. `tmc.shared.outbox` giữ DTO `EdgeSyncEvent` và port thuần Java. Test ArchUnit kiểm tra vòng trực tiếp trên các package hiện có. |

Những ranh giới này cho phép test Access với fake Turnstile và thay rule eligibility mà không cần thay API. Một trade-off là store JDBC vẫn giữ nhiều thao tác cùng nhau để bảo đảm transaction nguyên tử. Việc tách phần codec và port đọc outbox giảm trách nhiệm của store mà vẫn giữ ranh giới commit rõ ràng.

## So với yêu cầu triển khai Phase 2

| Hạng mục | Đã có trong repo | Giới hạn hoặc sai lệch |
| --- | --- | --- |
| Task 2 · nghiệp vụ chính | Access quyết định tại Edge, ghi trước khi phát lệnh. UserQr cấp/thu hồi, Attendance nhận event và export demo. | Quản lý trạm đầy đủ, CRUD nhân viên, quản trị role/config và override offline hai người thuộc phạm vi thiết kế hoặc chưa hiện thực. |
| Pipeline và trạng thái | Các bước xác thực, chữ ký/thời gian, credential, policy, replay, anti-passback, transaction, lệnh và outcome. Ba trạng thái được giữ riêng. | Scope của trạm được kiểm tra trước khi trả một kết quả idempotent. Idempotency nằm trong use case. Passage là endpoint riêng và không nằm trên đường gửi lệnh. |
| Port và contract | Wiring ở bootstrap. Attendance nhận `AttendanceEvent` và ghi bảng riêng. Edge và Central dùng `EdgeSyncEvent`. | UserQr dùng các port credential/token của Access và Attendance dùng policy/scope ports của Access. Sync ánh xạ DTO sang `AccessAttempt` ở bộ nhận. Ranh giới giữa các module chưa đạt quy tắc tuyệt đối chỉ import từ package `contract`. |
| Runtime và tích hợp | Java 21, H2 JDBC transactions, outbox retry, ES256 và cấu hình snapshot. Domain và application không dùng Spring, JPA hay Jackson. | Adapter lưu trữ dùng Spring JDBC ngoài hai lớp API/bootstrap. Central receiver giữ dữ liệu trong bộ nhớ. Fake Turnstile, xác thực và ACK của HR là mô phỏng. Device-signed offline QR chỉ được mô tả trong ADR. |
| Mức bao phủ test | Unit, contract, HTTP với H2, fault injection cho outbox và ArchUnit. | Còn thiếu test riêng cho một số nhánh xác minh như sai `kid`, sai credential version và tổ hợp scope. Load harness dùng Node.js, khác ví dụ JUnit/k6 trong yêu cầu triển khai. |

## Đối chiếu AC trong Phần I

| AC | Bằng chứng đang có | Việc còn thiếu để nghiệm thu |
| --- | --- | --- |
| AC-01 · QR và từ chối | `VerificationPipelineTest` kiểm tra thời hạn, skew, issuer/audience và snapshot/policy. `EdgeFlowTest` kiểm tra allow, replay, anti-passback và tình huống lỗi lưu không phát lệnh. | Ma trận QR/credential đầy đủ với dữ liệu và policy TMC duyệt. |
| AC-02 · lệnh cổng và passage | `GateCommandSenderContractTest`, `AccessAttemptTest` và `EdgeFlowTest` kiểm tra outcome riêng với passage. | Kiểm thử theo API và nguồn cảm biến thực. |
| AC-03 · Attendance Transaction | `EdgeFlowTest` kiểm tra ghi bản ghi và cờ HR review. `AttendanceEligibilityTest` kiểm tra ACKED-only. | Test từng trường employee, site, time, direction và evidence theo schema được duyệt. |
| AC-04 · độ bền dữ liệu | `EdgeFlowTest` ép lỗi insert outbox, kiểm tra JTI rollback và không phát lệnh. | Khởi động lại Edge/Central và đối soát số liệu theo định nghĩa normal operation. |
| AC-05 · idempotency | `EdgeFlowTest` kiểm tra replay kết quả cũ và hai request đồng thời dùng một JTI. `VerificationPipelineTest` kiểm tra trạng thái in-progress và không gửi lại lệnh sau timeout. | Kiểm thử thêm HTTP 409 khi duplicate đang xử lý và quy tắc retry trên thiết bị thật. |
| AC-06 · thời gian kiosk | [Đo tải local](../backend/README.md) thực hiện 800 scan/site với hai site đồng thời trong 30 giây, chậm nhất 74,55 ms ở ranh giới HTTP Edge. | Chạy kịch bản chính thức 30 phút tại từng site và đo tới kiosk với LAN/Turnstile thật. |
| AC-07 · gửi HR trước 12:00 ngày 1 | `EdgeFlowTest` dùng clock cố định, xác nhận batch tháng chỉ tạo một lần và lưu ACK mô phỏng trước trưa. | Schema, calendar, delivery ACK và đối soát với HR/Payroll thật. |
| AC-08 · RBAC | `EdgeFlowTest` kiểm tra yêu cầu token, Admin bị từ chối đọc attendance, scope Employee và quyền export của HR. | Ma trận role/scope TMC phê duyệt và test quản trị role/config. |
| AC-09 · audit | `EdgeFlowTest` đọc và tính lại hash audit, phát hiện khi đổi một trường. Có audit cho allow/deny, QR, correction và các thao tác attendance đã tích hợp. | Event catalog, quyền tra cứu, chống xóa và chính sách retention đầy đủ. |
| AC-10 · dữ liệu cá nhân | Test HTTP kiểm tra CSV không chứa QR gốc và log verify không chứa QR/token/dữ liệu nhân viên đưa vào test. | Rà dữ liệu QR/report/export và retention theo policy TMC/pháp lý. |

## Tài liệu đối chiếu

- Đề `SA_CC_hk261_Assignment_M2.pdf`, trang 2–3, phần scope, Task 1 và Task 2.
- `Phần I (fixed).docx`, mục 1.5.6, bảng Acceptance Criteria và Requirements Traceability Matrix.
- Yêu cầu Phase 2 đã thống nhất, mục 1–6 và 8, cùng slide `Chapter2.2_SA_CC.pdf` về LSP, ISP và DIP.
- [Kiến trúc và ADR Phase 0–1](../Phase1_Architecture.md) · [Kiến trúc của code đang chạy](architecture.md)

[old-store]: https://github.com/lazy128/tmc-qr-attendance/blob/9563ddf618ccb3f30733189c6abf701d17a14058/backend/src/main/java/tmc/access/infrastructure/JdbcAttemptStore.java
[old-wiring]: https://github.com/lazy128/tmc-qr-attendance/blob/f49977eea377ec33298eb687ca38aa3a0dae8fe1/backend/src/main/java/tmc/bootstrap/AccessConfiguration.java
[old-worker]: https://github.com/lazy128/tmc-qr-attendance/blob/9563ddf618ccb3f30733189c6abf701d17a14058/backend/src/main/java/tmc/bootstrap/CentralOutboxWorker.java
[old-sync]: https://github.com/lazy128/tmc-qr-attendance/blob/9563ddf618ccb3f30733189c6abf701d17a14058/backend/src/main/java/tmc/shared/outbox/CentralSyncReceiver.java
