import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";

export function QrCanvas({ payload, label }: { payload: string; label: string }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const element = canvas.current;
    if (!element) return;
    let active = true;
    let observer: ResizeObserver | undefined;
    const renderScale = 6;
    QRCode.toCanvas(element, payload, {
      scale: renderScale,
      margin: 4,
      errorCorrectionLevel: "M",
    })
      .then(() => {
        const container = element.parentElement;
        if (!active || !container || typeof ResizeObserver === "undefined") return;
        const modules = element.width / renderScale;
        observer = new ResizeObserver(([entry]) => {
          if (!entry) return;
          // Give every QR module an equal number of screen pixels.
          const pixelsPerModule = Math.max(1, Math.floor(entry.contentRect.width / modules));
          element.style.width = `${pixelsPerModule * modules}px`;
        });
        observer.observe(container);
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
      observer?.disconnect();
    };
  }, [payload]);
  if (failed) return <p role="alert">Không thể hiển thị mã QR.</p>;
  return <canvas ref={canvas} role="img" aria-label={label} />;
}
