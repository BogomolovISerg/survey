import { useEffect, useRef, useState } from "react";
import { BrowserMultiFormatReader, type IScannerControls } from "@zxing/browser";
import { BarcodeFormat, DecodeHintType } from "@zxing/library";

/** Минимальные типы нативного BarcodeDetector (Chrome/Android; в Safari отсутствует — тогда ZXing). */
interface DetectedBarcode { rawValue: string }
interface BarcodeDetectorLike { detect(source: CanvasImageSource): Promise<DetectedBarcode[]> }
declare global {
  interface Window {
    BarcodeDetector?: new (opts?: { formats?: string[] }) => BarcodeDetectorLike;
  }
}

/** qr — только QR (код посетителя); any — QR + DataMatrix (маркировка Честный знак) + линейные штрихкоды (код подарка). */
export type ScanFormats = "qr" | "any";

interface Props {
  formats?: ScanFormats;
  hint?: string;
  /** вызывается один раз при первом распознанном значении */
  onScan: (text: string) => void;
  onClose: () => void;
}

const NATIVE_FORMATS: Record<ScanFormats, string[]> = {
  qr: ["qr_code"],
  any: ["qr_code", "data_matrix", "code_128", "ean_13", "ean_8", "code_39"],
};
const ZXING_FORMATS: Record<ScanFormats, BarcodeFormat[]> = {
  qr: [BarcodeFormat.QR_CODE],
  any: [BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.CODE_128, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.CODE_39],
};

/**
 * Сканер кодов прямо в приложении: задняя камера через getUserMedia, распознавание на устройстве
 * (нативный BarcodeDetector, иначе ZXing). Работает только по HTTPS (или localhost).
 */
export function QrScanner({ formats = "qr", hint, onScan, onClose }: Props) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState("");
  const doneRef = useRef(false);

  useEffect(() => {
    let stream: MediaStream | null = null;
    let raf = 0;
    let controls: IScannerControls | null = null;
    let alive = true;
    const video = videoRef.current;
    if (!video) return;

    const finish = (text: string) => {
      if (doneRef.current || !text) return;
      doneRef.current = true;
      if (navigator.vibrate) navigator.vibrate(60);
      onScan(text);
    };

    const start = async () => {
      if (!navigator.mediaDevices?.getUserMedia) {
        setError("Этот браузер не даёт доступ к камере. Откройте страницу по https или введите код вручную.");
        return;
      }
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: "environment" }, width: { ideal: 1280 }, height: { ideal: 720 } },
          audio: false,
        });
      } catch (e) {
        const name = (e as DOMException).name;
        setError(
          name === "NotAllowedError"
            ? "Доступ к камере запрещён. Разрешите камеру для этого сайта в настройках браузера."
            : name === "NotFoundError"
              ? "Камера не найдена."
              : "Не удалось открыть камеру: " + name,
        );
        return;
      }
      if (!alive) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }

      if (window.BarcodeDetector) {
        video.srcObject = stream;
        await video.play().catch(() => undefined);
        const detector = new window.BarcodeDetector({ formats: NATIVE_FORMATS[formats] });
        const tick = async () => {
          if (!alive || doneRef.current) return;
          if (video.readyState >= 2) {
            try {
              const codes = await detector.detect(video);
              if (codes.length > 0 && codes[0].rawValue) {
                finish(codes[0].rawValue);
                return;
              }
            } catch {
              /* кадр не готов — пробуем дальше */
            }
          }
          raf = window.requestAnimationFrame(tick);
        };
        raf = window.requestAnimationFrame(tick);
      } else {
        const hints = new Map();
        hints.set(DecodeHintType.POSSIBLE_FORMATS, ZXING_FORMATS[formats]);
        hints.set(DecodeHintType.TRY_HARDER, true);
        const reader = new BrowserMultiFormatReader(hints, { delayBetweenScanAttempts: 150 });
        try {
          controls = await reader.decodeFromStream(stream, video, (result) => {
            if (result && alive && !doneRef.current) {
              controls?.stop();
              finish(result.getText());
            }
          });
        } catch (e) {
          setError("Не удалось запустить распознавание: " + String(e));
        }
      }
    };

    void start();
    return () => {
      alive = false;
      if (raf) window.cancelAnimationFrame(raf);
      if (controls) controls.stop();
      if (stream) stream.getTracks().forEach((t) => t.stop());
      if (video) video.srcObject = null;
    };
  }, [onScan, formats]);

  return (
    <div className="scanner">
      <div className="scanner-view">
        <video ref={videoRef} playsInline muted autoPlay />
        <div className="scanner-frame" aria-hidden="true" />
      </div>
      {error ? (
        <div className="alert error small">{error}</div>
      ) : (
        <p className="muted small center">{hint ?? "Наведите камеру на код."}</p>
      )}
      <button type="button" className="secondary block" onClick={onClose}>Закрыть камеру</button>
    </div>
  );
}
