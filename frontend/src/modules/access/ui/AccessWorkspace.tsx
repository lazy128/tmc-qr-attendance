import type { VerifyResult } from "../model/types";
import { DecisionPanel } from "./DecisionPanel";
import { VerifyForm } from "./VerifyForm";

type AccessWorkspaceProps = {
  site: string;
  station: string;
  credential: string;
  device: string;
  token: string;
  direction: "ENTRY" | "EXIT";
  busy: boolean;
  result: VerifyResult | null;
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

export function AccessWorkspace(props: AccessWorkspaceProps) {
  return (
    <div className="grid gap-6 lg:grid-cols-[1.1fr_.9fr]">
      <VerifyForm {...props} />
      <DecisionPanel result={props.result} />
    </div>
  );
}
