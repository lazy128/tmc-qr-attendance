import { createFileRoute, Link } from "@tanstack/react-router";
import { ClipboardList, ScanLine, Settings, Smartphone } from "lucide-react";
import { AppPage } from "../modules/shared/ui/AppPage";
import { RequireAccount } from "../modules/shared/ui/Auth";
import { canManageTimesheets } from "../modules/shared/workflow";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [{ title: "TMC Chấm công" }],
  }),
  component: Index,
});

function Index() {
  return (
    <AppPage title="Chấm công tại nhà máy">
      <RequireAccount>
        {(account) => (
          <div className="workflow-home">
            {account.employeeId && (
              <Link to="/employee" className="workflow-card">
                <Smartphone />
                <strong>Mã QR của tôi</strong>
                <span>Mở mã để chấm công vào hoặc ra.</span>
              </Link>
            )}
            {account.role === "GUARD" && (
              <Link to="/gate" className="workflow-card">
                <ScanLine />
                <strong>Trạm quét</strong>
                <span>Mở máy quét tại trạm {account.stationId}.</span>
              </Link>
            )}
            {(account.employeeId || canManageTimesheets(account.role)) && (
              <Link to="/attendance" className="workflow-card">
                <ClipboardList />
                <strong>
                  {canManageTimesheets(account.role) ? "Bảng công & Excel" : "Bảng công"}
                </strong>
                <span>
                  {canManageTimesheets(account.role)
                    ? "Xem theo nhà máy và tải Excel hoặc CSV."
                    : "Theo dõi giờ vào, giờ ra và số giờ làm."}
                </span>
              </Link>
            )}
            {account.role === "ADMIN" && (
              <Link to="/stations" className="workflow-card">
                <Settings />
                <strong>Quản trị</strong>
                <span>Quản lý trạm, nhân viên và tài khoản.</span>
              </Link>
            )}
          </div>
        )}
      </RequireAccount>
    </AppPage>
  );
}
