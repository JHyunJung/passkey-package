import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { ApiError } from '../api/types';
import type { KeyList, RotateResponse, SigningKeyView } from '../api/types';
import { useToast } from '../components/Toast';
import Dialog from '../components/Dialog';
import { Refresh, Key } from '../components/Icons';
import { formatDateTime } from '../lib/formatDateTime';

export default function KeyManagement() {
  const [keys, setKeys] = useState<SigningKeyView[]>([]);
  const [loading, setLoading] = useState(true);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [rotating, setRotating] = useState(false);
  const [last, setLast] = useState<RotateResponse | null>(null);
  const toast = useToast();

  function refresh() {
    setLoading(true);
    api.get<KeyList>('/admin/api/keys')
      .then((r) => { setKeys(r.keys); setLoading(false); })
      .catch(() => setLoading(false));
  }

  useEffect(refresh, []);

  async function rotate() {
    setRotating(true);
    try {
      const r = await api.post<RotateResponse>('/admin/api/keys/rotate', {});
      setLast(r);
      toast({ kind: 'ok', title: '키 회전됨', message: `${r.oldKid} → ${r.newKid}` });
      setConfirmOpen(false);
      refresh();
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({ kind: 'err', title: '회전 실패', message: e?.serverMessage ?? String(err), traceId: e?.traceId });
    } finally {
      setRotating(false);
    }
  }

  // Status literals are uppercase strings per SigningKey.java constructor/rotate()/revoke()
  const active = keys.filter((k) => k.status === 'ACTIVE').length;
  const rotated = keys.filter((k) => k.status === 'ROTATED').length;
  const revoked = keys.filter((k) => k.status === 'REVOKED').length;

  return (
    <div className="stack-6">
      <div className="page__head">
        <div>
          <h1 className="page__title">Signing Keys</h1>
          <div className="page__sub">ID Token 서명 키 생애 주기 (ACTIVE → ROTATED → REVOKED, 30분 grace).</div>
        </div>
        <button className="btn btn--primary" onClick={() => setConfirmOpen(true)} disabled={rotating}>
          <Refresh size={14} /> 지금 회전
        </button>
      </div>

      <div className="grid-3">
        <Metric label="ACTIVE" value={String(active)} sub="현재 서명 중" />
        <Metric label="ROTATED" value={String(rotated)} sub="grace window" />
        <Metric label="REVOKED" value={String(revoked)} sub="JWKS에서 제외" />
      </div>

      {last && (
        <div className="banner banner--success">
          <Key size={16} className="banner__icon" />
          <div>
            <div className="banner__title">키 회전 완료</div>
            <div className="banner__body mono">old: {last.oldKid} → new: {last.newKid}</div>
          </div>
        </div>
      )}

      <div className="card">
        <div className="card__head">
          <h2 className="card__title">모든 키</h2>
          <span className="muted" style={{ fontSize: 12 }}>{keys.length}건</span>
        </div>
        {loading ? (
          <div className="card__body"><div className="skeleton" style={{ height: 100 }} /></div>
        ) : (
          <table className="table">
            <thead>
              <tr><th>KID</th><th>ALG</th><th>STATUS</th><th>CREATED</th><th>ROTATED</th><th>REVOKED</th></tr>
            </thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k.id}>
                  <td className="mono" title={k.kid}>{k.kid.slice(0, 16)}…</td>
                  <td>{k.alg}</td>
                  <td>
                    <span className={`badge badge--${k.status === 'ACTIVE' ? 'success' : k.status === 'ROTATED' ? 'warning' : 'danger'} badge--dot`}>
                      {k.status}
                    </span>
                  </td>
                  <td className="mono muted">{formatDateTime(k.createdAt)}</td>
                  <td className="mono muted">{formatDateTime(k.rotatedAt)}</td>
                  <td className="mono muted">{formatDateTime(k.revokedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <Dialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        title="서명 키 회전"
        sub="새 ACTIVE 키 생성, 기존 ACTIVE는 ROTATED(30분 grace) 후 REVOKED 처리됩니다."
        footer={
          <>
            <button className="btn btn--outline" onClick={() => setConfirmOpen(false)}>취소</button>
            <button className="btn btn--danger" onClick={rotate} disabled={rotating}>
              {rotating ? '회전 중…' : '회전 실행'}
            </button>
          </>
        }
      >
        <div className="banner banner--warning">
          이 작업은 즉시 실행됩니다. RP가 캐시한 JWKS는 grace window 동안 ROTATED 키도 검증 가능.
        </div>
      </Dialog>
    </div>
  );
}

function Metric({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card" style={{ padding: 'var(--card-pad)' }}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      {sub && <div className="metric-delta">{sub}</div>}
    </div>
  );
}
