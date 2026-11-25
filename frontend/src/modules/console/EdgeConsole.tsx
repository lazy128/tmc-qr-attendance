import { CircleAlert, ShieldCheck } from "lucide-react";
import { useSyncExternalStore } from "react";
import { AccessWorkspace } from "../access/ui/AccessWorkspace";
import { AttendanceWorkspace } from "../attendance/ui/AttendanceWorkspace";
import { API_BASE } from "../shared/config/api-base";
import { useEdgeConsole } from "./use-edge-console";

const subscribeToHydration = () => () => {};
const clientIsReady = () => true;
const serverIsReady = () => false;

export function EdgeConsole() {
  const ready = useSyncExternalStore(subscribeToHydration, clientIsReady, serverIsReady);
  const consoleState = useEdgeConsole();

  return (
    <main className="min-h-screen bg-[#f5f7f6] text-slate-900">
      <header className="border-b bg-white">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="grid size-10 place-items-center rounded-xl bg-emerald-700 text-white">
              <ShieldCheck />
            </div>
            <div>
              <b className="text-sm tracking-wide">TMC MANUFACTURING</b>
              <p className="text-xs text-slate-500">Access & attendance · Edge console</p>
            </div>
          </div>
          <span className="rounded-full bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-800">● Edge demo</span>
        </div>
      </header>
      <div className="mx-auto max-w-7xl px-6 py-8">
        <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-xs font-bold uppercase tracking-[.18em] text-emerald-700">Factory operations</p>
            <h1 className="mt-2 text-3xl font-semibold">Gate activity</h1>
            <p className="mt-1 text-sm text-slate-500">Scans go to the local Edge Agent. Central sync is asynchronous.</p>
          </div>
          <div className="rounded-lg border bg-white px-3 py-2 font-mono text-xs text-slate-500">API {API_BASE}</div>
        </div>
        <nav className="mb-6 flex gap-5 border-b">
          {(
            [
              ["access", "Access check"],
              ["attendance", "Attendance records"],
            ] as const
          ).map(([id, label]) => (
            <button
              key={id}
              disabled={!ready}
              onClick={() => consoleState.setTab(id)}
              className={`border-b-2 px-2 py-3 text-sm ${
                consoleState.tab === id
                  ? "border-emerald-700 font-semibold text-emerald-800"
                  : "border-transparent text-slate-500"
              }`}
            >
              {label}
            </button>
          ))}
        </nav>
        {consoleState.error && (
          <div role="alert" className="mb-5 flex gap-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">
            <CircleAlert size={17} />
            {consoleState.error}
          </div>
        )}
        <fieldset disabled={!ready} className="min-w-0">
          {consoleState.tab === "access" ? (
            <AccessWorkspace
              site={consoleState.site}
              station={consoleState.station}
              credential={consoleState.credential}
              device={consoleState.device}
              token={consoleState.token}
              direction={consoleState.direction}
              busy={consoleState.busy}
              result={consoleState.result}
              onSite={consoleState.setSite}
              onStation={consoleState.setStation}
              onCredential={consoleState.setCredential}
              onDevice={consoleState.setDevice}
              onToken={consoleState.setToken}
              onDirection={consoleState.setDirection}
              onIssue={consoleState.issueQr}
              onRevoke={consoleState.revokeQr}
              onVerify={consoleState.verify}
            />
          ) : (
            <AttendanceWorkspace
              rows={consoleState.rows}
              busy={consoleState.busy}
              token={consoleState.attendanceToken}
              onToken={consoleState.updateAttendanceToken}
              onRefresh={consoleState.refreshAttendance}
              onExport={consoleState.exportAttendance}
            />
          )}
        </fieldset>
        <footer className="mt-8 text-xs text-slate-400">
          Demo station auth, keys, Turnstile and some storage are simulations. Do not use production data.
        </footer>
      </div>
    </main>
  );
}
