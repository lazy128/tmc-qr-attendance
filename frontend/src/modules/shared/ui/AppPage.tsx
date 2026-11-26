import { Link } from "@tanstack/react-router";
import { useState, type ReactNode } from "react";
import { accountApi, roleLabel } from "../workflow";
import { AccountForm, useAuth } from "./Auth";

export function AppPage({ title, children }: { title: string; children: ReactNode }) {
  const { account, setAccount } = useAuth();
  const [password, setPassword] = useState(false);
  const [error, setError] = useState("");
  async function logout() {
    setError("");
    try {
      await accountApi.logout();
      setAccount(null);
      setPassword(false);
    } catch {
      setError("Chưa thể đăng xuất. Kiểm tra kết nối rồi thử lại.");
    }
  }
  return (
    <div className="workflow-app">
      <header className="workflow-bar">
        <Link to="/" className="workflow-brand">
          TMC <span>Chấm công</span>
        </Link>
        {account && (
          <nav aria-label="Chấm công">
            {!account.changeRequired && (
              <>
                {account.employeeId && <Link to="/employee">Mã QR</Link>}
                {account.role === "GUARD" && <Link to="/gate">Trạm quét</Link>}
                {(account.employeeId || account.role === "HR") && (
                  <Link to="/attendance">Bảng công</Link>
                )}
                {account.role === "ADMIN" && <Link to="/stations">Quản trị</Link>}
                <button className="workflow-text-button" onClick={() => setPassword(true)}>
                  Đổi mật khẩu
                </button>
              </>
            )}
            <button className="workflow-text-button" onClick={logout}>
              Đăng xuất
            </button>
          </nav>
        )}
      </header>
      <main className="workflow-main">
        <h1>{title}</h1>
        {error && (
          <p className="workflow-error" role="alert">
            {error}
          </p>
        )}
        {account && !account.changeRequired && (
          <p className="workflow-subtitle">
            {account.displayName} · {roleLabel[account.role]}
          </p>
        )}
        {password && account ? <AccountForm change cancel={() => setPassword(false)} /> : children}
      </main>
    </div>
  );
}
