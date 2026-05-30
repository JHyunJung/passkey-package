import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';

export function QrCode({ value, size = 176 }: { value: string; size?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (!ref.current) return;
    QRCode.toCanvas(ref.current, value, { width: size, margin: 1 }, (err) => {
      if (err) console.error('QR render failed', err);
    });
  }, [value, size]);
  return <canvas ref={ref} width={size} height={size} style={{ borderRadius: 6, background: '#fff', padding: 8 }} />;
}
