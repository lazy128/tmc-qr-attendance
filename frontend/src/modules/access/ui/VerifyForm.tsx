import { ArrowDownToLine, ArrowUpFromLine, Fingerprint } from "lucide-react";
import { TextField } from "../../shared/ui/TextField";

type VerifyFormProps = {
  site: string;
  station: string;
  credential: string;
  device: string;
  token: string;
  direction: "ENTRY" | "EXIT";
  busy: boolean;
  onSite: (value: string) => void;
  onStation: (value: string) => void;
  onCredential: (value: string) => void;
  onDevice: (value: string) => void;
  onToken: (value: string) => void;
  onDirection: (value: "ENTRY" | "EXIT") => void;
  onIssue: () => void;
  onRevoke: () => void;
  onVerify: () => void;
};

export function VerifyForm(props: VerifyFormProps) {
  return (
    <section className="rounded-2xl border bg-white p-6 shadow-sm">
      <div className="mb-5 flex gap-3">
        <Fingerprint className="text-emerald-700" />
        <div>
          <h2 className="font-semibold">Verify employee QR</h2>
          <p className="text-sm text-slate-500">Issue a short-lived token, then simulate a station scan.</p>
        </div>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Site ID" value={props.site} onChange={props.onSite} />
        <TextField label="Station ID" value={props.station} onChange={props.onStation} />
        <TextField label="Credential ID" value={props.credential} onChange={props.onCredential} />
        <TextField label="Device binding" value={props.device} onChange={props.onDevice} />
      </div>
      <div className="mt-5 flex flex-wrap gap-2">
        <button
          disabled={props.busy}
          onClick={props.onIssue}
          className="rounded-lg bg-slate-900 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
        >
          {props.busy ? "Working…" : "Issue QR token"}
        </button>
        <button
          disabled={props.busy}
          onClick={props.onRevoke}
          className="rounded-lg border border-rose-200 px-3 py-2 text-xs text-rose-700"
        >
          Revoke QR
        </button>
        <button
          onClick={() => props.onDirection("ENTRY")}
          className={`flex gap-1 rounded-lg border px-3 py-2 text-xs ${props.direction === "ENTRY" ? "bg-emerald-50 text-emerald-800" : ""}`}
        >
          <ArrowDownToLine size={15} />
          Entry
        </button>
        <button
          onClick={() => props.onDirection("EXIT")}
          className={`flex gap-1 rounded-lg border px-3 py-2 text-xs ${props.direction === "EXIT" ? "bg-emerald-50 text-emerald-800" : ""}`}
        >
          <ArrowUpFromLine size={15} />
          Exit
        </button>
      </div>
      <label className="mt-5 block text-xs font-semibold text-slate-600">
        Signed QR token
        <textarea
          rows={3}
          value={props.token}
          onChange={(event) => props.onToken(event.target.value)}
          className="mt-2 w-full rounded-lg border bg-slate-50 p-3 font-mono text-xs"
          placeholder="Issue or paste a signed token"
        />
      </label>
      <button
        disabled={props.busy || !props.token}
        onClick={props.onVerify}
        className="mt-4 w-full rounded-lg bg-emerald-700 py-3 text-sm font-semibold text-white disabled:opacity-50"
      >
        Verify at gate
      </button>
    </section>
  );
}
