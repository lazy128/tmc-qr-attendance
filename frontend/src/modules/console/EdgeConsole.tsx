import {
  ArrowRight,
  Building2,
  ClipboardList,
  LayoutGrid,
  ScanLine,
  ShieldCheck,
  Smartphone,
} from "lucide-react";
import { Link } from "@tanstack/react-router";
import { useSyncExternalStore } from "react";
import { AccessWorkspace } from "../access/ui/AccessWorkspace";
import { AttendanceWorkspace } from "../attendance/ui/AttendanceWorkspace";
import { CredentialWorkspace } from "../userqr/ui/CredentialWorkspace";
import { useEdgeConsole, type ConsoleTab } from "./use-edge-console";

const navigation = {
  overview: { title: "Operations workspace", label: "Tổng quan", icon: LayoutGrid },
  access: { title: "Gate Access Service", label: "Truy cập cổng", icon: ScanLine },
  qr: { title: "User & QR Service", label: "Nhân viên & QR", icon: Smartphone },
  attendance: { title: "Attendance Service", label: "Chấm công & HR", icon: ClipboardList },
} satisfies Record<ConsoleTab, { title: string; label: string; icon: typeof ScanLine }>;

const modules = [
  { page: "access", detail: "Quét mã ở kiosk và xem quyết định, lệnh cổng, chứng cứ qua cổng." },
  { page: "qr", detail: "Cấp mã QR từ Edge và hiển thị trên thẻ nhân viên điện tử." },
  {
    page: "attendance",
    detail: "Xem sổ chấm công trong phạm vi được cấp quyền và tải CSV theo tháng.",
  },
] as const;

const subscribeToHydration = () => () => {};
const clientIsReady = () => true;
const serverIsReady = () => false;

export function EdgeConsole() {
  const ready = useSyncExternalStore(subscribeToHydration, clientIsReady, serverIsReady);
  const state = useEdgeConsole();

  return (
    <div className="facility-app">
      <aside className="facility-sidebar">
        <div className="facility-brand">
          <span>TMC</span>
          <div>
            <strong>FacilityOS</strong>
            <small>ACCESS INTELLIGENCE</small>
          </div>
        </div>
        <nav aria-label="Các module" className="facility-nav">
          {(Object.keys(navigation) as ConsoleTab[]).map((page) => {
            const { label, icon: Icon } = navigation[page];
            return (
              <button
                key={page}
                type="button"
                disabled={!ready}
                aria-current={state.tab === page ? "page" : undefined}
                onClick={() => state.setTab(page)}
              >
                <Icon size={17} aria-hidden="true" /> {label}
              </button>
            );
          })}
        </nav>
        <div className="facility-side-status">
          <ShieldCheck size={16} /> TMC · Edge demo
        </div>
      </aside>
      <main className="facility-main">
        <header className="facility-topbar">
          <div>
            <span>TMC</span>
            <span aria-hidden="true"> / </span>
            <strong>{navigation[state.tab].title}</strong>
          </div>
          <span className="facility-site">
            <Building2 size={15} /> {state.site || "Chưa chọn site"}
          </span>
        </header>
        <div className="facility-content">
          <h1>{navigation[state.tab].title}</h1>
          {state.error && (
            <p className="facility-error" role="alert">
              {state.error}
            </p>
          )}
          <fieldset disabled={!ready} className="min-w-0">
            {state.tab === "overview" && (
              <>
                <section className="facility-hero">
                  <div>
                    <h2>Ba module, một luồng truy cập</h2>
                    <p>Cấp QR nhân viên, quét ở cổng và xem bản ghi chấm công từ Edge.</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => state.setTab("access")}
                    className="facility-button facility-button--white"
                  >
                    <ScanLine size={16} /> Mở Kiosk
                  </button>
                </section>
                <nav className="facility-actions my-4" aria-label="Màn hình demo">
                  <Link to="/employee" className="facility-button">Trang nhân viên</Link>
                  <Link to="/gate" className="facility-button">Kiosk cổng</Link>
                  <Link to="/stations" className="facility-button">Quản lý trạm</Link>
                </nav>
                <div className="facility-modules">
                  {modules.map(({ page, detail }) => {
                    const { title, icon: Icon } = navigation[page];
                    return (
                      <section key={page} className="facility-card facility-module">
                        <span className={"facility-module-icon facility-module-icon--" + page}>
                          <Icon size={23} />
                        </span>
                        <h2>{title}</h2>
                        <p>{detail}</p>
                        <button
                          type="button"
                          className="facility-link"
                          onClick={() => state.setTab(page)}
                        >
                          Mở module <ArrowRight size={15} />
                        </button>
                      </section>
                    );
                  })}
                </div>
              </>
            )}
            {state.tab === "access" && (
              <AccessWorkspace
                site={state.site}
                station={state.station}
                device={state.device}
                token={state.token}
                issuedQr={state.issuedQr}
                direction={state.direction}
                busy={state.busy}
                result={state.result}
                onSite={state.setSite}
                onStation={state.setStation}
                onDevice={state.setDevice}
                onToken={state.setToken}
                onDirection={state.setDirection}
                onVerify={state.verify}
                onOpenQr={() => state.setTab("qr")}
              />
            )}
            {state.tab === "qr" && (
              <CredentialWorkspace
                credential={state.credential}
                device={state.device}
                issuedQr={state.issuedQr}
                busy={state.busy}
                onCredential={state.setCredential}
                onDevice={state.setDevice}
                onIssue={state.issueQr}
                onRevoke={state.revokeQr}
                onOpenKiosk={() => state.setTab("access")}
              />
            )}
            {state.tab === "attendance" && (
              <AttendanceWorkspace
                rows={state.rows}
                busy={state.busy}
                token={state.attendanceToken}
                onToken={state.updateAttendanceToken}
                onRefresh={state.refreshAttendance}
                onExport={state.exportAttendance}
              />
            )}
          </fieldset>
        </div>
      </main>
    </div>
  );
}
