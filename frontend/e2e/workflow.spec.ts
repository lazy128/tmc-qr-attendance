import { randomBytes } from "node:crypto";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import jsQR from "jsqr";

const base = process.env.PLAYWRIGHT_BASE_URL ?? "http://127.0.0.1:5173";
const api = base + "/edge-api";
const browser = process.env.PLAYWRIGHT_CHROME_PATH;
const adminPassword = process.env.E2E_ADMIN_PASSWORD;
if (browser) test.use({ launchOptions: { executablePath: browser } });

test("serves the TMC favicon and keeps private areas behind sign-in", async ({ page, request }) => {
  test.setTimeout(45_000);
  await page.goto(base + "/employee");
  await expect(page.getByRole("heading", { name: "Đăng nhập" })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("img", { name: "Mã QR truy cập" })).toHaveCount(0);
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute("href", "/tmc-icon.svg");
  expect((await request.get(base + "/tmc-icon.svg")).ok()).toBe(true);

  await page.goto(base + "/gate#qr=scanned-code");
  await expect(page.getByRole("heading", { name: "Đăng nhập" })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/Đăng nhập bằng tài khoản bảo vệ của trạm/)).toContainText(
    "Nếu mã vừa quét hết hạn",
  );
  await expect(page.getByRole("button", { name: "Xác nhận quét" })).toHaveCount(0);
});

async function login(request: APIRequestContext, username: string, password: string) {
  const response = await request.post(api + "/auth/v1/login", {
    data: { username, password },
    headers: { "X-TMC-Client-IP": "203.0.113.20" },
  });
  return response;
}

async function activate(request: APIRequestContext, username: string, password: string) {
  const initial = await login(request, username, password);
  expect(initial.status()).toBe(200);
  if (!(await initial.json()).changeRequired) return;
  const replacement = password + "-updated";
  const change = await request.post(api + "/auth/v1/password", {
    data: { current: password, replacement },
  });
  expect(change.status()).toBe(204);
  expect((await login(request, username, replacement)).status()).toBe(200);
}

async function signIn(page: Page, username: string, password: string) {
  await page.getByLabel("Tên đăng nhập").fill(username);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
}

async function createAccount(request: APIRequestContext, data: Record<string, string>) {
  const created = await request.post(api + "/workplace/v1/accounts", { data });
  expect(created.status()).toBe(201);
}

async function qrCanvas(page: Page, name: string) {
  const canvas = page.getByRole("img", { name });
  await expect(canvas).toBeVisible();
  await expect
    .poll(() =>
      canvas.evaluate(
        (el: HTMLCanvasElement) => el.width > 300 && el.style.width === `${el.clientWidth}px`,
      ),
    )
    .toBe(true);
  return canvas;
}

async function readQr(page: Page, name: string) {
  const canvas = await qrCanvas(page, name);
  const { width, encoded } = await canvas.evaluate((element: HTMLCanvasElement) => {
    const rgba = element.getContext("2d")!.getImageData(0, 0, element.width, element.height).data;
    let bytes = "";
    for (let offset = 0; offset < rgba.length; offset += 32768) {
      bytes += String.fromCharCode(...rgba.subarray(offset, offset + 32768));
    }
    return { width: element.width, encoded: btoa(bytes) };
  });
  const data = jsQR(Uint8ClampedArray.from(Buffer.from(encoded, "base64")), width, width)?.data;
  if (!data) throw new Error("Could not decode the displayed QR.");
  return data;
}

