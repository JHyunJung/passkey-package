import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { ApiError } from '../api/types';
import type { AuditLogView } from '../api/types';
import { useToast } from '../components/Toast';
import { Receipt, Refresh, Shield } from '../components/Icons';
import { formatDateTime } from '../lib/formatDateTime';
import PlatformOnlyGuard from '../components/PlatformOnlyGuard';

// AuditChainVerifier.Result record fields (verified via java source):
//   ok: boolean
//   brokenAt: Long | null (id of first broken row, or null when chain intact)
// No "checked" or "message" fields in the server record.
interface VerifyResult {
  ok: boolean;
  brokenAt?: number | null;
}

export default function AuditLog() {
  const [rows, setRows] = useState<AuditLogView[]>([]);
  const [action, setAction] = useState('');
  const [actorId, setActorId] = useState('');
  const [tenantId, setTenantId] = useState('');
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [verify, setVerify] = useState<VerifyResult | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [loading, setLoading] = useState(true);
  const toast = useToast();

  function load() {
    setLoading(true);
    setFetchError(null);
    const params = new URLSearchParams();
    if (action) params.set('action', action);
    if (actorId) params.set('actorId', actorId);
    if (tenantId) params.set('tenantId', tenantId);
    api.get<AuditLogView[]>(`/admin/api/audit?${params.toString()}`)
      .then((r) => { setRows(r); setLoading(false); })
      .catch((e) => {
        setLoading(false);
        const err = e instanceof ApiError ? e : null;
        const msg = err?.serverMessage ?? String(e);
        setFetchError(msg);
        toast({ kind: 'err', title: '감사 로그 실패', message: msg, traceId: err?.traceId });
      });
  }

  useEffect(load, []);

  async function runVerify() {
    setVerifying(true);
    try {
      const r = await api.get<VerifyResult>('/admin/api/audit/verify');
      setVerify(r);
      toast({
        kind: r.ok ? 'ok' : 'err',
        title: r.ok ? 'Chain OK' : 'Chain BROKEN',
        message: r.ok ? '전체 hash chain 무결성 확인됨.' : `id ${r.brokenAt}에서 위변조 감지.`,
      });
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({ kind: 'err', title: '검증 실패', message: e?.serverMessage ?? String(err), traceId: e?.traceId });
    } finally {
      setVerifying(false);
    }
  }

  return (
    <PlatformOnlyGuard>
      <div className="stack-6">
      <div className="page__head">
        <div>
          <h1 className="page__title">Audit Log</h1>
          <div className="page__sub">모든 mutation은 hash chain으로 연결되어 위변조 검출 가능.</div>
        </div>
        <button className="btn btn--outline" onClick={runVerify} disabled={verifying}>
          <Shield size={14} /> {verifying ? '검증 중…' : 'Chain 검증'}
        </button>
      </div>

      {verify && (
        <div className={`banner banner--${verify.ok ? 'success' : 'danger'}`}>
          <Shield size={16} className="banner__icon" />
          <div>
            <div className="banner__title">
              {verify.ok ? 'Chain 무결 · 전체 행 검증됨' : 'Chain 위변조 감지'}
            </div>
            {verify.ok
              ? <div className="banner__body">모든 hash chain 링크가 유효합니다.</div>
              : <div className="banner__body mono">brokenAt: {verify.brokenAt}</div>
            }
          </div>
        </div>
      )}

      <div className="card">
        <div className="card__head">
          <div className="row" style={{ gap: 8, flex: 1 }}>
            <input
              className="input"
              placeholder="action"
              value={action}
              onChange={(e) => setAction(e.target.value)}
              style={{ maxWidth: 200 }}
            />
            <input
              className="input"
              placeholder="actorId"
              value={actorId}
              onChange={(e) => setActorId(e.target.value)}
              style={{ maxWidth: 140 }}
            />
            <input
              className="input"
              placeholder="tenantId (UUID)"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
              style={{ maxWidth: 280 }}
            />
            <button className="btn btn--outline" onClick={load}>
              <Refresh size={14} /> 적용
            </button>
          </div>
          <span className="muted" style={{ fontSize: 12 }}>{rows.length}건</span>
        </div>
        {loading ? (
          <div className="card__body"><div className="skeleton" style={{ height: 200 }} /></div>
        ) : fetchError ? (
          <div className="card__body">
            <div className="banner banner--danger">
              <div>
                <div className="banner__title">로그 조회 실패</div>
                <div className="banner__body">{fetchError}</div>
              </div>
            </div>
          </div>
        ) : rows.length === 0 ? (
          <div className="empty">
            <div className="empty__art"><Receipt size={20} /></div>
            <div className="empty__title">로그 없음</div>
            <div className="empty__sub">필터를 바꾸거나 액션을 발생시키세요.</div>
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>#</th>
                <th>ACTION</th>
                <th>ACTOR</th>
                <th>TENANT</th>
                <th>TARGET</th>
                <th>AT</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className="mono muted">{r.id}</td>
                  <td><span className="badge badge--accent">{r.action}</span></td>
                  <td className="mono">{r.actorEmail}</td>
                  <td className="muted" style={{ fontSize: 12 }}>
                    {r.tenantId ? r.tenantId.slice(0, 8) + '…' : '—'}
                  </td>
                  <td className="mono muted">
                    {r.targetType ?? '–'}{r.targetId ? `/${r.targetId}` : ''}
                  </td>
                  <td className="mono muted">{formatDateTime(r.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
    </PlatformOnlyGuard>
  );
}
