import { ReactNode, useEffect, useRef, useState } from 'react';
import { Dialog } from './Dialog';
import { Button } from './Button';
import { Alert } from './Alert';
import { ConfirmByTyping } from './ConfirmByTyping';
import { describeError } from '@/lib/describeError';

export type ConfirmLevel = 'L1' | 'L2' | 'L3';
export type ConfirmDialogProps = {
  open: boolean; onClose: () => void; level: ConfirmLevel;
  title: string; verb: string; sub?: ReactNode; children?: ReactNode; danger?: boolean;
  typeToConfirm?: { expected: string; label: ReactNode };
  acknowledge?: string;
  onConfirm: () => Promise<void>;
};

/** 위험 동작 확인 3등급(V3 가이드). 기본 포커스는 취소, 실패는 다이얼로그 안에서 보여 준다. */
export function ConfirmDialog({ open, onClose, level, title, verb, sub, children, danger = true, typeToConfirm, acknowledge, onConfirm }: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [typed, setTyped] = useState(false);
  const [acked, setAcked] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => { if (open) { setTyped(false); setAcked(false); setError(null); busyRef.current = false; setBusy(false); } }, [open]);

  const gateOk = level !== 'L3' || (typeToConfirm ? typed : acknowledge ? acked : true);

  async function confirm() {
    if (busyRef.current || !gateOk) return;
    busyRef.current = true; setBusy(true); setError(null);
    try { await onConfirm(); onClose(); }
    catch (e) { setError(e); }
    finally { busyRef.current = false; setBusy(false); }
  }

  const info = error ? describeError(error) : null;
  return (
    <Dialog open={open} onClose={busy ? () => {} : onClose} dismissible={!busy} title={title} sub={sub} role="alertdialog" initialFocusRef={cancelRef} closeOnScrim={false}
      footer={<>
        <Button ref={cancelRef} onClick={onClose} disabled={busy}>취소</Button>
        <Button variant={danger ? 'danger-solid' : 'primary'} onClick={() => void confirm()} disabled={busy || !gateOk}>{busy ? `${verb} 중…` : verb}</Button>
      </>}>
      {info && <Alert tone="bad" title={`${verb}하지 못했습니다`}>{info.title}. {info.detail}</Alert>}
      {children}
      {level === 'L3' && typeToConfirm && <ConfirmByTyping expected={typeToConfirm.expected} label={typeToConfirm.label} onMatchChange={setTyped} actionLabel={verb} />}
      {level === 'L3' && acknowledge && (
        <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
          <input type="checkbox" checked={acked} onChange={(e) => setAcked(e.target.checked)} />{acknowledge}
        </label>
      )}
    </Dialog>
  );
}
