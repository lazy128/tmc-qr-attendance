import { useEffect, useState } from "react";
import { Download } from "lucide-react";
import { errorMessage } from "../../shared/lib/error-message";
import { businessZone, workflow, type PayrollHistory } from "../../shared/workflow";

const exportedAt = new Intl.DateTimeFormat("vi-VN", {
  dateStyle: "short",
  timeStyle: "short",
  timeZone: businessZone,
});

export function PayrollBatchesPanel({
  month,
  revision,
  saveFile,
}: {
  month: string;
  revision: number;
  saveFile: (file: Blob, name: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [history, setHistory] = useState<PayrollHistory | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    void workflow
      .payrollHistory(month, controller.signal)
      .then((value) => {
        if (controller.signal.aborted) return;
        setHistory(value);
        setError("");
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      });
    return () => controller.abort();
  }, [month, open, refresh, revision]);

  async function operate(action: () => Promise<void>) {
    if (busy) return;
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

  function capture() {
    void operate(async () => {
      await workflow.capturePayroll(month);
      setRefresh((value) => value + 1);
    });
  }

  function download(number: number) {
    void operate(async () => {
      const file = await workflow.downloadPayroll(month, number);
      saveFile(file, `payroll-${month}-${number}.csv`);
    });
  }

  const next = history?.batches.length ? "Tạo lô bổ sung" : "Chốt lô đầu tiên";

  return (
    <details
      className="workflow-card grid gap-3"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="cursor-pointer font-semibold">Các lô CSV cho lương</summary>
      {open && (
        <div className="mt-3 grid gap-3">
          <p>Lô lương áp dụng cho cả Bình Dương và Long An. Các lô đã chốt có thể tải lại.</p>
          <div className="workflow-actions">
            <button
              className="workflow-button"
              type="button"
              onClick={capture}
              disabled={busy || !history?.pendingRows}
            >
              {next}
            </button>
            <button
              className="workflow-button secondary"
              type="button"
              disabled={busy}
              onClick={() => setRefresh((value) => value + 1)}
            >
              Cập nhật lô lương
            </button>
          </div>
          {error && (
            <p className="workflow-error" role="alert">
              {error}
            </p>
          )}
          {!history && !error && <p>Đang tải lịch sử lô lương…</p>}
          {history && (
            <p>
              {history.pendingRows
                ? `${history.pendingRows} dòng chờ xuất`
                : "Chưa có dữ liệu mới."}
            </p>
          )}
          {history && history.batches.length > 0 && (
            <ul className="grid gap-2">
              {history.batches.map((batch) => (
                <li className="workflow-actions border-b pb-2" key={batch.number}>
                  <span>
                    {batch.number === 1 ? "Lô gốc" : "Lô bổ sung"} {batch.number}
                    {" · "}
                    {batch.rows} dòng{" · "}
                    {exportedAt.format(new Date(batch.createdAt))}
                  </span>
                  <button
                    className="workflow-button secondary"
                    type="button"
                    disabled={busy}
                    onClick={() => download(batch.number)}
                  >
                    <Download size={16} /> Tải CSV lô {batch.number}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </details>
  );
}
