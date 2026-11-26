import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

export function DemoPage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="workflow-app">
      <header className="workflow-bar">
        <Link to="/" className="workflow-brand">
          TMC <span>Chấm công</span>
        </Link>
        <nav aria-label="Luồng chấm công">
          <Link to="/employee">Nhân viên</Link>
          <Link to="/gate">Cổng quét</Link>
          <Link to="/attendance">Bảng công</Link>
        </nav>
      </header>
      <main className="workflow-main">
        <h1>{title}</h1>
        {children}
      </main>
    </div>
  );
}
