import { useState, useEffect, useCallback, useRef } from 'react';
import { Dialog } from '@/shell/Dialog';
import { credentialsApi } from '@/api/credentials';
import type { Credential, AuthEvent } from '@/api/designTypes';
import { statusLabel } from '@/i18n/labels';

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

function ResultBadge({ result }: { result: 'SUCCESS' | 'FAILED' }) {
  const ok = result === 'SUCCESS';
  return (
    <span className={`badge badge--dot badge--${ok ? 'success' : 'danger'}`}>
      {statusLabel(result)}
    </span>
  );
}

export default function CredentialDetailDialog({
  c, tenantId, onClose,
}: {
  c: Credential;
  tenantId: string;
  onClose: () => void;
}) {
  const [events, setEvents] = useState<AuthEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  // 마운트 동안만 setState 허용하는 단일 취소 신호 (effect·retry 가 공유)
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; };
  }, []);

  const load = useCallback(() => {
    setLoading(true);
    setError(false);
    credentialsApi.authEvents(tenantId, c.credentialId, 20)
      .then((res) => { if (aliveRef.current) setEvents(res); })
      .catch(() => { if (aliveRef.current) setError(true); })
      .finally(() => { if (aliveRef.current) setLoading(false); });
  }, [tenantId, c.credentialId]);

  useEffect(() => { load(); }, [load]);

  return (
    <Dialog open onClose={onClose} wide title="Credential 상세"
      sub={c.credentialId}
      footer={<button className="btn" onClick={onClose}>닫기</button>}>

      {/* 상세 — 이미 가진 c 로 즉시 렌더 */}
      <div style={{ display: 'grid', gridTemplateColumns: '140px 1fr', rowGap: 8, fontSize: 13, marginBottom: 16 }}>
        <div className="muted">externalUserId</div><div className="mono">{c.externalUserId}</div>
        <div className="muted">별칭</div><div>{c.nickname ?? '—'}</div>
        <div className="muted">인증기</div><div>{c.authenticatorName ?? (c.aaguid ?? 'unknown')}</div>
        <div className="muted">aaguid</div><div className="mono">{c.aaguid ?? '—'}</div>
        <div className="muted">전송 방식</div>
        <div style={{ display: 'flex', gap: 3 }}>
          {c.transports.length ? c.transports.map((t) => <span key={t} className="badge" style={{ fontSize: 10 }}>{t}</span>) : '—'}
        </div>
        <div className="muted">서명 카운터</div><div className="mono">{c.signatureCounter}</div>
        <div className="muted">attestation</div><div className="mono">{c.attestationFormat ?? '—'}</div>
        <div className="muted">마지막 사용</div><div className="muted">{fmtDateTime(c.lastUsedAt)}</div>
        <div className="muted">생성</div><div className="muted">{fmtDateTime(c.createdAt)}</div>
      </div>

      {/* 인증 기록 */}
      <div className="label" style={{ borderTop: '1px solid var(--border)', paddingTop: 10 }}>인증 기록 (최근 20건)</div>
      {loading ? (
        <div style={{ padding: '20px 0', textAlign: 'center', color: 'var(--text-mute)', fontSize: 13 }}>로딩 중…</div>
      ) : error ? (
        <div style={{ padding: '16px 0', textAlign: 'center', fontSize: 13 }}>
          <div className="muted" style={{ marginBottom: 8 }}>기록을 불러오지 못했습니다.</div>
          <button className="btn btn--sm" onClick={() => load()}>재시도</button>
        </div>
      ) : events.length === 0 ? (
        <div style={{ padding: '20px 0', textAlign: 'center', color: 'var(--text-mute)', fontSize: 13 }}>아직 인증 이력이 없습니다.</div>
      ) : (
        <table className="table">
          <thead>
            <tr><th>시간</th><th>결과</th><th>사유</th><th style={{ textAlign: 'right' }}>서명 카운터</th></tr>
          </thead>
          <tbody>
            {events.map((e, i) => (
              <tr key={i}>
                <td className="muted">{fmtDateTime(e.createdAt)}</td>
                <td><ResultBadge result={e.result} /></td>
                <td>{e.failureReason ?? <span className="faint">—</span>}</td>
                <td style={{ textAlign: 'right' }} className="mono">{e.signCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Dialog>
  );
}
