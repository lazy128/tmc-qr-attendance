import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import { accountApi, roleLabel, type Account, type Role } from "../workflow";
import { ApiRequestError } from "../http/client";
import { errorMessage } from "../lib/error-message";

type Authentication = {
  account: Account | null;
  setAccount: (account: Account | null) => void;
  loading: boolean;
  failure: string;
  refresh: () => void;
};
const AuthContext = createContext<Authentication | null>(null);
const sessionRevision = "tmc.session.revision";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<Account | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState("");
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => {
    setAccount(null);
    setLoading(true);
    setFailure("");
    setRevision((value) => value + 1);
  }, []);
  function updateAccount(user: Account | null) {
    setAccount(user);
    try {
      window.localStorage.setItem(sessionRevision, String(Math.random()));
    } catch {
      /* Pages still check the QR owner if storage is unavailable. */
    }
  }
  useEffect(() => {
    const expire = () => setAccount(null);
    const onStorage = (event: StorageEvent) => {
      if (event.key === sessionRevision) refresh();
    };
    window.addEventListener("tmc-session-expired", expire);
    window.addEventListener("tmc-session-changed", refresh);
    window.addEventListener("storage", onStorage);
    return () => {
      window.removeEventListener("tmc-session-expired", expire);
      window.removeEventListener("tmc-session-changed", refresh);
      window.removeEventListener("storage", onStorage);
    };
  }, [refresh]);
  useEffect(() => {
    const controller = new AbortController();
    void accountApi
      .me(controller.signal)
      .then((user) => {
        if (!controller.signal.aborted) setAccount(user);
      })
      .catch((cause) => {
        if (controller.signal.aborted) return;
        if (!(cause instanceof ApiRequestError && cause.status === 401))
          setFailure("Không kết nối được máy chủ đăng nhập.");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [revision]);
  return (
    <AuthContext.Provider value={{ account, setAccount: updateAccount, loading, failure, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("AuthProvider is required");
  return value;
}

export function RequireAccount({
  roles,
  loginHint = "",
  children,
}: {
  roles?: Role[];
  loginHint?: string;
  children: (account: Account) => ReactNode;
}) {
  const { account, loading, failure, refresh } = useAuth();
  if (loading) return <p role="status">Đang kiểm tra phiên đăng nhập…</p>;
  if (failure)
    return (
      <section className="workflow-card workflow-login">
        <p role="alert">{failure}</p>
        <button className="workflow-button" onClick={refresh}>
          Thử lại
        </button>
      </section>
    );
  if (!account || account.changeRequired)
    return (
      <AccountForm key={account?.username ?? "login"} change={Boolean(account)} hint={loginHint} />
    );
  if (roles && !roles.includes(account.role))
    return (
      <section className="workflow-card workflow-login">
        <p role="alert">
          Trang này dành cho {roles.map((role) => roleLabel[role]).join(", ")}. Bạn đang đăng nhập
          bằng tài khoản {roleLabel[account.role]}.
        </p>
      </section>
    );
  return children(account);
}

export function AccountForm({
  change,
  hint,
  cancel,
}: {
  change: boolean;
  hint?: string;
  cancel?: () => void;
}) {
  const { setAccount } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const form = new FormData(event.currentTarget);
    const value = (key: string) => String(form.get(key) ?? "");
    setBusy(true);
    setError("");
    try {
      if (change) {
        if (value("replacement") !== value("confirm"))
          throw new Error("Hai mật khẩu mới chưa trùng nhau.");
        await accountApi.password(value("password"), value("replacement"));
        cancel?.();
        setAccount(null);
      } else {
        setAccount(await accountApi.login(value("username"), value("password")));
      }
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="workflow-card workflow-login" onSubmit={submit}>
      <h2>{change ? "Đổi mật khẩu" : "Đăng nhập"}</h2>
      {!change && hint && <p>{hint}</p>}
      {change ? (
        <p>Đặt mật khẩu riêng và đăng nhập lại để tiếp tục.</p>
      ) : (
        <label className="workflow-field">
          Tên đăng nhập <input name="username" autoComplete="username" maxLength={60} required />
        </label>
      )}
      <label className="workflow-field">
        {change ? "Mật khẩu hiện tại" : "Mật khẩu"}
        <input
          name="password"
          type="password"
          autoComplete="current-password"
          maxLength={72}
          required
        />
      </label>
      {change && (
        <>
          <label className="workflow-field">
            Mật khẩu mới{" "}
            <input
              name="replacement"
              type="password"
              autoComplete="new-password"
              minLength={12}
              maxLength={72}
              required
            />
          </label>
          <label className="workflow-field">
            Nhập lại mật khẩu mới{" "}
            <input
              name="confirm"
              type="password"
              autoComplete="new-password"
              minLength={12}
              maxLength={72}
              required
            />
          </label>
        </>
      )}
      {error && (
        <p className="workflow-error" role="alert">
          {error}
        </p>
      )}
      <div className="workflow-actions">
        <button className="workflow-button" disabled={busy}>
          {busy ? "Đang xử lý…" : change ? "Lưu mật khẩu" : "Đăng nhập"}
        </button>
        {cancel && (
          <button
            type="button"
            className="workflow-button secondary"
            onClick={cancel}
            disabled={busy}
          >
            Hủy
          </button>
        )}
      </div>
    </form>
  );
}
