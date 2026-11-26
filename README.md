# TMC QR Attendance & Access Control

[![CI](https://github.com/lazy128/tmc-qr-attendance/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/lazy128/tmc-qr-attendance/actions/workflows/ci.yml)

Ứng dụng mẫu cho Assignment M2. **Access** là module chính của Task 2. Demo cho phép cấp QR ngắn hạn, kiểm tra truy cập tại Edge, điều khiển cổng mô phỏng, ghi nhận công và tải CSV theo tháng.

[Cách chạy](#chạy-demo) · [Ba view kiến trúc](#architecture-views) · [SOLID](#solid) · [Reflection](#reflection) · [Đối chiếu M2](#m2-checklist)

## Chạy demo

### Demo nghiệp vụ nhân viên, cổng và HR

Cần **Java 21** và **Node.js 24**.

Chạy profile `workflow` với H2 riêng tại `backend/data/tmc-workflow`. Profile tạo sáu nhân viên hư cấu ở Bình Dương và Long An, thiết bị đã đăng ký và dữ liệu mẫu có ca đủ giờ, ca qua đêm, thiếu giờ ra và lượt trùng. Chỉ bật profile trên dữ liệu demo vì giao diện cho chọn tài khoản và vai trò mẫu.

```powershell
# Terminal backend
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=workflow"
```

```powershell
# Terminal frontend
cd frontend
npm ci
$env:VITE_EDGE_API_URL = 'http://127.0.0.1:8084'
npm run dev -- --strictPort
```

- `/employee` hiển thị QR ngay với tài khoản đầu tiên. Đổi nhân viên trong danh sách và đưa mã vào camera kiosk.
- `/gate` lấy tên, hướng và nhóm được vào từ registry. Dùng **Mở camera** hoặc **Chọn ảnh QR**. Nhập token thủ công nằm trong **Chi tiết kỹ thuật**. Cổng vào và cổng ra ghi nhận theo hướng cấu hình.
- `/attendance` cho nhân viên xem dữ liệu của mình, quản lý xem nhóm sản xuất Bình Dương và HR xem toàn bộ hồ sơ mẫu. HR bổ sung giờ ra đã qua kèm lý do, tải CSV tháng hoặc XLSX. CSV bàn giao giờ công cho bộ phận Payroll.
- `/stations` dành cho Admin quản lý trạm và bảo vệ xem trạm được giao. Trạm `DISABLED` bị từ chối xác minh.

Để thử đồng thời hai nhà máy, đóng gói JAR rồi chạy `backend/scripts/start-multisite.ps1 -Workflow`. Trong terminal frontend đặt `VITE_EDGE_API_URL=http://127.0.0.1:8090` để cấp QR và xem bảng công hợp nhất trên Central, và đặt `VITE_EDGES` như sau để kiosk gọi đúng Edge. Hai Edge chia sẻ khóa ký demo. Outbox gửi sự kiện chấm công bằng bearer dành riêng cho đồng bộ. Có thể thêm `-IsolatedData` để dùng H2 trong bộ nhớ.

```powershell
$env:VITE_EDGES = '[{"site":"SITE-BD","station":"BD-G01","url":"http://127.0.0.1:8081"},{"site":"SITE-LA","station":"LA-G01","url":"http://127.0.0.1:8082"}]'
```

### Tổng hợp công và bảng tính

Các lượt quét gốc trong DB là nguồn dữ liệu chính. Sau khi outbox ghi nhận lượt quét hợp lệ hoặc bất thường tại cổng chấm công, projection tính lại theo khóa nhân viên và ngày công. Phiên ghép theo thứ tự, cộng các cặp vào–ra hợp lệ và loại thời gian nghỉ giữa cặp. Mặc định ngày công bắt đầu lúc **06:00**, theo `Asia/Ho_Chi_Minh`. Lượt ra trước 06:00 thuộc ngày hôm trước, kể cả qua tháng. Đặt `tmc.timesheet.day-start` để đổi mốc. Mốc này là giả định chờ HR duyệt.

XLSX là bản sao dẫn xuất. Worker ghi tệp `timesheet-YYYY-MM.xlsx` vào `backend/exports/workflow` trong profile đơn, gồm **Tổng hợp**, **Chi tiết ngày** và **Cần HR xem**. File được ghi tạm trước khi thay thế. Tần suất gộp mặc định 5 giây, thời gian chờ thử lại 30 giây và lịch chốt tháng trước mặc định 11:30 ngày 1. Các tham số lần lượt là `tmc.timesheet.publish-delay-ms`, `tmc.timesheet.retry-seconds` và `tmc.timesheet.close-cron`. API tải file kiểm tra quyền HR và ghi audit. File chỉ chứa dữ liệu công đã tổng hợp.

Google Sheets là adapter khung, mặc định tắt. Các thuộc tính dự kiến là `tmc.timesheet.google.spreadsheet-id` và `tmc.timesheet.google.service-account`. Cần TMC và pháp chế phê duyệt trước khi phát triển và bật đường truyền dữ liệu ra dịch vụ bên thứ ba.

### Quyền qua cổng và giả định

| Khu vực | Nhóm được vào |
| --- | --- |
| Cổng chấm công vào/ra | Nhân viên, quản lý, lãnh đạo |
| Khu sản xuất | Nhân viên, quản lý |
| Văn phòng quản lý | Quản lý, lãnh đạo |
| Khu lãnh đạo | Lãnh đạo |

Cổng khu vực ghi AccessAttempt và audit. Quyền theo nhóm khai báo tường minh, đúng site, đúng hướng và đúng khung giờ đã cấu hình. `tmc.access.shift-grace-minutes` mặc định 10 phút. Admin cấu hình trường `gate` trên `POST/PATCH /admin/v1/stations` với `name`, `type`, `allowedGroups`, `directions` và cặp giờ tùy chọn `opensAt` / `closesAt`. Bật `tmc.access.enforce-employee-shift=true` để áp dụng thêm lịch ca ngày và ca đêm của nhân viên, với các mốc `tmc.access.shift.standard-open`, `standard-close`, `night-open`, `night-close`. Công tắc mặc định tắt cho demo. Snapshot ngoài file khai báo `employees` theo credential ID. Trong demo, dữ liệu mẫu và cơ sở dữ liệu local đóng vai trò snapshot tin cậy. Nhóm quyền và tên nhân viên nằm ở Edge. Ma trận trên cần TMC duyệt, và việc miễn chấm công cho lãnh đạo do HR quyết định.

### Điện thoại hiển thị QR qua LAN

Ví dụ IP LAN của laptop là `192.168.1.25`. Chạy backend profile `workflow` ở cổng 8084. Dùng cùng Wi-Fi và thay IP trong các lệnh dưới đây bằng IP laptop. Dùng dữ liệu mẫu trên mạng tin cậy vì HTTP truyền mã demo dưới dạng rõ.

- Trong terminal backend, trước khi chạy, đặt `$env:SERVER_ADDRESS = '0.0.0.0'` và `$env:TMC_CORS_ORIGINS = 'http://localhost:5173,http://192.168.1.25:5173'`.
- Trong terminal frontend, đặt `$env:VITE_EDGE_API_URL = 'http://192.168.1.25:8084'` và chạy `npm run dev -- --host 0.0.0.0 --strictPort`. Nếu firewall chặn, cho phép TCP 5173 và 8084 trên mạng riêng.
- Trên điện thoại mở `http://192.168.1.25:5173/employee`. Trên laptop mở `http://localhost:5173/gate` để trình duyệt cấp quyền camera. Hai trang dùng cùng Edge.

### Điện thoại làm máy quét qua HTTPS

Với **mkcert**, giữ backend ở loopback và đặt `TMC_CORS_ORIGINS=https://localhost:5173,https://192.168.1.25:5173` trước khi chạy backend. Trong `frontend/`, dùng chứng chỉ chứa IP LAN của laptop và cho điện thoại tin cậy `rootCA.pem` từ thư mục `mkcert -CAROOT`. Chỉ chuyển chứng chỉ CA công khai sang điện thoại. [Hướng dẫn mkcert cho thiết bị di động](https://github.com/FiloSottile/mkcert#mobile-devices)

```powershell
mkcert -install
mkcert -cert-file dev-cert.pem -key-file dev-key.pem localhost 127.0.0.1 192.168.1.25
$env:VITE_EDGE_API_URL = '/edge-api'
$env:TMC_DEV_PROXY_TARGET = 'http://127.0.0.1:8084'
$env:TMC_DEV_HTTPS_CERT = (Resolve-Path dev-cert.pem).Path
$env:TMC_DEV_HTTPS_KEY = (Resolve-Path dev-key.pem).Path
npm run dev -- --host 0.0.0.0 --strictPort
```

Mở `https://192.168.1.25:5173/gate` trên điện thoại. Trình duyệt gọi API qua proxy cùng origin `/edge-api`.

Với **cloudflared**, dùng cùng proxy `/edge-api` và giữ Vite ở HTTP loopback. Tạo ứng dụng Cloudflare Access giới hạn người dùng demo, sau đó trỏ hostname đã bảo vệ đến `http://127.0.0.1:5173` qua tunnel. Đặt `__VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS` bằng hostname đó cho Vite và `TMC_CORS_ORIGINS` bằng origin HTTPS cho Edge. Mở `https://<hostname>/gate` sau khi đăng nhập Access. [Cloudflare Tunnel](https://developers.cloudflare.com/tunnel/get-started/) · [Bảo vệ ứng dụng bằng Cloudflare Access](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/)

### Demo hai nhà máy

Trong cùng terminal backend đã đặt các mã demo, đóng gói JAR và chạy ba tiến trình. Script dùng khóa ES256 demo chung cho BD và LA để cùng token có thể được xác minh tại cả hai Edge.

```powershell
cd backend
.\mvnw.cmd -DskipTests package
.\scripts\start-multisite.ps1
```

| Tiến trình | Địa chỉ | Dữ liệu |
| --- | --- | --- |
| `bd` | `localhost:8081` · `SITE-BD / BD-G01` | `data/tmc-edge-bd` |
| `la` | `localhost:8082` · `SITE-LA / LA-G01` | `data/tmc-edge-la` |
| Central mô phỏng | `localhost:8090` | Bộ nhớ tiến trình |

Để kiểm thử replay trên ba tiến trình, chạy `.\scripts\start-multisite.ps1 -Check` từ `backend/` khi ba cổng đang trống. Script xác minh cùng token tại hai Edge và đợi Central ghi nhận `SUSPICIOUS`. Dùng `.\scripts\start-multisite.ps1 -Workflow -Check` để kiểm tra đồng bộ bảng công và phân quyền cổng của hai nhà máy.

### Kịch bản demo

Để giữ nguyên một token cho các kịch bản nhập tay trong kiosk, lấy mã từ API của profile nghiệp vụ.

```powershell
$edge = 'http://127.0.0.1:8084'
$issued = Invoke-RestMethod -Method Post -Uri "$edge/demo/v1/employees/NV001/qr"
$issued.token
```

| Kịch bản | Thao tác và kết quả |
| --- | --- |
| Hợp lệ | Quét mã mới ở đúng site, đúng chiều. Kiosk hiện thông báo đã ghi nhận giờ vào hoặc ra. |
| Hết hạn | Giữ token API chưa dùng, đợi quá TTL và dung sai đồng hồ khoảng 5 giây. Nhập lại ở kiosk để nhận `TOKEN_EXPIRED`. |
| Replay | Dùng lại cùng token tại cùng site trước khi hết hạn. Kiosk gửi event ID mới và nhận `REPLAY`. |
| Thu hồi | Dùng DB kiểm thử và gọi `POST /qr/v1/credentials/{id}/revoke` bằng bearer HR. Kiosk từ chối token còn hạn và trang nhân viên hiện **Đã thu hồi** ở lần làm mới kế tiếp. |
| Sai thiết bị | Gọi API cấp token với mã đăng ký của thiết bị khác để nhận HTTP 403 với `DEVICE_MISMATCH`. Xem [hợp đồng API backend](backend/README.md#luồng-và-api-demo). |
| Hai site | Chạy script hai site. Kết quả mong đợi là `BD ALLOW, LA ALLOW, Central SUSPICIOUS`. |

### Deviations

- Phase 1 giao Central cấp token và Edge xác minh. Demo ký và cấp token ngay trên Edge. Hai profile dùng chung khóa ký demo. Registry, credential, thu hồi và đăng ký thiết bị lưu cục bộ từng Edge.
- Bearer cấu hình và mã đăng ký ngẫu nhiên là cơ chế demo. Edge lưu hash của mã đăng ký. Cơ chế này chưa tích hợp OIDC, khóa thiết bị không thể xuất hoặc xác thực trạm bằng mTLS.
- Turnstile, cảm biến mặc định, ACK HR và Central receiver vẫn ở mức mô phỏng. Central lưu trạng thái đối soát trong bộ nhớ. Tín hiệu `SUSPICIOUS` ở Central không tự chỉnh bản ghi chấm công đã lưu tại Edge. Registry để trống heartbeat khi chưa có thiết bị gửi dữ liệu.
- Sinh trắc mặc định tắt. Có port và fake để kiểm thử ba kết quả. [ADR xác minh khuôn mặt](docs/adr/face-verification.md)

## Chạy kiểm thử

| Phần | Thư mục | Lệnh |
| --- | --- | --- |
| Backend | `backend/` | `.\mvnw.cmd test` trên Windows hoặc `./mvnw test` trên macOS/Linux. Dùng `verify` để chạy thêm bước đóng gói JAR. |
| Frontend | `frontend/` | `npm ci`, `npm run typecheck`, `npm test`, `npm run build`. |
| Luồng trên trình duyệt | `frontend/` | Bật profile `workflow` và frontend, chạy `npx playwright install chromium` rồi `npm run test:e2e`. Có thể đặt `PLAYWRIGHT_CHROME_PATH` tới Chrome đã cài và `E2E_WORKFLOW_SCREENSHOTS` tới thư mục nhận ba ảnh. Dùng DB demo mới có dữ liệu mẫu của tháng đang xem. |
| GitHub Actions | `.github/workflows/ci.yml` | Ubuntu chạy `mvn -B test` với Java 21 và chạy `npm ci`, `npm run typecheck`, `npm test` với Node.js 24 khi push hoặc mở pull request. |

## Cách tổ chức repo

```text
backend/                 Java, Spring Boot, H2 và test
frontend/                React, TanStack Start và test
Phase1_Architecture.md   Thiết kế tổng thể, ADR và truy vết Phase 0–1
.github/workflows/        CI backend và frontend
```

[Cấu hình, API, Docker và metrics backend](backend/README.md) · [Cấu trúc và cấu hình frontend](frontend/README.md)

## Architecture Views

Ba hình dưới đây mô tả **bản demo Phase 2 đang chạy**, theo Chương 6 của bài giảng. [Thiết kế Phase 0–1](Phase1_Architecture.md) dùng cùng ba loại view để trình bày phương án cho hai nhà máy. Mỗi view có hình chính, chú giải, catalog và thông tin về các biến thể.

| View | Thành phần trên hình | Quan hệ được biểu diễn |
| --- | --- | --- |
| Module, slide 15–17 và 27–30 | Package hoặc đơn vị triển khai mã nguồn | Bao chứa `is-part-of` và phụ thuộc tĩnh `depends-on` |
| Component-and-Connector, slide 36–43 và 45–51 | Instance lúc chạy, port và connector | Port của component gắn vào một vai trò của connector |
| Allocation, deployment style, slide 52–56 và 62–64 | Máy, môi trường thực thi và artifact | Artifact đặt trên node, communication association nối các node |

### Module View

**Hình MV-1 · Decomposition and uses.** Hình chỉ xét các package Java của backend. Khung lồng nhau là `is-part-of`. Mũi tên đứt đi từ package **dùng** sang package **được dùng** trong mã nguồn.

```mermaid
flowchart LR
  subgraph Backend["«system» Backend Edge"]
    Boot["«package» tmc.bootstrap"]
    Qr["«package» tmc.userqr"]
    Attendance["«package» tmc.attendance"]
    Sync["«package» tmc.sync"]
    Access["«package» tmc.access"]
    Shared["«package» tmc.shared"]
    Boot -.-> Qr
    Boot -.-> Attendance
    Boot -.-> Sync
    Boot -.-> Access
    Boot -.-> Shared
    Qr -.-> Access
    Qr -.-> Shared
    Attendance -.-> Access
    Attendance -.-> Shared
    Sync -.-> Access
    Sync -.-> Shared
    Access -.-> Shared
  end
```

| Module | Trách nhiệm và interface |
| --- | --- |
| [`tmc.access`](backend/src/main/java/tmc/access/) | Sở hữu `AccessAttempt`, used JTI, idempotency, presence và Edge outbox. Application dùng các port như `TokenValidator`, `AttemptStore` và `GateCommandSender`. |
| [`tmc.userqr`](backend/src/main/java/tmc/userqr/) | Cấp QR ES256 và thu hồi credential qua các port credential/token của Access. |
| [`tmc.attendance`](backend/src/main/java/tmc/attendance/) | Nhận `AttendanceEvent` bằng `AttendanceRecorder`. Quản lý bảng `attendance_record`, eligibility, tra cứu và CSV. API dùng permission/scope ports từ Access. |
| [`tmc.sync`](backend/src/main/java/tmc/sync/) | Nhận `EdgeSyncEvent` từ `shared`, chuyển sang kiểu domain Access để chống trùng và phát hiện cùng JTI ở nhiều site. |
| [`tmc.shared`](backend/src/main/java/tmc/shared/) | Clock, ID, audit, correlation ID, DTO `EdgeSyncEvent` và port `CentralOutbox`. |
| [`tmc.bootstrap`](backend/src/main/java/tmc/bootstrap/) | Ghép Spring beans, worker và mapping từ `AccessAttempt` sang `AttendanceEvent`. |

<details>
<summary>Cây package và vị trí tìm code</summary>

```text
backend/src/main/java/tmc/
├── access/
│   ├── api/                  REST verify, passage và correction
│   ├── application/          Use case, pipeline, step và policy/scope ports
│   ├── domain/               Aggregate, trạng thái, value object và ports
│   └── infrastructure/
│       ├── persistence/      JDBC, in-memory stores và codec outbox
│       ├── security/         ES256, xác thực trạm và quyền demo
│       ├── snapshot/         Snapshot dạng file và in-memory
│       └── turnstile/        Fake, simulator, HTTP skeleton, timeout và metrics
├── userqr/                   API và use case cấp/thu hồi QR
├── attendance/               API, application, domain, contract và stores
├── sync/                     Central API và bộ nhận demo
├── shared/                   Audit, clock, ID, outbox contract và filter HTTP
└── bootstrap/
    ├── mapping/              Chuyển AccessAttempt thành AttendanceEvent
    └── worker/               Attendance, Central sync, đối soát, snapshot và HR
```

Frontend chia theo `access`, `userqr`, `attendance` và `shared`. [Cây frontend chi tiết](frontend/README.md)

</details>

**Biến thể và lý do.** Module phân lớp với port và adapter để đổi Turnstile hoặc strategy chấm công ở bootstrap. Attendance ghi bảng riêng qua event contract. [ArchUnit](backend/src/test/java/tmc/architecture/ArchitectureRulesTest.java) kiểm tra vòng phụ thuộc và ngăn domain/application import framework hoặc infrastructure. Các phụ thuộc sang Access ở trên phản ánh code hiện tại, gồm những chỗ chưa tách hoàn toàn qua package `contract`.

### Component-and-Connector View

**Hình CC-1 · Các instance trên đường quét và đồng bộ.** Hai phần dùng cùng instance `local`. Khung ghi `instance : type`. Ô nhỏ là **port**. Mỗi connector nối hai port và có mã để tra hợp đồng trong bảng. Ký hiệu box-and-line theo slide 43.

**CC-1a · Đường quét**

```mermaid
flowchart LR
  subgraph S["station : Console"]
    sv["verifyClient"]
  end
  subgraph E["edge : Edge API"]
    ev["verifyApi"]
    ej["storeClient"]
    eg["gateClient"]
    ev ~~~ ej
    ev ~~~ eg
  end
  subgraph G["gate : Fake"]
    gc["commandSink"]
  end
  subgraph D["local : H2"]
    dj["jdbc"]
  end
  sv -->|C1| ev
  ej ==>|C2| dj
  eg -->|C3| gc
  classDef port fill:#fff,stroke:#416785,color:#172a3a,font-size:13px;
  class sv,ev,ej,eg,gc,dj port;
```

**CC-1b · Xử lý sau lượt quét**

```mermaid
flowchart LR
  subgraph D["local : H2"]
    out["outbox"]
    dj["jdbc"]
  end
  subgraph A["attJob : Worker"]
    ar["reader"]
    ap["eventSender"]
    ar ~~~ ap
  end
  subgraph T["att : Consumer"]
    ti["eventSink"]
    tj["recordStore"]
  end
  subgraph Y["syncJob : Worker"]
    yr["reader"]
    yp["syncClient"]
    yr ~~~ yp
  end
  subgraph C["central : Receiver"]
    ci["syncApi"]
  end
  out -.->|C4| ar
  ap -->|C5| ti
  dj ===|C6| tj
  out -.->|C7| yr
  yp -->|C8| ci
  classDef port fill:#fff,stroke:#416785,color:#172a3a,font-size:13px;
  class dj,out,ar,ap,ti,tj,yr,yp,ci port;
```

| Instance | Vai trò lúc chạy |
| --- | --- |
| `station`, `edge` | Console gửi scan. Edge xác minh, lưu quyết định và điều khiển cổng. |
| `gate`, `local` | Fake Turnstile trả outcome. H2 lưu trạng thái Access, outbox và attendance. |
| `attJob`, `att` | Worker poll kết quả ACKED. Consumer áp dụng eligibility và ghi bản ghi. |
| `syncJob`, `central` | Worker tùy chọn gửi batch. Receiver demo xử lý và chống trùng trong bộ nhớ. |

| Connector | Kiểu và chiều | Vai trò, hợp đồng và hành vi |
| --- | --- | --- |
| C1 | Liền · HTTP request/response | Console gọi `POST /access/v1/verify`. Edge trả decision, command, passage và correlation ID. |
| C2 | Đậm · JDBC transaction | Edge ghi attempt, JTI, SENT, outbox và audit trước lệnh cổng. Giao dịch sau lưu outcome. |
| C3 | Liền · Gọi Java trong process | Edge qua `GateCommandSender`, dùng fake adapter trong demo. ACK xác nhận lệnh. |
| C4 / C7 | Đứt · Outbox, chiều **dữ liệu đi** | Worker chủ động poll H2. C4 nhận kết quả ACKED. C7 chạy khi bật gửi Central và có thể gửi lại. |
| C5 | Liền · Gọi Java trong process | Attendance worker chuyển `AttendanceEvent` cho consumer. |
| C6 | Đậm, không mũi tên · JDBC | Attendance là caller. Consumer áp dụng eligibility rồi ghi bảng riêng trong H2. |
| C8 | Liền · HTTP request/response trong worker | Worker POST batch tới `/edge/v1/sync`. Receiver chống trùng theo `eventId`. Việc gửi diễn ra ngoài request C1. |

Ở đường liền hoặc đậm có mũi tên, chiều đi từ bên **khởi tạo request/gọi hàm** sang bên đáp ứng. C&C ghi topology và các connector. Sequence bên dưới mô tả thứ tự của một lần quét.

<details>
<summary>Sequence minh họa một lần quét</summary>

```mermaid
sequenceDiagram
    participant S as Gate Station hoặc console demo
    participant E as Access tại Edge
    participant D as H2 cục bộ
    participant G as Turnstile
    participant A as Attendance worker
    participant T as Attendance
    participant C as Central worker
    participant R as Central receiver

    S->>E: POST /access/v1/verify
    E->>D: Kiểm tra reservation, JTI, credential và presence
    alt DENY
        E->>D: Ghi attempt hoặc audit từ chối
        E-->>S: DENY và reasonCode
    else ALLOW
        E->>D: Commit attempt, JTI, SENT, outbox và audit
        E->>G: Gửi lệnh theo commandId
        G-->>E: ACK, lỗi hoặc hết thời gian chờ
        E->>D: Lưu outcome và cập nhật presence khi ACKED
        E-->>S: Trả decision, command, passage
    end
    A->>D: Đọc outbox đã ACKED
    A->>T: Chuyển AttendanceEvent
    T->>D: Ghi AttendanceRecord
    opt Bật gửi Central
        C->>D: Đọc payload đang chờ
        C->>R: POST /edge/v1/sync
    end
```

</details>

**Biến thể và lý do.** H2 và fake adapter chạy cục bộ cho demo. Central worker bật bằng cấu hình. Outbox giữ gửi Central và ghi chấm công ngoài đường quét. Khi có cảm biến, Edge nhận passage qua endpoint riêng. API, health, metrics và HR job nằm trong [runbook backend](backend/README.md).

- **Scan và retry.** Edge xác thực trạm, kiểm tra scope rồi tra idempotency. Retry cùng `eventId` và cùng fingerprint có thể nhận `409 REQUEST_IN_PROGRESS` hoặc kết quả đã lưu. Một JTI được dùng một lần trong mỗi site. Central phát hiện dùng lại giữa site sau khi đồng bộ.
- **Giao dịch trước lệnh cổng.** Nếu transaction ghi `SENT` lỗi, Edge từ chối, JTI được rollback và lệnh chưa được gửi. Sau khi gửi, timeout tạo `UNKNOWN`. Worker truy vấn trạng thái theo ID thay cho việc gửi lại lệnh.
- **Ba trạng thái độc lập.** `decision` là kết quả kiểm tra quyền. `command` là trạng thái lệnh điều khiển. `passage` là trạng thái chứng cứ qua cổng. ACK tạo `ACK_ONLY` và cập nhật presence. Chứng cứ riêng mới tạo `CONFIRMED`. `NOT_AVAILABLE` nghĩa là chưa cấu hình nguồn cảm biến, còn `UNCONFIRMED` nghĩa là đang chờ chứng cứ.
- **Chấm công.** Worker chỉ đưa ACKED vào attendance. `ACK_ONLY` bật cờ rà soát HR. UNKNOWN ở luồng đối soát; FAILED bị loại khỏi xuất công. Guard demo có thể sửa quên quét ra với audit mà không sinh scan chấm công mới.
- **Mất WAN.** Đường verify dùng dữ liệu cục bộ. Khi chạy với snapshot, Edge fail-closed nếu snapshot stale. Thu hồi trong cửa sổ staleness vẫn có thể chưa được Edge biết. Báo động JTI khác site chỉ xuất hiện sau sync.

### Allocation View

**Hình AL-1 · Deployment style của demo.** Khung ngoài là thiết bị. `«env»` là môi trường thực thi. Artifact đặt trong khung nơi nó được chạy hoặc lưu. Đường liên kết thể hiện mạng loopback hoặc đường dẫn đến nơi lưu trữ trên cùng host.

```mermaid
flowchart LR
  subgraph H["«device» Máy chạy demo"]
    subgraph FE["«env» Node.js 24"]
      V["«artifact» Vite dev server :5173"]
    end
    subgraph B["«env» Trình duyệt"]
      UI["«artifact» React console"]
    end
    subgraph BE["«env» JVM 21 / Docker"]
      J["«artifact» qr-attendance-edge-1.0.0.jar :8080"]
    end
    subgraph Disk["«storage» Đĩa host"]
      F["«data» H2 tmc-edge.mv.db"]
    end
    B ---|loopback 5173| FE
    B ---|loopback 8080| BE
    BE ---|file path hoặc volume mount| Disk
  end
```

| Phần tử môi trường | Phần mềm được đặt ở đó | Biến thể và ràng buộc |
| --- | --- | --- |
| Máy demo và trình duyệt | React console và Node.js phục vụ Vite | Frontend mặc định nghe ở cổng 5173. Trình duyệt cần truy cập được Edge. |
| JVM hoặc container trên máy demo | Edge API, fake Turnstile, Attendance và các worker. Central receiver có thể cùng chạy trong JAR demo. | Chạy Maven Wrapper/JAR hoặc Docker Compose. Compose publish cổng 8080 trên loopback của host. |
| Đĩa host | File dữ liệu H2, outbox và attendance | Maven dùng `backend/data/`. Docker lưu vào named volume `edge-data`. |

**Liên hệ giữa ba view.** Code `tmc.access` ở MV-1 góp phần tạo instance `edge` và fake gate trong CC-1. Các instance chạy từ JAR đặt trong môi trường JVM/container ở AL-1. Thiết kế [Bình Dương, Long An và Central](Phase1_Architecture.md#6-allocation-view) thể hiện phân bố vật lý ở các site. Danh tính, Turnstile và gửi HR vẫn dùng mô phỏng trong demo.

## SOLID

**Phạm vi nộp Task 2 là Access.** Đề M2 trang 3 yêu cầu hiện thực ít nhất một module chính và áp dụng SOLID. Bản demo hiện thực luồng Access tại Edge, cùng UserQr, Attendance và Sync ở mức hỗ trợ luồng chính. Mức bao phủ **Task 2** và mức nghiệm thu **toàn hệ thống** được đánh giá riêng theo Phần I.

### Bằng chứng trong code

| Nguyên lý | Áp dụng ở đâu | Bằng chứng trong code và test |
| --- | --- | --- |
| **S · Single Responsibility** | Access chia logic xác minh theo từng bước. Attendance tách áp dụng policy khỏi định dạng CSV. | Có test riêng cho từng bước xác minh. [Các VerificationStep](backend/src/main/java/tmc/access/application/step/) · [Test từng bước](backend/src/test/java/tmc/access/application/step/) · [RecordAttendance](backend/src/main/java/tmc/attendance/application/RecordAttendance.java) · [AttendanceCsv](backend/src/main/java/tmc/attendance/application/AttendanceCsv.java) |
| **O · Open/Closed** | Access nhận danh sách `VerificationStep` có thứ tự. Cổng và chính sách eligibility được thay qua interface. | [VerifyAccessUseCase](backend/src/main/java/tmc/access/application/VerifyAccessUseCase.java), [EligibilityPolicy](backend/src/main/java/tmc/attendance/domain/EligibilityPolicy.java). Test `acceptsAnExtraStepAndReturnsInProgressWhileCommandIsPending` đăng ký bước mới qua constructor. |
| **L · Liskov Substitution** | Access dùng fake, simulator và HTTP skeleton qua cùng `GateCommandSender`. Outcome được biểu diễn bằng `GateResult` cho mọi adapter. | [GateCommandSenderContractTest](backend/src/test/java/tmc/access/infrastructure/turnstile/GateCommandSenderContractTest.java) chạy với ba adapter. Test áp dụng outcome lên `AccessAttempt.withCommand` và xác nhận ACK chỉ tạo `ACK_ONLY`. HTTP skeleton trả FAILED cho đến khi có vendor contract. |
| **I · Interface Segregation** | Access tách gửi lệnh, đọc trạng thái và nhận chứng cứ. UserQr dùng reader, writer, signer theo tác vụ. Worker sync chỉ cần lấy và đánh dấu payload. | [Access ports](backend/src/main/java/tmc/access/domain/port/), [CentralOutbox](backend/src/main/java/tmc/shared/outbox/CentralOutbox.java), [CentralOutboxWorker](backend/src/main/java/tmc/bootstrap/worker/CentralOutboxWorker.java). Contract test gửi lệnh chỉ cần `GateCommandSender`. |
| **D · Dependency Inversion** | Các use case Access, UserQr và Attendance nhận port qua constructor, gồm `ClockProvider` và `IdGenerator` ở những chỗ cần thời gian hoặc ID. Bootstrap ghép các implementation. | [AccessConfiguration](backend/src/main/java/tmc/bootstrap/AccessConfiguration.java), [UserQrConfiguration](backend/src/main/java/tmc/bootstrap/UserQrConfiguration.java), [AttendanceConfiguration](backend/src/main/java/tmc/bootstrap/AttendanceConfiguration.java). [ArchitectureRulesTest](backend/src/test/java/tmc/architecture/ArchitectureRulesTest.java) kiểm tra hướng phụ thuộc, vòng giữa các package cấp module, và ngăn Access, UserQr, Attendance import infrastructure của nhau. |

### Thay đổi trước và sau có trong repo

Các ví dụ dưới đây dựa trên source đã lưu trong Git. Chúng có thể dùng để mở code tương ứng khi bảo vệ.

| Nguyên lý | Trước | Sau |
| --- | --- | --- |
| S | [`JdbcAttemptStore` cũ][old-store] vừa điều khiển transaction vừa dựng tay từng key JSON của outbox trong `payload()`. | [`EdgeOutboxCodec`](backend/src/main/java/tmc/access/infrastructure/persistence/EdgeOutboxCodec.java) đảm nhiệm format. Store gọi `outboxCodec.encode(attempt)` bên trong transaction. [Test tương thích payload cũ](backend/src/test/java/tmc/access/infrastructure/persistence/EdgeOutboxCodecTest.java) so sánh cả chuỗi vì outbox dùng nội dung payload để xác nhận phiên bản. |
| O | [`AccessConfiguration` trước observability][old-wiring] truyền `gate` vào pipeline. | Bootstrap truyền `new MeteredGateCommandSender(gate, metrics)`. Bộ đếm được thêm qua decorator, `GateCommandStep` vẫn nhận cùng port. Đây là một lần mở rộng thực tế của thiết kế. |
| L | [Store JDBC trước refactor][old-store] khai báo `implements OutboxPublisher` nhưng `publish(…)` ném `UnsupportedOperationException`. | JDBC bỏ khai báo port không thực hiện được. Việc ghi outbox là phần của hợp đồng `AttemptStore.persistPrepared`, cùng transaction với attempt và JTI. Contract Turnstile kiểm tra quy tắc thay thế ở đường điều khiển cổng. |
| I | [Worker Central cũ][old-worker] nhận toàn bộ `JdbcAttemptStore` để gọi đúng hai phương thức. | Worker nhận `CentralOutbox` với `pendingCentral(limit)` và `centralDone(payload)`. Port phản ánh đúng nhu cầu của phía gửi. |
| D | [Receiver cũ][old-sync] nằm trong `tmc.shared.outbox` và import `AccessAttempt`. Access cũng import clock và audit từ `shared`, sinh phụ thuộc hai chiều. | Receiver nằm trong `tmc.sync`. `tmc.shared.outbox` giữ DTO `EdgeSyncEvent` và port thuần Java. Test ArchUnit kiểm tra vòng trực tiếp trên các package hiện có. |

Những ranh giới này cho phép test Access với fake Turnstile và thay rule eligibility mà không cần thay API. Một trade-off là store JDBC vẫn giữ nhiều thao tác cùng nhau để bảo đảm transaction nguyên tử. Việc tách phần codec và port đọc outbox giảm trách nhiệm của store mà vẫn giữ ranh giới commit rõ ràng.

## Reflection

1. **OCP và thứ tự xác minh.** Có thể đăng ký thêm `VerificationStep` qua constructor, nhưng mốc `step.order() >= 30` ngầm quyết định bước chạy trước hay sau idempotency. Nhóm bước trước mốc bảo đảm trạm được xác thực và đúng site trước khi Edge trả kết quả đã lưu. Một phase được khai báo rõ khi đăng ký step sẽ giúp kiểm tra ràng buộc này khi thêm bước. [VerifyAccessUseCase](backend/src/main/java/tmc/access/application/VerifyAccessUseCase.java) · [VerificationPipelineTest](backend/src/test/java/tmc/access/application/VerificationPipelineTest.java)
2. **SRP và trạng thái dùng chung.** `VerifyAccessUseCase` vừa điều phối pipeline, vừa giữ chỗ idempotency, tạo và lưu bản ghi từ chối, rồi xử lý trường hợp lỗi sau khi đã gửi lệnh. Các field public trong `AccessContext` khiến quan hệ giữa những bước này chỉ thể hiện qua thứ tự thực thi. Ví dụ, `GateCommandStep` dựa vào `persisted` và `attempt` do bước lưu thiết lập. Một phương thức ghi nhận attempt đã lưu có thể cập nhật cả hai cùng lúc và giữ điều kiện đó ngay trong context. [AccessContext](backend/src/main/java/tmc/access/application/AccessContext.java) · [GateCommandStepTest](backend/src/test/java/tmc/access/application/step/GateCommandStepTest.java)
3. **Bằng chứng ở cấp step.** Test token trước đây dùng claim giả lập. Test riêng hiện ký và xác minh ES256 bằng khóa test, kiểm tra riêng sai `kid`, sai chữ ký và các biên skew. Ma trận credential, scope và anti-passback xác nhận mã từ chối của từng bước. Test HTTP/H2 kiểm tra hai hành vi phụ thuộc vào tích hợp là rollback JTI khi outbox lỗi và HTTP 409 khi request còn xử lý. [Test từng bước](backend/src/test/java/tmc/access/application/step/) · [EdgeFlowTest](backend/src/test/java/tmc/bootstrap/EdgeFlowTest.java)

## Sai lệch so với thiết kế

Phase 0–1 dùng `EDGE_VERIFY` và `STRICT` để mô tả cách vận hành khi mất WAN. Demo dùng trạng thái snapshot `FRESH` và `STALE` khi chạy ở cấu hình snapshot. `FRESH` cho phép tiếp tục kiểm tra cục bộ. Tại bước policy, `STALE` dẫn đến `SNAPSHOT_STALE` và bỏ qua bước gửi lệnh cổng. [ADR-05](Phase1_Architecture.md#3-architecture-decisions-adr-index)

## M2 Checklist

### So với yêu cầu triển khai Phase 2

| Hạng mục | Đã có trong repo | Giới hạn hoặc sai lệch |
| --- | --- | --- |
| Task 2 · nghiệp vụ chính | Access quyết định tại Edge, ghi trước khi phát lệnh. UserQr cấp/thu hồi, Attendance nhận event và export demo. | Quản lý trạm đầy đủ, CRUD nhân viên, quản trị role/config và override offline hai người thuộc phạm vi thiết kế hoặc chưa hiện thực. |
| Pipeline và trạng thái | Các bước xác thực, chữ ký/thời gian, credential, policy, replay, anti-passback, transaction, lệnh và outcome. Ba trạng thái được giữ riêng. | Scope của trạm được kiểm tra trước khi trả một kết quả idempotent. Idempotency nằm trong use case. Passage là endpoint riêng và không nằm trên đường gửi lệnh. |
| Port và contract | Wiring ở bootstrap. Attendance nhận `AttendanceEvent` và ghi bảng riêng. Edge và Central dùng `EdgeSyncEvent`. | UserQr dùng các port credential/token của Access và Attendance dùng policy/scope ports của Access. Sync ánh xạ DTO sang `AccessAttempt` ở bộ nhận. Ranh giới giữa các module chưa đạt quy tắc tuyệt đối chỉ import từ package `contract`. |
| Runtime và tích hợp | Java 21, H2 JDBC transactions, outbox retry, ES256 và cấu hình snapshot. Domain và application không dùng Spring, JPA hay Jackson. | Adapter lưu trữ dùng Spring JDBC ngoài hai lớp API/bootstrap. Central receiver giữ dữ liệu trong bộ nhớ. Fake Turnstile, xác thực và ACK của HR là mô phỏng. Device-signed offline QR chỉ được mô tả trong ADR. |
| Mức bao phủ test | Test riêng cho cả 10 step, contract, HTTP với H2, fault injection cho outbox và ArchUnit. | Load harness dùng Node.js. Bộ test dùng dữ liệu và cấu hình demo. |

### Đối chiếu AC trong Phần I

| AC | Bằng chứng đang có | Việc còn thiếu để nghiệm thu |
| --- | --- | --- |
| AC-01 · QR và từ chối | Test riêng kiểm tra chữ ký ES256, `kid`, issuer/audience, biên thời gian, credential và thiết bị, station scope, snapshot, replay, anti-passback và lỗi lưu. `EdgeFlowTest` xác nhận không phát lệnh khi lưu thất bại. | Chạy ma trận QR/credential với dữ liệu và policy TMC duyệt. |
| AC-02 · lệnh cổng và passage | `GateCommandSenderContractTest`, `AccessAttemptTest` và `EdgeFlowTest` kiểm tra outcome riêng với passage. | Kiểm thử theo API và nguồn cảm biến thực. |
| AC-03 · Attendance Transaction | `EdgeFlowTest` kiểm tra ghi bản ghi và cờ HR review. `AttendanceEligibilityTest` kiểm tra ACKED-only. | Test từng trường employee, site, time, direction và evidence theo schema được duyệt. |
| AC-04 · độ bền dữ liệu | `EdgeFlowTest` ép lỗi insert outbox, kiểm tra JTI rollback và không phát lệnh. | Khởi động lại Edge/Central và đối soát số liệu theo định nghĩa normal operation. |
| AC-05 · idempotency | `EdgeFlowTest` kiểm tra replay kết quả cũ, hai request đồng thời dùng một JTI và HTTP 409 `REQUEST_IN_PROGRESS` có thể thử lại. `VerificationPipelineTest` kiểm tra trạng thái in-progress trong lúc gửi lệnh và việc gửi lệnh một lần khi retry sau timeout. | Kiểm thử quy tắc retry trên thiết bị thật. |
| AC-06 · thời gian kiosk | [Đo tải local](backend/README.md) thực hiện 800 scan/site với hai site đồng thời trong 30 giây, chậm nhất 74,55 ms ở ranh giới HTTP Edge. | Chạy kịch bản chính thức 30 phút tại từng site và đo tới kiosk với LAN/Turnstile thật. |
| AC-07 · gửi HR trước 12:00 ngày 1 | `EdgeFlowTest` dùng clock cố định, xác nhận batch tháng chỉ tạo một lần và lưu ACK mô phỏng trước trưa. | Schema, calendar, delivery ACK và đối soát với HR/Payroll thật. |
| AC-08 · RBAC | `EdgeFlowTest` kiểm tra yêu cầu token, Admin bị từ chối đọc attendance, scope Employee và quyền export của HR. | Ma trận role/scope TMC phê duyệt và test quản trị role/config. |
| AC-09 · audit | `EdgeFlowTest` đọc và tính lại hash audit, phát hiện khi đổi một trường. Có audit cho allow/deny, QR, correction và các thao tác attendance đã tích hợp. | Event catalog, quyền tra cứu, chống xóa và chính sách retention đầy đủ. |
| AC-10 · dữ liệu cá nhân | Test HTTP kiểm tra CSV không chứa QR gốc và log verify không chứa QR/token/dữ liệu nhân viên đưa vào test. | Rà dữ liệu QR/report/export và retention theo policy TMC/pháp lý. |

### Tài liệu đối chiếu

- Đề `SA_CC_hk261_Assignment_M2.pdf`, trang 2–3, phần scope, Task 1 và Task 2.
- `Phần I (fixed).docx`, mục 1.5.6, bảng Acceptance Criteria và Requirements Traceability Matrix.
- Yêu cầu Phase 2 đã thống nhất, mục 1–6 và 8, cùng slide `Chapter2.2_SA_CC.pdf` về LSP, ISP và DIP.
- `Chapter6_SA_CC.pdf`, slide 12–17, 27–30, 36–51 và 52–64, cho cách trình bày, ký hiệu và ranh giới giữa ba view.
- [Kiến trúc và ADR Phase 0–1](Phase1_Architecture.md) · [Kiến trúc của code đang chạy](#architecture-views)

[old-store]: https://github.com/lazy128/tmc-qr-attendance/blob/9563ddf618ccb3f30733189c6abf701d17a14058/backend/src/main/java/tmc/access/infrastructure/JdbcAttemptStore.java
[old-wiring]: https://github.com/lazy128/tmc-qr-attendance/blob/f49977eea377ec33298eb687ca38aa3a0dae8fe1/backend/src/main/java/tmc/bootstrap/AccessConfiguration.java
[old-worker]: https://github.com/lazy128/tmc-qr-attendance/blob/9563ddf618ccb3f30733189c6abf701d17a14058/backend/src/main/java/tmc/bootstrap/CentralOutboxWorker.java
[old-sync]: https://github.com/lazy128/tmc-qr-attendance/blob/9563ddf618ccb3f30733189c6abf701d17a14058/backend/src/main/java/tmc/shared/outbox/CentralSyncReceiver.java
