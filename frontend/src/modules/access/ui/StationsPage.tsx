import { useEffect, useState, type FormEvent } from "react";
import { Link } from "@tanstack/react-router";
import { request } from "../../shared/http/client";
import { errorMessage } from "../../shared/lib/error-message";
import { AppPage } from "../../shared/ui/AppPage";
import { RequireAccount } from "../../shared/ui/Auth";
import {
  accountApi,
  groupLabel,
  roleLabel,
  siteName,
  workflow,
  type Account,
  type Group,
  type Role,
  type Station,
} from "../../shared/workflow";

type Registry = Awaited<ReturnType<typeof workflow.stations>>;
const stationPath = "/admin/v1/stations";
const groups = Object.keys(groupLabel) as Group[];
const roles = Object.keys(roleLabel) as Role[];

export function StationsPage() {
  return (
    <AppPage title="Quản trị hệ thống">
      <RequireAccount roles={["ADMIN"]}>
        {(account) => <Administration key={account.username} actor={account.username} />}
      </RequireAccount>
    </AppPage>
  );
}

function Administration({ actor }: { actor: string }) {
  const [registry, setRegistry] = useState<Registry | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [stationEdit, setStationEdit] = useState<Station | null>(null);
  const [reset, setReset] = useState<Account | null>(null);
  const [role, setRole] = useState<Role>("EMPLOYEE");
  const [selectedSite, setSelectedSite] = useState("");
  const [tab, setTab] = useState<"accounts" | "stations">("accounts");
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [revision, setRevision] = useState(0);
  const siteId = selectedSite || registry?.siteId || "";
  useEffect(() => {
    const controller = new AbortController();
    void Promise.all([workflow.stations(controller.signal), accountApi.list(controller.signal)])
      .then(([stations, users]) => {
        if (!controller.signal.aborted) {
          setRegistry(stations);
          setAccounts(users);
        }
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setLoadError(errorMessage(cause));
      });
    return () => controller.abort();
  }, [revision]);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await action();
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  function updateAccount(updated: Account) {
    setAccounts((previous) =>
      [...previous.filter((item) => item.username !== updated.username), updated].sort(
        (left, right) => left.username.localeCompare(right.username),
      ),
    );
  }

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const body = Object.fromEntries(new FormData(form));
    void run(async () => {
      updateAccount(await accountApi.create({ ...body, siteId }));
      form.reset();
      setRole("EMPLOYEE");
      setNotice("Đã tạo tài khoản. Người dùng sẽ đổi mật khẩu ở lần đăng nhập đầu.");
    });
  }

  function saveStation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const value = (key: string) => String(data.get(key) ?? "").trim();
    const gate = {
      name: value("name"),
      type: value("type"),
      allowedGroups: data.getAll("groups"),
      directions: [value("direction")],
      opensAt: value("opensAt") || null,
      closesAt: value("closesAt") || null,
    };
    const gateId = value("gateId");
    void run(async () => {
      const saved = await request<Station>(
        stationEdit ? `${stationPath}/${encodeURIComponent(stationEdit.stationId)}` : stationPath,
        {
          method: stationEdit ? "PATCH" : "POST",
          body: JSON.stringify(
            stationEdit
              ? { gateId, gate, status: value("status") }
              : { gateId, gate, siteId, stationId: value("stationId") },
          ),
        },
      );
      setRegistry(
        (previous) =>
          previous && {
            ...previous,
            stations: [
              ...previous.stations.filter((row) => row.stationId !== saved.stationId),
              saved,
            ].sort((left, right) => left.stationId.localeCompare(right.stationId)),
          },
      );
      setStationEdit(null);
      form.reset();
      setNotice("Đã lưu cấu hình trạm.");
    });
  }

  if (loadError)
    return (
      <section className="workflow-card workflow-login">
        <p role="alert" className="workflow-error">
          {loadError}
        </p>
        <button
          className="workflow-button"
          onClick={() => {
            setLoadError("");
            setRevision((value) => value + 1);
          }}
        >
          Thử lại
        </button>
      </section>
    );
  if (!registry) return <p role="status">Đang tải cấu hình…</p>;
  const associateEmployee = role === "EMPLOYEE" || role === "MANAGER";
  const siteStations = registry.stations.filter((station) => station.siteId === siteId);
  const activeStations = siteStations.filter((station) => station.status === "ACTIVE");
  const siteAccounts = accounts.filter((account) => !account.siteId || account.siteId === siteId);
  return (
    <div className="grid gap-4">
      <div className="workflow-actions" aria-label="Quản trị">
        <button
          className={`workflow-button ${tab === "accounts" ? "" : "secondary"}`}
          aria-pressed={tab === "accounts"}
          onClick={() => {
            setTab("accounts");
            setError("");
            setNotice("");
          }}
          disabled={busy}
        >
          Tài khoản
        </button>
        <button
          className={`workflow-button ${tab === "stations" ? "" : "secondary"}`}
          aria-pressed={tab === "stations"}
          onClick={() => {
            setTab("stations");
            setError("");
            setNotice("");
          }}
          disabled={busy}
        >
          Trạm quét
        </button>
        <Link to="/attendance" className="workflow-button secondary">
          Mở bảng công & Excel
        </Link>
      </div>
      <label className="workflow-field w-full max-w-72">
        Nhà máy quản lý
        <select
          value={siteId}
          disabled={busy}
          onChange={(event) => {
            setSelectedSite(event.target.value);
            setStationEdit(null);
            setReset(null);
            setError("");
            setNotice("");
          }}
        >
          {(registry.siteIds ?? [registry.siteId]).map((id) => (
            <option key={id} value={id}>
              {siteName(id)}
            </option>
          ))}
        </select>
      </label>
      {error && (
        <p className="workflow-error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="workflow-today" role="status">
          {notice}
        </p>
      )}
      {tab === "accounts" ? (
        <>
          <section className="workflow-card grid gap-3">
            <h2>Danh sách tài khoản</h2>
            <div className="workflow-table-wrap">
              <table className="workflow-table">
                <thead>
                  <tr>
                    <th scope="col">Tài khoản</th>
                    <th scope="col">Vai trò</th>
                    <th scope="col">Nhà máy</th>
                    <th scope="col">Trạng thái</th>
                    <th scope="col">Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {siteAccounts.map((account) => (
                    <tr key={account.username}>
                      <td>
                        <strong>{account.displayName}</strong>
                        <small>
                          {account.username}
                          {account.employeeId
                            ? ` · ${account.employeeId}`
                            : account.stationId
                              ? ` · ${account.stationId}`
                              : ""}
                        </small>
                      </td>
                      <td>{roleLabel[account.role]}</td>
                      <td>{account.siteId ? siteName(account.siteId) : "Toàn hệ thống"}</td>
                      <td>
                        {!account.enabled
                          ? "Đã khóa"
                          : account.changeRequired
                            ? "Chờ đổi mật khẩu"
                            : "Hoạt động"}
                      </td>
                      <td>
                        {account.username !== actor && (
                          <div className="workflow-actions">
                            <button
                              className="workflow-text-button"
                              disabled={busy}
                              onClick={() =>
                                void run(async () => {
                                  setReset(null);
                                  updateAccount(
                                    await accountApi.update(account.username, {
                                      enabled: !account.enabled,
                                    }),
                                  );
                                  setNotice(
                                    account.enabled
                                      ? "Đã khóa tài khoản."
                                      : "Đã mở khóa tài khoản.",
                                  );
                                })
                              }
                            >
                              {account.enabled ? "Khóa" : "Mở khóa"}
                            </button>
                            <button
                              className="workflow-text-button"
                              disabled={busy}
                              onClick={() => setReset(account)}
                            >
                              Đặt lại mật khẩu
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          {reset && (
            <form
              className="workflow-card grid gap-3"
              key={reset.username}
              onSubmit={(event) => {
                event.preventDefault();
                const password = String(new FormData(event.currentTarget).get("password"));
                void run(async () => {
                  updateAccount(await accountApi.update(reset.username, { password }));
                  setReset(null);
                  setNotice("Đã đặt lại mật khẩu và kết thúc các phiên hiện có.");
                });
              }}
            >
              <h2>Đặt lại mật khẩu cho {reset.username}</h2>
              <label className="workflow-field">
                Mật khẩu tạm{" "}
                <input
                  name="password"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  maxLength={72}
                  required
                />
              </label>
              <div className="workflow-actions">
                <button className="workflow-button" disabled={busy}>
                  Lưu mật khẩu tạm
                </button>
                <button
                  className="workflow-button secondary"
                  type="button"
                  disabled={busy}
                  onClick={() => setReset(null)}
                >
                  Hủy
                </button>
              </div>
            </form>
          )}
          <form className="workflow-card grid gap-3" onSubmit={create}>
            <h2>Tạo tài khoản</h2>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="workflow-field">
                Tên đăng nhập{" "}
                <input name="username" autoComplete="off" minLength={3} maxLength={60} required />
              </label>
              <label className="workflow-field">
                Mật khẩu tạm{" "}
                <input
                  name="password"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  maxLength={72}
                  required
                />
              </label>
              <label className="workflow-field">
                Vai trò
                <select
                  name="role"
                  value={role}
                  onChange={(event) => setRole(event.target.value as Role)}
                >
                  {roles.map((item) => (
                    <option key={item} value={item}>
                      {roleLabel[item]}
                    </option>
                  ))}
                </select>
              </label>
              {associateEmployee && (
                <>
                  <label className="workflow-field">
                    Mã nhân viên <input name="employeeId" maxLength={60} required />
                  </label>
                  <label className="workflow-field">
                    Họ tên <input name="fullName" maxLength={120} required />
                  </label>
                  <label className="workflow-field">
                    Bộ phận <input name="departmentId" maxLength={60} required />
                  </label>
                  <label className="workflow-field">
                    Nhóm truy cập{" "}
                    <select
                      name="accessGroup"
                      defaultValue={role === "MANAGER" ? "MANAGER" : "STAFF"}
                      key={role}
                    >
                      {groups.map((group) => (
                        <option value={group} key={group}>
                          {groupLabel[group]}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="workflow-field">
                    Ca{" "}
                    <select name="shiftId">
                      <option value="STANDARD">Ca ngày</option>
                      <option value="NIGHT">Ca đêm</option>
                    </select>
                  </label>
                </>
              )}
              {role === "GUARD" && (
                <label className="workflow-field">
                  Trạm được giao
                  <select name="stationId" key={siteId} defaultValue="" required>
                    <option disabled value="">
                      Chọn trạm
                    </option>
                    {activeStations.map((item) => (
                      <option value={item.stationId} key={item.stationId}>
                        {item.gate.name} · {item.stationId}
                      </option>
                    ))}
                  </select>
                </label>
              )}
            </div>
            {associateEmployee && <p>Nhà máy {siteName(siteId)}</p>}
            <button
              className="workflow-button"
              disabled={busy || (role === "GUARD" && !activeStations.length)}
            >
              Tạo tài khoản
            </button>
          </form>
        </>
      ) : (
        <>
          <section className="workflow-card grid gap-3">
            <h2>Trạm tại {siteName(siteId)}</h2>
            <div className="workflow-table-wrap">
              <table className="workflow-table">
                <thead>
                  <tr>
                    <th scope="col">Trạm</th>
                    <th scope="col">Tên cổng</th>
                    <th scope="col">Hướng</th>
                    <th scope="col">Trạng thái</th>
                    <th scope="col">Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {siteStations.map((station) => (
                    <tr key={station.stationId}>
                      <td>{station.stationId}</td>
                      <td>{station.gate.name}</td>
                      <td>
                        {station.gate.directions
                          .map((direction) => (direction === "ENTRY" ? "Vào" : "Ra"))
                          .join(", ")}
                      </td>
                      <td>{station.status === "ACTIVE" ? "Đang hoạt động" : "Đã tắt"}</td>
                      <td>
                        <button
                          className="workflow-text-button"
                          disabled={busy}
                          onClick={() => setStationEdit(station)}
                        >
                          Sửa trạm
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <form
            key={stationEdit?.stationId ?? siteId}
            className="workflow-card grid gap-3"
            onSubmit={saveStation}
          >
            <h2>{stationEdit ? `Cấu hình ${stationEdit.stationId}` : "Đăng ký trạm"}</h2>
            <div className="grid gap-3 sm:grid-cols-2">
              {!stationEdit && (
                <label className="workflow-field">
                  Mã trạm <input name="stationId" required maxLength={100} />
                </label>
              )}
              <label className="workflow-field">
                Mã cổng{" "}
                <input name="gateId" defaultValue={stationEdit?.gateId} required maxLength={100} />
              </label>
              <label className="workflow-field">
                Tên hiển thị{" "}
                <input name="name" defaultValue={stationEdit?.gate.name} required maxLength={150} />
              </label>
              <label className="workflow-field">
                Loại cổng{" "}
                <select name="type" defaultValue={stationEdit?.gate.type}>
                  <option value="ATTENDANCE_GATE">Chấm công</option>
                  <option value="ZONE_GATE">Kiểm soát khu vực</option>
                </select>
              </label>
              <label className="workflow-field">
                Hướng{" "}
                <select name="direction" defaultValue={stationEdit?.gate.directions[0]}>
                  <option value="ENTRY">Vào</option>
                  <option value="EXIT">Ra</option>
                </select>
              </label>
              <label className="workflow-field">
                Giờ mở{" "}
                <input
                  name="opensAt"
                  type="time"
                  defaultValue={stationEdit?.gate.opensAt?.slice(0, 5)}
                />
              </label>
              <label className="workflow-field">
                Giờ đóng{" "}
                <input
                  name="closesAt"
                  type="time"
                  defaultValue={stationEdit?.gate.closesAt?.slice(0, 5)}
                />
              </label>
              {stationEdit && (
                <label className="workflow-field">
                  Trạng thái{" "}
                  <select name="status" defaultValue={stationEdit.status}>
                    <option value="ACTIVE">Đang hoạt động</option>
                    <option value="DISABLED">Đã tắt</option>
                  </select>
                </label>
              )}
            </div>
            <fieldset>
              <legend>Nhóm được qua cổng</legend>
              <div className="workflow-actions">
                {groups.map((group) => (
                  <label key={group}>
                    <input
                      name="groups"
                      type="checkbox"
                      value={group}
                      defaultChecked={
                        !stationEdit || stationEdit.gate.allowedGroups.includes(group)
                      }
                    />{" "}
                    {groupLabel[group]}
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="workflow-actions">
              <button className="workflow-button" disabled={busy}>
                {stationEdit ? "Lưu cấu hình" : "Đăng ký"}
              </button>
              {stationEdit && (
                <button
                  className="workflow-button secondary"
                  type="button"
                  disabled={busy}
                  onClick={() => setStationEdit(null)}
                >
                  Hủy
                </button>
              )}
            </div>
          </form>
        </>
      )}
    </div>
  );
}
