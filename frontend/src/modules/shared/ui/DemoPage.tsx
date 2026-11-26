import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

export function DemoPage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="facility-app !block">
      <header className="facility-topbar">
        <Link to="/" className="facility-button">
          TMC FacilityOS
        </Link>
      </header>
      <main className="facility-content !max-w-4xl">
        <h1>{title}</h1>
        {children}
      </main>
    </div>
  );
}
