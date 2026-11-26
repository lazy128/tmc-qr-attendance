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
});

const staff = {
  id: "NV001",
  name: "Nguyễn Văn An",
  siteId: "SITE-BD",
  accessGroup: "STAFF",
} as const;
const attendance = { status: "NOT_ENTERED", lastEntry: null, lastExit: null } as const;
beforeEach(() => vi.spyOn(workflow, "employees").mockResolvedValue([staff]));

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
