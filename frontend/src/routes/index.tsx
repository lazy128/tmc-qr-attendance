import { createFileRoute, Link } from "@tanstack/react-router";
import { ClipboardList, ScanLine, Smartphone } from "lucide-react";
import { DemoPage } from "../modules/shared/ui/DemoPage";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [{ title: "TMC Chấm công" }],
  }),
  component: Index,
});

function Index() {
  return (
    <DemoPage title="Chấm công tại nhà máy">
      <p className="workflow-subtitle">
        Hiện QR vào ca, quét khi ra về và đối chiếu bảng công theo ngày.
      </p>
      <div className="workflow-home">
        <Link to="/employee" className="workflow-card">
          <Smartphone />
          <strong>Mã QR nhân viên</strong>
          <span>Chọn tài khoản để mở mã QR.</span>
        </Link>
        <Link to="/gate" className="workflow-card">
          <ScanLine />
          <strong>Kiosk cổng</strong>
          <span>Quét mã lúc vào, lúc ra và tại khu vực có phân quyền.</span>
        </Link>
        <Link to="/attendance" className="workflow-card">
          <ClipboardList />
          <strong>Bảng công</strong>
          <span>Xem giờ làm hàng ngày và xuất dữ liệu cho HR.</span>
        </Link>
      </div>
      <div className="mt-4">
        <Link to="/stations" className="workflow-button secondary">
          Quản lý trạm
        </Link>
      </div>
    </DemoPage>
  );
}
