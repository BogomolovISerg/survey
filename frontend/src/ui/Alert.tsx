import type { ReactNode } from "react";

export function Alert({ kind = "info", children }: { kind?: "info" | "error" | "ok"; children: ReactNode }) {
  return (
    <div className={`alert ${kind}`} role={kind === "error" ? "alert" : "status"}>
      {children}
    </div>
  );
}
