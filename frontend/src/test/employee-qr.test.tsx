import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { EmployeePage } from "../modules/userqr/ui/EmployeePage";
import { workflow } from "../modules/shared/workflow";
import { ApiRequestError } from "../modules/shared/http/client";
import QRCode from "qrcode";

vi.mock("../modules/shared/ui/DemoPage", () => ({
  DemoPage: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock("qrcode", () => ({ default: { toCanvas: vi.fn().mockResolvedValue(undefined) } }));

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

const staff = {
  id: "NV001",
  name: "Nguyễn Văn An",
  siteId: "SITE-BD",
  accessGroup: "STAFF",
} as const;
const attendance = { status: "NOT_ENTERED", lastEntry: null, lastExit: null } as const;
beforeEach(() => {
  vi.stubEnv("VITE_QR_SCAN_ORIGIN", "");
  vi.spyOn(workflow, "employees").mockResolvedValue([staff]);
  vi.spyOn(workflow, "attendance").mockResolvedValue(attendance);
});

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

it("renders a prefixed QR, refreshes eight seconds before expiry and clears a revoked pass", async () => {
  vi.useFakeTimers();
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
  expect(QRCode.toCanvas).toHaveBeenCalledWith(
    expect.any(HTMLCanvasElement),
    "tmc1:first",
    expect.any(Object),
  );
  expect(issue).toHaveBeenCalledWith("NV001", expect.any(AbortSignal));
  await advance(21_000);
  expect(issue).toHaveBeenCalledTimes(1);
  await advance(1_000);
  expect(issue).toHaveBeenCalledTimes(2);
  expect(screen.getAllByText("Đã thu hồi")).not.toHaveLength(0);
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

it("hides the QR once it expires after a failed refresh", async () => {
  vi.useFakeTimers();
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

it("renders a phone link and confirms attendance without replacing the current QR", async () => {
  vi.useFakeTimers();
  vi.stubEnv("VITE_QR_SCAN_ORIGIN", "http://192.168.1.25:5173");
  const issue = vi.spyOn(workflow, "pass").mockResolvedValue({
    ...attendance,
    token: "current",
    expiresAt: new Date(Date.now() + 30_000).toISOString(),
  });
  vi.mocked(workflow.attendance).mockResolvedValue({
    status: "IN_SHIFT",
    lastEntry: new Date().toISOString(),
    lastExit: null,
  });
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  const canvas = screen.getByRole("img", { name: "Mã QR truy cập" });
  expect(QRCode.toCanvas).toHaveBeenLastCalledWith(
    canvas,
    "http://192.168.1.25:5173/gate#qr=current",
    expect.any(Object),
  );
  expect(screen.getByRole("status")).toHaveTextContent("Chưa vào");
  await advance(2000);
  expect(screen.getByRole("status")).toHaveTextContent("Đã ghi nhận giờ vào");
  expect(screen.getByRole("img", { name: "Mã QR truy cập" })).toBe(canvas);
  expect(issue).toHaveBeenCalledTimes(1);
});

it("budgets time to open a phone link even when the laptop clock is behind", async () => {
  vi.useFakeTimers();
  vi.stubEnv("VITE_QR_SCAN_ORIGIN", "http://192.168.1.25:5173");
  const issuedAt = Date.parse("2026-11-26T10:00:00Z") / 1000;
  vi.setSystemTime((issuedAt - 60) * 1000);
  const header = btoa(JSON.stringify({ kid: "demo" }));
  const claims = btoa(JSON.stringify({ iat: issuedAt, exp: issuedAt + 30 }));
  const issue = vi
    .spyOn(workflow, "pass")
    .mockResolvedValueOnce({
      ...attendance,
      token: `${header}.${claims}.signature`,
      expiresAt: new Date((issuedAt + 30) * 1000).toISOString(),
    })
    .mockImplementationOnce(() => new Promise(() => {}));
  render(<EmployeePage />);
  await advance(0);
  await advance(0);
  expect(screen.getByText("29 giây")).toBeInTheDocument();
  await advance(11_000);
  expect(issue).toHaveBeenCalledTimes(2);
  expect(screen.getByRole("img", { name: "Mã QR truy cập" })).toBeInTheDocument();
  await advance(3000);
  expect(screen.queryByRole("img", { name: "Mã QR truy cập" })).not.toBeInTheDocument();
  expect(screen.getAllByText("Đang làm mới…")).not.toHaveLength(0);
  expect(screen.queryByText("Đã hết hạn")).not.toBeInTheDocument();
});
