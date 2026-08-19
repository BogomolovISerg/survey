import { useEffect, useState } from "react";
import QRCode from "qrcode";

/** QR-код как inline SVG (рисуется на устройстве, без сети). */
export function QrCode({ value, label }: { value: string; label?: string }) {
  const [svg, setSvg] = useState("");
  useEffect(() => {
    let alive = true;
    QRCode.toString(value, { type: "svg", errorCorrectionLevel: "M", margin: 0 })
      .then((s) => alive && setSvg(s))
      .catch(() => alive && setSvg(""));
    return () => {
      alive = false;
    };
  }, [value]);
  if (!svg) return <div className="qr muted small">Готовим QR-код…</div>;
  return <div className="qr" role="img" aria-label={label ?? "QR-код"} dangerouslySetInnerHTML={{ __html: svg }} />;
}
