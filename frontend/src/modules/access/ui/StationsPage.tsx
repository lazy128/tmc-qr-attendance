import { useState, type FormEvent } from "react";
import { configuredEdges } from "../../shared/config/edges";
import { request } from "../../shared/http/client";
import { errorMessage } from "../../shared/lib/error-message";
import { DemoPage } from "../../shared/ui/DemoPage";

type Station = {
  siteId: string;
  stationId: string;
  gateId: string;
  status: "ACTIVE" | "DISABLED";
  lastHeartbeatAt: string | null;
};
type StationList = { stations: Station[]; canManage: boolean };

const endpoint = "/admin/v1/stations";

export function StationsPage() {
  const edges = configuredEdges();
  const [selection, setSelection] = useState(0);
  const [bearer, setBearer] = useState("");
  const [registry, setRegistry] = useState<StationList | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [edit, setEdit] = useState<Station | null>(null);
  const edge = edges[selection] ?? edges[0];
  const headers = { Authorization: `Bearer ${bearer.trim()}` };

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const gateId = String(data.get("gateId")).trim();
    const stationId = String(data.get("stationId")).trim();
    const status = data.get("status");
    const form = event.currentTarget;
    void run(async () => {
      const saved = await request<Station>(
        edit ? `${endpoint}/${encodeURIComponent(edit.stationId)}` : endpoint,
        {
          method: edit ? "PATCH" : "POST",
          headers,
          body: JSON.stringify(
            edit ? { gateId, status } : { siteId: edge.site, stationId, gateId },
          ),
        },
        edge.url,
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
      setEdit(null);
      form.reset();
    });
  }

  return (
    <DemoPage title="Quản lý trạm">
      <div className="grid gap-4">
        <section className="workflow-card grid gap-4">
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              void run(async () => {
                setEdit(null);
                setRegistry(null);
                setRegistry(await request<StationList>(endpoint, { headers }, edge.url));
              });
            }}
          >
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="workflow-field">
                Edge / site
                <select
                  value={selection}
                  disabled={busy}
                  onChange={(event) => {
                    setRegistry(null);
                    setEdit(null);
                    setError("");
                    setSelection(Number(event.target.value));
                  }}
                >
                  {edges.map((item, index) => (
                    <option value={index} key={index}>
                      {item.site} / {item.station}
                    </option>
                  ))}
                </select>
              </label>
              <label className="workflow-field">
                Mã đăng nhập Admin hoặc bảo vệ
                <input
                  type="password"
                  value={bearer}
                  required
                  disabled={busy}
                  onChange={(event) => {
                    setBearer(event.target.value);
                    setRegistry(null);
                    setEdit(null);
                    setError("");
                  }}
                />
              </label>
            </div>
            <button className="workflow-button justify-self-start" disabled={busy}>
              Tải danh sách
            </button>
          </form>
          {error && (
            <p className="workflow-error" role="alert">
              {error}
            </p>
          )}
          {registry && (
            <div className="workflow-table-wrap">
              <table className="workflow-table">
                <thead>
                  <tr>
                    <th scope="col">Site</th>
                    <th scope="col">Trạm</th>
                    <th scope="col">Cổng</th>
                    <th scope="col">Trạng thái</th>
                    <th scope="col">Heartbeat cuối</th>
                    {registry.canManage && <th scope="col">Thao tác</th>}
                  </tr>
                </thead>
                <tbody>
                  {registry.stations.map((station) => (
                    <tr key={station.stationId}>
                      <td>{station.siteId}</td>
                      <td>{station.stationId}</td>
                      <td>{station.gateId}</td>
                      <td>{station.status}</td>
                      <td>
                        {station.lastHeartbeatAt
                          ? new Date(station.lastHeartbeatAt).toLocaleString("vi-VN")
                          : "Chưa có"}
                      </td>
                      {registry.canManage && (
                        <td>
                          <button
                            className="workflow-button secondary"
                            disabled={busy}
                            onClick={() => setEdit(station)}
                          >
                            Sửa
                          </button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
        {registry?.canManage && (
          <form key={edit?.stationId ?? "new"} className="workflow-card grid gap-4" onSubmit={save}>
            <h2>{edit ? `Sửa ${edit.stationId}` : "Đăng ký trạm"}</h2>
            <p>{edge.site}</p>
            {!edit && (
              <label className="workflow-field">
                Trạm <input name="stationId" required maxLength={100} />
              </label>
            )}
            <label className="workflow-field">
              Cổng <input name="gateId" defaultValue={edit?.gateId} required maxLength={100} />
            </label>
            {edit && (
              <label className="workflow-field">
                Trạng thái
                <select name="status" defaultValue={edit.status}>
                  <option value="ACTIVE">ACTIVE</option>
                  <option value="DISABLED">DISABLED</option>
                </select>
              </label>
            )}
            <div className="workflow-actions">
              <button className="workflow-button" disabled={busy}>
                {edit ? "Lưu thay đổi" : "Đăng ký"}
              </button>
              {edit && (
                <button
                  type="button"
                  className="workflow-button secondary"
                  disabled={busy}
                  onClick={() => setEdit(null)}
                >
                  Hủy
                </button>
              )}
            </div>
          </form>
        )}
      </div>
    </DemoPage>
  );
}
