import { useEffect, useEffectEvent, useRef } from "react";
import { readQr } from "../lib/read-qr";

type Detector = { detect(source: HTMLVideoElement): Promise<{ rawValue: string }[]> };

export function QrCamera({
  onDecode,
  onFailure,
  paused,
  ignoreCode,
}: {
  onDecode: (code: string) => void;
  onFailure: (message: string) => void;
  paused: boolean;
  ignoreCode?: (code: string) => boolean;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canRead = useEffectEvent(() => !paused);
  const decode = useEffectEvent((code: string) => {
    if (!paused && !ignoreCode?.(code)) onDecode(code);
  });
  const fail = useEffectEvent(onFailure);

  useEffect(() => {
    const video = videoRef.current;
    const context = document.createElement("canvas").getContext("2d", { willReadFrequently: true });
    if (!video || !context || !navigator.mediaDevices?.getUserMedia) {
      fail("Không mở được camera trực tiếp. Hãy chụp hoặc chọn ảnh QR.");
      return;
    }

    let active = true;
    let stream: MediaStream | undefined;
    let frame = 0;
    let lastRead = 0;
    let detector: Detector | undefined;
    const NativeDetector = (
      globalThis as typeof globalThis & {
        BarcodeDetector?: new (options: { formats: string[] }) => Detector;
      }
    ).BarcodeDetector;
    try {
      if (NativeDetector) detector = new NativeDetector({ formats: ["qr_code"] });
    } catch {
      // The browser does not support native QR detection.
    }

    function stop() {
      active = false;
      cancelAnimationFrame(frame);
      stream?.getTracks().forEach((track) => track.stop());
    }

    async function scan(time: number) {
      if (!active) return;
      if (
        canRead() &&
        video &&
        context &&
        video.videoWidth &&
        video.readyState >= 2 &&
        time - lastRead >= 125
      ) {
        lastRead = time;
        let code: string | undefined;
        try {
          code = (await detector?.detect(video))?.[0]?.rawValue;
        } catch {
          detector = undefined;
        }
        if (!active) return;
        try {
          if (!code) code = readQr(context, video, video.videoWidth, video.videoHeight);
        } catch {
          stop();
          fail("Không đọc được hình từ camera. Hãy mở lại camera hoặc chọn ảnh QR.");
          return;
        }
        if (code) decode(code);
      }
      frame = requestAnimationFrame(scan);
    }

    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: { ideal: "environment" } }, audio: false })
      .then(async (camera) => {
        if (!active) {
          camera.getTracks().forEach((track) => track.stop());
          return;
        }
        stream = camera;
        video.srcObject = camera;
        await video.play();
        if (active) frame = requestAnimationFrame(scan);
      })
      .catch((error: unknown) => {
        if (!active) return;
        stop();
        const name = error instanceof Error ? error.name : "";
        fail(
          name === "NotAllowedError"
            ? "Quyền camera bị từ chối. Hãy cấp quyền hoặc dùng ảnh QR."
            : name === "NotFoundError"
              ? "Không tìm thấy camera. Bạn có thể chọn ảnh QR."
              : "Không mở được camera. Kiểm tra kết nối hoặc ứng dụng đang dùng camera.",
        );
      });

    return stop;
  }, []);

  return <video ref={videoRef} aria-label="Camera quét QR" playsInline muted />;
}
