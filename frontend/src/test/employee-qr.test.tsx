import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { EmployeePage } from "../modules/userqr/ui/EmployeePage";
import { qrApi } from "../modules/userqr/api/qr-api";
import { ApiRequestError } from "../modules/shared/http/client";
import QRCode from "qrcode";

vi.mock("../modules/shared/ui/DemoPage", () => ({
  DemoPage: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock("qrcode", () => ({ default: { toCanvas: vi.fn().mockResolvedValue(undefined) } }));

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function openPass() {
  render(<EmployeePage />);
  fireEvent.change(screen.getByLabelText("Mã đăng nhập nhân viên"), {
    target: { value: "employee" },
  });
  fireEvent.change(screen.getByLabelText("Mã đăng ký do HR cấp"), { target: { value: "proof" } });
  fireEvent.click(screen.getByRole("button", { name: "Hiển thị QR" }));
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

it("renders a prefixed QR, refreshes eight seconds before expiry and clears a revoked pass", async () => {
  vi.useFakeTimers();
  const issue = vi
    .spyOn(qrApi, "issue")
    .mockResolvedValueOnce({
      token: "first",
      expiresAt: new Date(Date.now() + 30_000).toISOString(),
    })
    .mockRejectedValueOnce(new ApiRequestError("Credential revoked", "REVOKED"));
  openPass();
  await advance(0);
  expect(screen.getByRole("img", { name: "Mã QR truy cập" })).toBeInTheDocument();
  expect(QRCode.toCanvas).toHaveBeenCalledWith(
    expect.any(HTMLCanvasElement),
    "tmc1:first",
    expect.any(Object),
  );
  expect(issue).toHaveBeenCalledWith(
    "demo-credential",
    "employee",
    "proof",
    expect.any(AbortSignal),
  );
  await advance(21_000);
  expect(issue).toHaveBeenCalledTimes(1);
  await advance(1_000);
  expect(issue).toHaveBeenCalledTimes(2);
  expect(screen.getByText("REVOKED")).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});

it("hides the QR once it expires after a failed refresh", async () => {
  vi.useFakeTimers();
  vi.spyOn(qrApi, "issue")
    .mockResolvedValueOnce({ token: "old", expiresAt: new Date(Date.now() + 30_000).toISOString() })
    .mockRejectedValueOnce(new Error("Edge unavailable"));
  openPass();
  await advance(0);
  await advance(22_000);
  expect(screen.getByRole("img")).toBeInTheDocument();
  await advance(8_000);
  expect(screen.getByText("EXPIRED")).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();
});
