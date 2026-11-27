import { useEffect, useState, type FormEvent } from "react";
import { errorMessage } from "../../shared/lib/error-message";
import { businessZone, siteName, workflow, type GateIncident } from "../../shared/workflow";

const incidentTime = new Intl.DateTimeFormat("vi-VN", {
  dateStyle: "short",
  timeStyle: "short",
  timeZone: businessZone,
});

export function GateIncidentQueue() {
  const [open, setOpen] = useState(false);
  const [revision, setRevision] = useState(0);
  const [rows, setRows] = useState<GateIncident[] | null>(null);
  const [canReview, setCanReview] = useState(false);
  const [selected, setSelected] = useState<GateIncident | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    void workflow
      .gateIncidents(controller.signal)
      .then((data) => {
        if (controller.signal.aborted) return;
        setRows(data.incidents);
        setCanReview(data.canReview);
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      });
    return () => controller.abort();
  }, [open, revision]);

  async function review(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || busy) return;
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await workflow.reviewGateIncident(
        selected.eventId,
        form.get("resolution") === "passed",
        String(form.get("note") ?? "").trim(),
      );
      setRows((value) => value?.filter((row) => row.eventId !== selected.eventId) ?? null);
      setSelected(null);
      setNotice("Đã lưu kết quả đối soát.");
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <details
      className="workflow-card grid gap-3"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="cursor-pointer font-semibold">Lượt cổng cần đối soát</summary>
      {open && (
        <div className="mt-3 grid gap-3">
          <button
            className="workflow-button secondary w-fit"
            type="button"
            onClick={() => {
              setError("");
              setRevision((value) => value + 1);
            }}
          >
            Cập nhật danh sách cổng
          </button>
          {error && (
            <p className="workflow-error" role="alert">
              {error}
            </p>
          )}
          {notice && <p role="status">{notice}</p>}
          {!rows && !error && <p>Đang tải lượt cần đối soát…</p>}
          {rows?.length === 0 && <p>Không có lượt cần đối soát.</p>}
          {rows && rows.length > 0 && (
            <ul className="grid gap-2">
              {rows.map((row) => (
                <li key={row.eventId} className="grid gap-1 border-b pb-2">
                  <strong>
                    {row.employeeName} · {row.employeeId}
                  </strong>
                  <p>
                    {row.direction === "ENTRY" ? "Vào" : "Ra"} ·{" "}
                    {incidentTime.format(new Date(row.scannedAt))}
                    {" · "}
                    {siteName(row.siteId)} · {row.stationId}
                  </p>
                  {canReview && (
                    <button
                      className="workflow-button secondary w-fit"
                      type="button"
                      onClick={() => {
                        setSelected(row);
                        setNotice("");
                      }}
                      disabled={busy}
                    >
                      Đối soát
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {selected && (
            <form
              key={selected.eventId}
              className="workflow-correction"
              onSubmit={(event) => void review(event)}
            >
              <strong>Đối soát lượt của {selected.employeeName}</strong>
              <label className="workflow-field">
                Kết quả
                <select name="resolution" required defaultValue="">
                  <option value="" disabled>
                    Chọn kết quả đã xác minh
                  </option>
                  <option value="passed">Đã qua cổng · Ghi giờ công nếu là cổng chấm công</option>
                  <option value="no-passage">Chưa qua cổng</option>
                </select>
              </label>
              <label className="workflow-field">
                Lý do đối soát
                <textarea name="note" rows={2} required maxLength={300} />
              </label>
              <div className="workflow-actions">
                <button className="workflow-button" disabled={busy}>
                  Lưu đối soát
                </button>
                <button
                  className="workflow-button secondary"
                  type="button"
                  disabled={busy}
                  onClick={() => setSelected(null)}
                >
                  Hủy
                </button>
              </div>
            </form>
          )}
        </div>
      )}
    </details>
  );
}
