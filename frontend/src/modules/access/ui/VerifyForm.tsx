import { ArrowDownToLine, ArrowUpFromLine, ScanLine } from "lucide-react";
import { TextField } from "../../shared/ui/TextField";

type VerifyFormProps = {
  site: string;
  station: string;
  device: string;
  token: string;
  direction: "ENTRY" | "EXIT";
  busy: boolean;
  onSite: (value: string) => void;
  onStation: (value: string) => void;
  onDevice: (value: string) => void;
  onToken: (value: string) => void;
  onDirection: (value: "ENTRY" | "EXIT") => void;
  onVerify: (token: string) => void;
};

export function VerifyForm(props: VerifyFormProps) {
  return (
    <form
      className="facility-card facility-scan-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (!props.busy && props.token.trim()) props.onVerify(props.token);
      }}
    >
      <h2>Cấu hình lượt quét</h2>
      <div className="facility-field-grid">
        <TextField label="Site ID" value={props.site} onChange={props.onSite} />
        <TextField label="Station ID" value={props.station} onChange={props.onStation} />
        <TextField label="Thiết bị" value={props.device} onChange={props.onDevice} />
        <div>
          <span className="facility-field-caption">Chiều qua cổng</span>
          <div className="facility-directions">
            <button
              type="button"
              aria-pressed={props.direction === "ENTRY"}
              onClick={() => props.onDirection("ENTRY")}
            >
              <ArrowDownToLine size={15} /> Vào
            </button>
            <button
              type="button"
              aria-pressed={props.direction === "EXIT"}
              onClick={() => props.onDirection("EXIT")}
            >
              <ArrowUpFromLine size={15} /> Ra
            </button>
          </div>
        </div>
      </div>
      <label className="facility-token-field">
        Token từ máy quét
        <input
          value={props.token}
          onChange={(event) => props.onToken(event.target.value)}
          placeholder="Quét bằng đầu đọc USB hoặc dán token"
          autoComplete="off"
          spellCheck={false}
          maxLength={4096}
        />
      </label>
      <button
        type="submit"
        disabled={props.busy || !props.token.trim()}
        className="facility-button facility-button--primary"
      >
        <ScanLine size={16} /> {props.busy ? "Đang xác minh…" : "Xác minh tại cổng"}
      </button>
    </form>
  );
}
