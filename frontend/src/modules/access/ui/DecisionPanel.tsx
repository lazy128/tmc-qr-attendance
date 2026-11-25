import { Activity } from "lucide-react";
import type { VerifyResult } from "../model/types";

export function DecisionPanel({ result }: { result: VerifyResult | null }) {
  return (
    <section className="rounded-2xl border bg-white p-6 shadow-sm">
      <div className="mb-5 flex gap-3">
        <Activity className="text-emerald-700" />
        <div>
          <h2 className="font-semibold">Latest decision</h2>
          <p className="text-sm text-slate-500">Decision, command and passage are independent.</p>
        </div>
      </div>
      {!result ? (
        <div className="grid min-h-64 place-items-center rounded-xl border border-dashed text-center text-sm text-slate-400">
          No scan submitted
        </div>
      ) : (
        <div className="space-y-4">
          <div className={`rounded-xl p-4 ${result.decision === "ALLOW" ? "bg-emerald-50" : "bg-rose-50"}`}>
            <b>
              {result.decision}
              {result.reasonCode ? ` · ${result.reasonCode}` : ""}
            </b>
            <p className="mt-1 text-sm">{result.kioskMessage}</p>
          </div>
          <div className="grid grid-cols-3 gap-2">
            {[
              ["Command", result.command],
              ["Passage", result.passage],
              ["Evidence", result.evidence ?? "—"],
            ].map(([label, value]) => (
              <div key={label} className="rounded-lg bg-slate-50 p-3">
                <p className="text-[11px] text-slate-500">{label}</p>
                <b className="text-xs">{value}</b>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap gap-1">
            {result.steps?.map((step, index) => (
              <span key={index} className="rounded bg-slate-100 px-2 py-1 font-mono text-[10px]">
                {step}
              </span>
            ))}
          </div>
          <p className="break-all font-mono text-xs text-slate-500">Event ID {result.eventId}</p>
        </div>
      )}
    </section>
  );
}
