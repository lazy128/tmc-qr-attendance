import { mkdir } from "node:fs/promises";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import jsQR from "jsqr";

const base = process.env.PLAYWRIGHT_BASE_URL ?? "http://127.0.0.1:5173";
const lanBase = process.env.PLAYWRIGHT_LAN_BASE_URL;
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

test("employee QR is scanned by the kiosk camera and phone capture, then appears on HR's daily sheet", async ({
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
      .poll(() =>
        canvas.evaluate(
          (el: HTMLCanvasElement) => el.width > 300 && el.style.width === `${el.clientWidth}px`,
        ),
      )
      .toBe(true);
    return canvas.screenshot();
  }

  await page.addInitScript(() => {
    const frame = document.createElement("canvas");
    frame.width = 480;
    frame.height = 360;
    const context = frame.getContext("2d")!;
    let image: HTMLImageElement | undefined;
    window.addEventListener("test-camera-frame", (event) => {
      image = (event as CustomEvent<HTMLImageElement>).detail;
    });
    Object.defineProperty(navigator.mediaDevices, "getUserMedia", {
      value: async () => {
        const stream = frame.captureStream(0);
        const track = stream.getVideoTracks()[0] as CanvasCaptureMediaStreamTrack;
        const timer = setInterval(() => {
          if (track.readyState !== "live") {
            clearInterval(timer);
            return;
          }
          track.requestFrame();
          context.fillStyle = "#39434a";
          context.fillRect(0, 0, frame.width, frame.height);
          if (image) context.drawImage(image, 60, 20);
        }, 66);
        return stream;
      },
    });
  });
  await page.goto(base + "/gate");
  const gates = page.getByLabel("Chọn cổng");
  await gates.selectOption({ label: "Bình Dương – Cổng vào 1" });
  await expect(canvas).toBeVisible();
  if (await personal.getByText("Đã ghi nhận giờ vào").count()) {
    await gates.selectOption({ label: "Bình Dương – Cổng ra 1" });
    await page.getByLabel("Chọn ảnh QR").setInputFiles({
      name: "pass.png",
      mimeType: "image/png",
      buffer: await newQr(),
    });
    await expect(page.getByRole("status")).toContainText("Đã ghi nhận giờ ra");
    await gates.selectOption({ label: "Bình Dương – Cổng vào 1" });
  }
  const qr = await newQr();
  await page.bringToFront();
  await page.getByRole("button", { name: "Mở camera" }).click();
  const video = page.getByLabel("Camera quét QR");
  await page.evaluate(
    async (src) => {
      const image = new Image();
      image.src = src;
      await image.decode();
      window.dispatchEvent(new CustomEvent("test-camera-frame", { detail: image }));
    },
    `data:image/png;base64,${qr.toString("base64")}`,
  );
  await expect(page.getByRole("status")).toContainText(
    "Xin chào Nguyễn Văn An, đã ghi nhận giờ vào",
  );
  await expect(video).toBeVisible();
  await expect.poll(() => video.evaluate((el: HTMLVideoElement) => el.videoWidth)).toBe(480);
  await capture(page, "02-kiosk.png");
  await page.getByRole("button", { name: "Tắt camera" }).click();
  await expect(video).toHaveCount(0);
  await expect(personal.getByRole("status")).toContainText("Đã ghi nhận giờ vào");
  await capture(personal, "01-nhan-vien.png");

  const phoneKiosk = await context.newPage();
  await phoneKiosk.setViewportSize({ width: 390, height: 844 });
  if (!lanBase) {
    await phoneKiosk.addInitScript(() => {
      Object.defineProperty(window, "isSecureContext", { value: false });
    });
  }
  await phoneKiosk.goto((lanBase ?? base) + "/gate");
  await phoneKiosk.getByLabel("Chọn cổng").selectOption({ label: "Bình Dương – Cổng ra 1" });
  const photo = phoneKiosk.getByLabel("Chụp mã QR");
  await expect(photo).toHaveAttribute("capture", "environment");
  await photo.setInputFiles({ name: "pass.png", mimeType: "image/png", buffer: await newQr() });
  await expect(phoneKiosk.getByRole("status")).toContainText("Đã ghi nhận giờ ra");
  await expect(personal.getByRole("status")).toContainText("Đã ghi nhận giờ ra");

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
    await expect(today.getByRole("cell").nth(2)).toHaveText(/^\d\d:\d\d$/, { timeout: 1500 });
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

test("opening the QR link confirms entry on both the phone and the employee page", async ({
  page,
  context,
}) => {
  test.skip(!lanBase, "Configure the LAN address as the QR scan origin to test phone links.");
  test.setTimeout(60_000);
  const phone = await context.newPage();
  await phone.setViewportSize({ width: 390, height: 844 });
  const employeeStatus = page.getByRole("status");

  async function openEmployeeQr() {
    await page.goto(base + "/employee");
    await page.getByLabel("Tài khoản demo").selectOption("NV003");
    const canvas = page.getByRole("img", { name: "Mã QR truy cập" });
    await expect
      .poll(() => canvas.evaluate((el: HTMLCanvasElement) => el.width))
      .toBeGreaterThan(300);
    const { width, encoded } = await canvas.evaluate((el: HTMLCanvasElement) => {
      const rgba = el.getContext("2d")!.getImageData(0, 0, el.width, el.height).data;
      let bytes = "";
      for (let offset = 0; offset < rgba.length; offset += 32768) {
        bytes += String.fromCharCode(...rgba.subarray(offset, offset + 32768));
      }
      return { width: el.width, encoded: btoa(bytes) };
    });
    const qr = jsQR(Uint8ClampedArray.from(Buffer.from(encoded, "base64")), width, width);
    expect(Boolean(qr)).toBe(true);
    const link = new URL(qr!.data);
    expect(link.origin).toBe(lanBase);
    expect(link.pathname).toBe("/gate");
    expect(link.hash.startsWith("#qr=")).toBe(true);
    await phone.goto(link.href);
    await expect(phone.getByRole("status")).toContainText("Đã đọc mã QR");
    await expect(phone).toHaveURL(`${lanBase}/gate`);
  }

  async function confirmAt(gate: string) {
    await phone.getByLabel("Chọn cổng").selectOption({ label: gate });
    await phone.getByRole("button", { name: "Xác nhận quét" }).click();
  }

  await openEmployeeQr();
  if (await employeeStatus.getByText("Đã ghi nhận giờ vào").count()) {
    await confirmAt("Bình Dương – Cổng ra 1");
    await expect(employeeStatus).toContainText("Đã ghi nhận giờ ra");
    await openEmployeeQr();
  }
  await expect(employeeStatus).not.toContainText("Đã ghi nhận giờ vào");
  await confirmAt("Bình Dương – Cổng vào 1");
  await expect(phone.getByRole("status")).toContainText(
    "Xin chào Lê Minh Khôi, đã ghi nhận giờ vào",
  );
  await expect(employeeStatus).toContainText("Đã ghi nhận giờ vào");

  await openEmployeeQr();
  await confirmAt("Bình Dương – Cổng ra 1");
  await expect(phone.getByRole("status")).toContainText("Đã ghi nhận giờ ra");
  await expect(employeeStatus).toContainText("Đã ghi nhận giờ ra");
});
