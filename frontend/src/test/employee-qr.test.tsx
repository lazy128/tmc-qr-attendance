import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { EmployeePage } from "../modules/userqr/ui/EmployeePage";
import { workflow } from "../modules/shared/workflow";
import { ApiRequestError } from "../modules/shared/http/client";
import QRCode from "qrcode";

vi.mock("../modules/shared/ui/AppPage", () => ({
  AppPage: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock("../modules/shared/ui/Auth", () => ({
  RequireAccount: ({
    children,
  }: {
    children: (account: { username: string; employeeId: string }) => React.ReactNode;
  }) => children({ username: "nhanvien", employeeId: "NV001" }),
}));
vi.mock("qrcode", () => ({ default: { toCanvas: vi.fn().mockResolvedValue(undefined) } }));

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
  window.history.replaceState(null, "", "/");
});

const staff = {
  id: "NV001",
  name: "Nguyễn Văn An",
  siteId: "SITE-BD",
  accessGroup: "STAFF",
} as const;
const attendance = {
  employeeId: staff.id,
  status: "NOT_ENTERED",
  lastEntry: null,
  lastExit: null,
  workDate: "2026-11-27",
} as const;
beforeEach(() => {
  vi.stubEnv("VITE_QR_SCAN_ORIGIN", "");
  vi.spyOn(workflow, "employee").mockResolvedValue(staff);
  vi.spyOn(workflow, "config").mockResolvedValue({ personalSelfEnabled: false });
  vi.spyOn(workflow, "devices").mockResolvedValue({ canManage: true, devices: [] });
  vi.spyOn(workflow, "attendance").mockResolvedValue(attendance);
  vi.spyOn(workflow, "activity").mockResolvedValue({
    employeeId: staff.id,
    otherSessions: 0,
    currentDeviceName: null,
    recentLogins: [],
    scans: [],
  });
});

it("displays the assigned branch and opens a separate QR link for the guard", async () => {
  vi.useFakeTimers();
  vi.stubEnv("VITE_QR_SCAN_ORIGIN", "https://192.168.1.25:5173");
  vi.spyOn(workflow, "pass").mockResolvedValue({
    ...attendance,
    token: "current",
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
  });
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  expect(screen.getByText("Chi nhánh được phân công · Bình Dương")).toBeInTheDocument();
  expect(workflow.pass).not.toHaveBeenCalled();
  expect(screen.queryByText("Chấm công cá nhân tự quét")).not.toBeInTheDocument();
  expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Mở QR cho bảo vệ quét" }));
  await advance(0);
  expect(screen.getByRole("heading", { name: "Mã QR cho bảo vệ" })).toBeInTheDocument();
  expect(QRCode.toCanvas).toHaveBeenLastCalledWith(
    expect.any(HTMLCanvasElement),
    "https://192.168.1.25:5173/gate#qr=current",
    expect.any(Object),
  );
});

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

it("renders a prefixed QR, refreshes eight seconds before expiry and clears a revoked pass", async () => {
  vi.useFakeTimers();
  vi.mocked(workflow.config).mockResolvedValue({ personalSelfEnabled: true });
  const issue = vi
    .spyOn(workflow, "pass")
    .mockResolvedValueOnce({
      ...attendance,
      token: "first",
      expiresAt: new Date(Date.now() + 30_000).toISOString(),
    })
    .mockRejectedValueOnce(new ApiRequestError("Credential revoked", "REVOKED"));
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  expect(screen.getByRole("img", { name: "Mã QR truy cập" })).toBeInTheDocument();
  expect(screen.queryByText("Chi tiết kỹ thuật")).not.toBeInTheDocument();
  expect(QRCode.toCanvas).toHaveBeenCalledWith(
    expect.any(HTMLCanvasElement),
    "tmc1:first",
    expect.any(Object),
  );
  expect(issue).toHaveBeenCalledWith(expect.any(AbortSignal), "ENTRY");
  expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  await advance(21_000);
  expect(issue).toHaveBeenCalledTimes(1);
  await advance(1_000);
  expect(issue).toHaveBeenCalledTimes(2);
  expect(screen.getAllByText("Đã thu hồi")).not.toHaveLength(0);
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

it("hides the QR once it expires after a failed refresh", async () => {
  vi.useFakeTimers();
  vi.mocked(workflow.config).mockResolvedValue({ personalSelfEnabled: true });
  vi.spyOn(workflow, "pass")
    .mockResolvedValueOnce({
      ...attendance,
      token: "old",
      expiresAt: new Date(Date.now() + 30_000).toISOString(),
    })
    .mockRejectedValueOnce(new Error("Edge unavailable"));
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  await advance(22_000);
  expect(screen.getByRole("img")).toBeInTheDocument();
  await advance(8_000);
  expect(screen.getAllByText("Đã hết hạn")).not.toHaveLength(0);
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

it("renders a personal phone link and replaces the QR after confirmed attendance", async () => {
  vi.useFakeTimers();
  vi.mocked(workflow.config).mockResolvedValue({ personalSelfEnabled: true });
  vi.stubEnv("VITE_QR_SCAN_ORIGIN", "https://192.168.1.25:5173");
  const issue = vi.spyOn(workflow, "pass").mockResolvedValue({
    ...attendance,
    token: "current",
    expiresAt: new Date(Date.now() + 30_000).toISOString(),
  });
  vi.mocked(workflow.attendance).mockResolvedValueOnce(attendance).mockResolvedValue({
    employeeId: staff.id,
    status: "IN_SHIFT",
    lastEntry: new Date().toISOString(),
    lastExit: null,
    workDate: attendance.workDate,
  });
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  const canvas = screen.getByRole("img", { name: "Mã QR truy cập" });
  expect(QRCode.toCanvas).toHaveBeenLastCalledWith(
    canvas,
    "https://192.168.1.25:5173/employee#personal=current",
    expect.any(Object),
  );
  expect(screen.getByRole("status")).toHaveTextContent("Chưa vào");
  await advance(2000);
  expect(screen.getByRole("status")).toHaveTextContent("Đã ghi nhận giờ vào");
  expect(screen.queryByRole("img", { name: "Mã QR truy cập" })).not.toBeInTheDocument();
  expect(screen.getByText("Đã xác nhận chấm công")).toBeInTheDocument();
  expect(issue).toHaveBeenCalledTimes(1);
});

it("closes QR actions after an exit and opens a fresh entry QR on the next work date", async () => {
  vi.useFakeTimers();
  vi.mocked(workflow.config).mockResolvedValue({ personalSelfEnabled: true });
  const inShift = {
    ...attendance,
    status: "IN_SHIFT",
    lastEntry: new Date().toISOString(),
  } as const;
  const nextDay = { ...attendance, workDate: "2026-11-28" };
  const finished = { ...inShift, status: "LEFT", lastExit: new Date().toISOString() } as const;
  vi.mocked(workflow.attendance)
    .mockResolvedValueOnce(inShift)
    .mockResolvedValueOnce(finished)
    .mockResolvedValue(nextDay);
  const issue = vi.spyOn(workflow, "pass").mockImplementation(async (_signal, direction) => ({
    ...(direction === "EXIT" ? inShift : nextDay),
    token: direction === "EXIT" ? "exit-qr" : "new-entry-qr",
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
  }));
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  fireEvent.click(screen.getByRole("button", { name: "Mở QR giờ ra" }));
  await advance(0);
  expect(issue).toHaveBeenLastCalledWith(expect.any(AbortSignal), "EXIT");
  expect(screen.getByRole("heading", { name: "Mã QR giờ ra" })).toBeInTheDocument();
  await advance(2001);
  await advance(0);
  expect(screen.getByText("Đã kết thúc ngày công.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /Mở QR/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Quét QR chấm công" })).not.toBeInTheDocument();
  expect(screen.queryByRole("img", { name: "Mã QR truy cập" })).not.toBeInTheDocument();
  await advance(2000);
  await advance(0);
  expect(issue).toHaveBeenLastCalledWith(expect.any(AbortSignal), "ENTRY");
  expect(screen.getByText("28/11/2026")).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Chấm công cá nhân tự quét" })).toBeInTheDocument();
});

it("hides a QR returned for a different account after a session changes", async () => {
  vi.useFakeTimers();
  vi.mocked(workflow.config).mockResolvedValue({ personalSelfEnabled: true });
  vi.spyOn(workflow, "pass").mockResolvedValue({
    ...attendance,
    employeeId: "NV002",
    token: "wrong-identity",
    expiresAt: new Date(Date.now() + 30_000).toISOString(),
  });
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  expect(screen.queryByRole("img", { name: "Mã QR truy cập" })).not.toBeInTheDocument();
});

it("keeps the one-minute QR readable until expiry if refresh stalls and the laptop clock is behind", async () => {
  vi.useFakeTimers();
  vi.mocked(workflow.config).mockResolvedValue({ personalSelfEnabled: true });
  vi.stubEnv("VITE_QR_SCAN_ORIGIN", "http://192.168.1.25:5173");
  const issuedAt = Date.parse("2026-11-26T10:00:00Z") / 1000;
  vi.setSystemTime((issuedAt - 60) * 1000);
  const header = btoa(JSON.stringify({ kid: "demo" }));
  const claims = btoa(JSON.stringify({ iat: issuedAt, exp: issuedAt + 60 }));
  const issue = vi
    .spyOn(workflow, "pass")
    .mockResolvedValueOnce({
      ...attendance,
      token: `${header}.${claims}.signature`,
      expiresAt: new Date((issuedAt + 60) * 1000).toISOString(),
    })
    .mockImplementationOnce(() => new Promise(() => {}));
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  expect(screen.getByText("59 giây")).toBeInTheDocument();
  await advance(55_000);
  expect(issue).toHaveBeenCalledTimes(1);
  expect(screen.getByRole("img", { name: "Mã QR truy cập" })).toBeInTheDocument();
  await advance(1000);
  expect(issue).toHaveBeenCalledTimes(2);
  expect(screen.getByRole("img", { name: "Mã QR truy cập" })).toBeInTheDocument();
  await advance(3000);
  expect(screen.queryByRole("img", { name: "Mã QR truy cập" })).not.toBeInTheDocument();
  expect(screen.getAllByText("Đã hết hạn")).not.toHaveLength(0);
});

it("uses a linked gate QR to record attendance in the employee session", async () => {
  vi.useFakeTimers();
  window.history.replaceState(null, "", "/employee#station=signed-station-code");
  const issue = vi.spyOn(workflow, "pass");
  const checkIn = vi.spyOn(workflow, "checkIn").mockResolvedValue({
    direction: "ENTRY",
    decision: "ALLOW",
    reasonCode: "ALLOW",
    command: "ACKED",
    scannedAt: new Date().toISOString(),
  });
  render(<EmployeePage />);
  await advance(0);
  expect(window.location.hash).toBe("");
  expect(checkIn).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Xác nhận chấm công" }));
  await advance(0);
  expect(checkIn).toHaveBeenCalledWith("signed-station-code", expect.any(AbortSignal));
  expect(screen.getByText(/Đã ghi nhận giờ vào ·/)).toBeInTheDocument();
  expect(issue).not.toHaveBeenCalled();
});

it("submits a personal QR to HR only when the demo flag is enabled", async () => {
  vi.useFakeTimers();
  vi.mocked(workflow.config).mockResolvedValue({ personalSelfEnabled: true });
  window.history.replaceState(null, "", "/employee#personal=employee-token");
  const personal = vi.spyOn(workflow, "personalCheckIn").mockResolvedValue({
    direction: "ENTRY",
    decision: "ALLOW",
    reasonCode: null,
    command: "NOT_SENT",
    evidence: "SELF_REPORTED",
    scannedAt: new Date().toISOString(),
  });
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  expect(window.location.hash).toBe("");
  expect(personal).toHaveBeenCalledExactlyOnceWith("employee-token", expect.any(AbortSignal));
  expect(screen.getByText("Đã gửi giờ vào để Nhân sự duyệt.")).toBeInTheDocument();
  expect(screen.queryByText("Đã xác nhận chấm công")).not.toBeInTheDocument();
  expect(screen.queryByRole("img", { name: "Mã QR truy cập" })).not.toBeInTheDocument();
});

it("does not submit a personal QR when self-reporting is disabled", async () => {
  vi.useFakeTimers();
  window.history.replaceState(null, "", "/employee#personal=old-employee-token");
  const personal = vi.spyOn(workflow, "personalCheckIn");
  render(<EmployeePage />);
  await advance(0);
  expect(screen.getByRole("alert")).toHaveTextContent("Tự khai qua QR cá nhân hiện đang tắt.");
  expect(personal).not.toHaveBeenCalled();
});

it("shows a pending previous gate command without confirming the attendance", async () => {
  vi.useFakeTimers();
  window.history.replaceState(null, "", "/employee#station=signed-station-code");
  vi.spyOn(workflow, "checkIn").mockResolvedValue({
    direction: "ENTRY",
    decision: "DENY",
    reasonCode: "COMMAND_PENDING",
    command: "NOT_SENT",
    scannedAt: new Date().toISOString(),
  });
  render(<EmployeePage />);
  await advance(0);
  fireEvent.click(screen.getByRole("button", { name: "Xác nhận chấm công" }));
  await advance(0);
  expect(screen.getByText("Lượt trước đang chờ xác nhận cổng.")).toBeInTheDocument();
  expect(screen.queryByText("Đã xác nhận chấm công")).not.toBeInTheDocument();
});

it("checks a saved gate command by event ID after the station QR expires", async () => {
  vi.useFakeTimers();
  window.history.replaceState(null, "", "/employee#station=signed-station-code");
  const attempt = {
    eventId: "incident-1",
    direction: "ENTRY",
    decision: "ALLOW",
    reasonCode: null,
    command: "UNKNOWN",
    scannedAt: new Date().toISOString(),
  } as const;
  const scan = vi.spyOn(workflow, "checkIn").mockResolvedValue(attempt);
  const status = vi.spyOn(workflow, "checkInStatus").mockResolvedValue({
    ...attempt,
    command: "EXPIRED",
  });
  render(<EmployeePage />);
  await advance(0);
  fireEvent.click(screen.getByRole("button", { name: "Xác nhận chấm công" }));
  await advance(0);
  fireEvent.click(screen.getByRole("button", { name: "Kiểm tra lại" }));
  await advance(0);
  expect(scan).toHaveBeenCalledTimes(1);
  expect(status).toHaveBeenCalledWith("incident-1", expect.any(AbortSignal));
  expect(screen.getByText(/đã chuyển cho Nhân sự đối soát/i)).toBeInTheDocument();
});