test("scans a personal QR on a second device and updates both screens and payroll", async ({
  browser,
}) => {
  test.skip(!adminPassword, "Set E2E_ADMIN_PASSWORD for a workplace test database.");
  test.setTimeout(150_000);
  const suffix = randomBytes(3).toString("hex");
  const password = "Initial-" + randomBytes(8).toString("hex");
  const employeeName = "Nguyễn Kiểm Thử";
  const employeeId = "NV-" + suffix.toUpperCase();
  const admin = await browser.newContext();
  const employeePhone = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const employee = await browser.newContext({ viewport: { width: 420, height: 880 } });
  const hr = await browser.newContext();
  try {
    const adminPage = await admin.newPage();
    const adminName = process.env.E2E_ADMIN_USERNAME ?? "admin";
    const existing = await login(admin.request, adminName, adminPassword! + "-updated");
    if (existing.status() === 401) await activate(admin.request, adminName, adminPassword!);
    await adminPage.goto(base + "/stations");
    await expect(adminPage.getByRole("heading", { name: "Danh sách tài khoản" })).toBeVisible({
      timeout: 15_000,
    });
    await adminPage.getByLabel("Nhà máy quản lý").selectOption("SITE-LA");
    await adminPage.getByRole("button", { name: "Trạm quét" }).click();
    await expect(adminPage.getByRole("heading", { name: "Trạm tại Long An" })).toBeVisible();
    await expect(adminPage.getByText("LA-G01", { exact: true })).toBeVisible();
    await expect(adminPage.getByText("BD-G01", { exact: true })).toHaveCount(0);
    await adminPage.getByRole("button", { name: "Tài khoản" }).click();
    await adminPage.getByLabel("Tên đăng nhập").fill("employee-" + suffix);
    await adminPage.getByLabel("Mật khẩu tạm").fill(password);
    await adminPage.getByLabel("Mã nhân viên").fill(employeeId);
    await adminPage.getByLabel("Họ tên").fill(employeeName);
    await adminPage.getByLabel("Bộ phận").fill("SAN-XUAT");
    await adminPage.getByRole("button", { name: "Tạo tài khoản" }).click();
    await expect(adminPage.getByRole("row").filter({ hasText: employeeId })).toContainText(
      "Chờ đổi mật khẩu",
    );

    await createAccount(admin.request, { username: "hr-" + suffix, role: "HR", password });
    await activate(hr.request, "hr-" + suffix, password);

    const person = await employee.newPage();
    await person.goto(base + "/employee");
    await signIn(person, "employee-" + suffix, password);
    await expect(person.getByRole("heading", { name: "Đổi mật khẩu" })).toBeVisible();
    await person.getByLabel("Mật khẩu hiện tại").fill(password);
    await person.getByLabel("Mật khẩu mới", { exact: true }).fill(password + "-updated");
    await person.getByLabel("Nhập lại mật khẩu mới").fill(password + "-updated");
    await person.getByRole("button", { name: "Lưu mật khẩu" }).click();
    await signIn(person, "employee-" + suffix, password + "-updated");
    await expect(person.getByRole("heading", { name: employeeName })).toBeVisible();
    await expect(
      person.getByRole("navigation").getByRole("link", { name: "Trạm quét" }),
    ).toHaveCount(0);
    await expect(person.getByRole("combobox")).toHaveCount(0);
    await expect(person.getByText("Chi nhánh được phân công · Long An")).toBeVisible();
    await expect(person.getByRole("status")).toContainText("Chưa vào");
    await person.getByText("Lịch sử đăng nhập", { exact: true }).click();
    await person.getByLabel("Tên thiết bị này").fill("Máy tính kiểm thử");
    await person.getByRole("button", { name: "Lưu tên thiết bị" }).click();
    await expect(person.getByText("Đã lưu tên cho thiết bị này.")).toBeVisible();
    expect(
      (await login(employeePhone.request, "employee-" + suffix, password + "-updated")).status(),
    ).toBe(200);
    const phone = await employeePhone.newPage();
    await phone.goto(base + "/employee");
    await expect(phone.getByRole("heading", { name: "Quét QR chấm công" })).toBeVisible({
      timeout: 15_000,
    });
    await phone.getByText("Lịch sử đăng nhập", { exact: true }).click();
    const desktopLogin = phone.getByRole("listitem").filter({ hasText: "Đang đăng nhập" });
    await expect(desktopLogin.getByText("Máy tính kiểm thử", { exact: true })).toBeVisible();
    await expect(desktopLogin.getByText("IP 127.0.0.1", { exact: true })).toBeVisible();
    const currentLogin = phone.getByRole("listitem").filter({ hasText: "Phiên hiện tại" });
    await expect(currentLogin.getByText("IP 127.0.0.1", { exact: true })).toBeVisible();
    const requests: string[] = [];
    await phone.route("**/workplace/v1/me/check-in/personal", async (route) => {
      requests.push(route.request().postData() ?? "");
      if (requests.length !== 1) {
        await route.continue();
        return;
      }
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      await route.abort("failed");
    });
    const personal = await readQr(person, "Mã QR truy cập");
    const personalHash = personal.startsWith("tmc1:")
      ? "#personal=" + personal.slice(5)
      : new URL(personal).hash;
    expect(personalHash).toMatch(/^#personal=/);
    await phone.goto(base + "/employee" + personalHash);
    await expect(phone.getByRole("alert")).toContainText("Chưa nhận được kết quả");
    await phone.getByRole("button", { name: "Kiểm tra lại" }).click();
    await expect(phone.getByText(/Đã ghi nhận giờ vào ·/)).toBeVisible();
    expect(requests.length).toBeGreaterThanOrEqual(2);
    expect(new Set(requests).size).toBe(1);
    await expect(person.getByRole("status")).toContainText("Đã ghi nhận giờ vào");
    await expect(person.getByText("Đã xác nhận chấm công")).toBeVisible();
    await expect(person.getByRole("img", { name: "Mã QR truy cập" })).toHaveCount(0);

    await person.getByRole("button", { name: "Mở QR giờ ra" }).click();
    const exitQr = await readQr(person, "Mã QR truy cập");
    const hash = exitQr.startsWith("tmc1:") ? "#personal=" + exitQr.slice(5) : new URL(exitQr).hash;
    await phone.goto(base + "/employee" + hash);
    await expect(phone.getByText(/Đã ghi nhận giờ ra ·/)).toBeVisible();
    await expect(person.getByRole("status")).toContainText("Đã ghi nhận giờ ra");
    await expect(person.getByText("Đã kết thúc ngày công.", { exact: true })).toBeVisible();
    await expect(person.getByRole("button", { name: /Mở QR/ })).toHaveCount(0);
    await expect(person.getByRole("heading", { name: "Quét QR chấm công" })).toHaveCount(0);

    const payroll = await hr.newPage();
    await payroll.goto(base + "/attendance");
    const row = payroll.getByRole("row").filter({ hasText: employeeId });
    await expect(async () => {
      await payroll.getByRole("button", { name: "Làm mới" }).click({ timeout: 2000 });
      await expect(row.getByRole("cell").nth(2)).toHaveText(/^\d\d:\d\d$/, { timeout: 1000 });
      await expect(row.getByRole("cell").nth(3)).toHaveText(/^\d\d:\d\d$/, { timeout: 1000 });
    }).toPass({ timeout: 15_000 });
    await expect(payroll.getByRole("button", { name: "Xuất CSV cho lương" })).toBeEnabled();

    await adminPage.getByRole("link", { name: "Mở bảng công & Excel" }).click();
    const reportSite = adminPage.getByRole("combobox", { name: "Nhà máy", exact: true });
    const filtered = adminPage.waitForResponse(
      (response) => {
        const url = new URL(response.url());
        return (
          url.pathname.endsWith("/attendance/v1/timesheets") &&
          url.searchParams.get("siteId") === "SITE-LA"
        );
      },
      { timeout: 10000 },
    );
    await reportSite.selectOption("SITE-LA", { timeout: 7000 });
    expect((await filtered).ok()).toBe(true);
    await expect(reportSite).toHaveValue("SITE-LA");
    const adminRow = adminPage.getByRole("row").filter({ hasText: employeeId });
    await expect(adminRow).toContainText("Long An");
    const downloaded = adminPage.waitForEvent("download", { timeout: 12000 });
    await adminPage.getByRole("button", { name: "Tải bảng tính (.xlsx)" }).click();
    expect((await downloaded).suggestedFilename()).toMatch(/-SITE-LA\.xlsx$/);
    await reportSite.selectOption("SITE-BD", { timeout: 7000 });
    await expect(adminRow).toHaveCount(0);

    await person.goto(base + "/gate");
    await expect(person.getByRole("alert")).toContainText("Bảo vệ");
    await expect(person.getByLabel("Chọn ảnh QR")).toHaveCount(0);
  } finally {
    await Promise.all([admin.close(), employeePhone.close(), employee.close(), hr.close()]);
  }
});
