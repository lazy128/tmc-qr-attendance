import { mkdir } from "node:fs/promises";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";

const base = process.env.PLAYWRIGHT_BASE_URL ?? "http://127.0.0.1:5173";
const screenshots = process.env.E2E_WORKFLOW_SCREENSHOTS;
const browser = process.env.PLAYWRIGHT_CHROME_PATH;
if (browser) test.use({ launchOptions: { executablePath: browser } });

test("serves the TMC favicon and no longer serves the legacy console", async ({
  page,
  request,
}) => {
  await page.goto(base + "/");
  const icon = page.locator('link[rel="icon"]');
  await expect(icon).toHaveAttribute("href", "/tmc-icon.svg");
  const favicon = await request.get(base + "/tmc-icon.svg");
  expect(favicon.ok()).toBe(true);
  expect(favicon.headers()["content-type"]).toContain("image/svg+xml");
  const removed = await page.goto(base + "/technical");
  expect(removed?.status()).toBe(404);
  await expect(page.getByRole("heading", { name: "Không tìm thấy trang" })).toBeVisible();
});

async function capture(page: Page, name: string) {
  if (!screenshots) return;
  await mkdir(screenshots, { recursive: true });
  await page.screenshot({ path: join(screenshots, name), animations: "disabled", fullPage: true });
}

test("employee QR is scanned in and out, then appears on HR's daily sheet", async ({
  page,
  context,
}) => {
  test.setTimeout(90_000);
  const personal = await context.newPage();
  await personal.setViewportSize({ width: 420, height: 880 });
  await personal.goto(base + "/employee");
  await expect(personal.getByRole("heading", { name: "Nguyễn Văn An" })).toBeVisible();
  const canvas = personal.getByRole("img", { name: "Mã QR truy cập" });

  async function newQr() {
    await personal.reload();
    await expect(canvas).toBeVisible();
    await expect
      .poll(() => canvas.evaluate((element: HTMLCanvasElement) => element.width))
      .toBe(320);
    return canvas.screenshot();
  }

  async function scan(buffer: Buffer) {
    await page
      .getByLabel("Chọn ảnh QR")
      .setInputFiles({ name: "pass.png", mimeType: "image/png", buffer });
  }

  await page.goto(base + "/gate");
  const gates = page.getByLabel("Chọn cổng");
  await gates.selectOption({ label: "Bình Dương – Cổng vào 1" });
  if (await personal.getByText("Đang trong ca").count()) {
    await gates.selectOption({ label: "Bình Dương – Cổng ra 1" });
    await scan(await newQr());
    await expect(page.getByRole("status")).toContainText("Đã ghi nhận giờ ra");
    await gates.selectOption({ label: "Bình Dương – Cổng vào 1" });
  }
  await scan(await newQr());
  await expect(page.getByRole("status")).toContainText(
    "Xin chào Nguyễn Văn An, đã ghi nhận giờ vào",
  );
  await capture(page, "02-kiosk.png");
  await newQr();
  await expect(personal.getByText("Đang trong ca")).toBeVisible();
  await capture(personal, "01-nhan-vien.png");

  await gates.selectOption({ label: "Bình Dương – Cổng ra 1" });
  await scan(await newQr());
  await expect(page.getByRole("status")).toContainText("Đã ghi nhận giờ ra");

  await page.goto(base + "/attendance");
  // At the default 06:00 Vietnam cutoff the work date is UTC+1's calendar date.
  const workDay = new Date(Date.now() + 3_600_000)
    .toISOString()
    .slice(0, 10)
    .split("-")
    .reverse()
    .join("/");
  const today = page
    .getByRole("row")
    .filter({ hasText: "Nguyễn Văn An" })
    .filter({ hasText: workDay });
  await expect(async () => {
    await page.getByRole("button", { name: "Làm mới" }).click({ timeout: 2500 });
    await expect(today.getByRole("cell").nth(3)).toHaveText(/^\d\d:\d\d$/, { timeout: 1500 });
  }).toPass({ timeout: 15000 });
  await expect(page.getByRole("row").filter({ hasText: "Thiếu giờ ra" })).toBeVisible();
  await expect(page.getByRole("row").filter({ hasText: "Trùng lượt" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Tải bảng tính (.xlsx)" })).toBeEnabled({
    timeout: 30000,
  });
  await capture(page, "03-bang-cong.png");
  await page.getByLabel("Vai trò demo").selectOption("EMPLOYEE");
  await expect(page.getByRole("row").filter({ hasText: "Nguyễn Văn An" }).first()).toBeVisible();
  await expect(page.getByRole("row").filter({ hasText: "Trần Thị Mai" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Xuất CSV cho lương" })).toHaveCount(0);
});
