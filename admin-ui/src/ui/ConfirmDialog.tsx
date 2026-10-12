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
  // 실패 여부와 오류 값을 분리한다. reject(undefined) 같은 falsy 거부도 인라인 오류로 보여야 한다.
  const [failure, setFailure] = useState<{ error: unknown } | null>(null);
  const expected = typeToConfirm?.expected;

  useEffect(() => { if (open) { setTyped(false); setAcked(false); setFailure(null); busyRef.current = false; setBusy(false); } }, [open]);
  // 확인 문자열이 바뀌면 이전 입력으로 얻은 일치 상태를 버린다(ConfirmByTyping 도 key 로 다시 마운트).
  useEffect(() => { setTyped(false); }, [expected]);

  const gateOk = level !== 'L3' || (typeToConfirm ? typed : acknowledge ? acked : true);

  async function confirm() {
    if (busyRef.current || !gateOk) return;
    busyRef.current = true; setBusy(true); setFailure(null);
    try { await onConfirm(); onClose(); }
    catch (e) { setFailure({ error: e }); }
    finally { busyRef.current = false; setBusy(false); }
  }

  const info = failure ? describeError(failure.error) : null;
  return (
    <Dialog open={open} onClose={busy ? () => {} : onClose} dismissible={!busy} title={title} sub={sub} role="alertdialog" initialFocusRef={cancelRef} closeOnScrim={false}
      footer={<>
        <Button ref={cancelRef} onClick={onClose} disabled={busy}>취소</Button>
        <Button variant={danger ? 'danger-solid' : 'primary'} onClick={() => void confirm()} disabled={busy || !gateOk}>{busy ? `${verb} 중…` : verb}</Button>
      </>}>
      {info && <Alert tone="bad" title={`${verb}하지 못했습니다`}>{info.title}. {info.detail}</Alert>}
      {children}
      {level === 'L3' && typeToConfirm && <ConfirmByTyping key={typeToConfirm.expected} expected={typeToConfirm.expected}label={typeToConfirm.label} onMatchChange={setTyped} actionLabel={verb} />}
      {level === 'L3' && acknowledge && (
        <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
          <input type="checkbox" checked={acked} onChange={(e) => setAcked(e.target.checked)} />{acknowledge}
        </label>
      )}
    </Dialog>
  );
}
