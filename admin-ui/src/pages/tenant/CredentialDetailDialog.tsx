// admin-ui/src/pages/tenant/CredentialDetailDialog.tsx
import { useState, useEffect, useCallback, useRef } from 'react';
import { Icons } from '@/icons/Icons';
import { Badge, Button, CopyBtn, Dialog, IconButton, StatusBadge } from '@/ui';
import { credentialsApi } from '@/api/credentials';
import type { Credential, AuthEvent } from '@/api/designTypes';
import { statusLabel } from '@/i18n/labels';
import { KvList } from './KvList';
import { timeAgo, ymdHm } from './tabFormat';

type Props = {
  c: Credential;
  tenantId: string;
  onClose: () => void;
  /** 1-based 위치. 없으면 이전/다음 UI 를 그리지 않는다. */
  position?: { index: number; total: number };
  onPrev?: () => void;
  onNext?: () => void;
  onRevoke?: (c: Credential) => void;
};

export default function CredentialDetailDialog({ c, tenantId, onClose, position, onPrev, onNext, onRevoke }: Props) {
  const [events, setEvents] = useState<AuthEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  // 마운트 동안만 setState 허용하는 단일 취소 신호 (effect·retry 가 공유)
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; };
  }, []);

  // c.credentialId 가 바뀌면(이전/다음) 새로 불러온다. 늦게 도착한 이전 응답은 버린다.
  const reqRef = useRef(0);
  const load = useCallback(() => {
    const req = ++reqRef.current;
    setLoading(true);
    setError(false);
    credentialsApi.authEvents(tenantId, c.credentialId, 20)
      .then((res) => { if (aliveRef.current && req === reqRef.current) setEvents(res); })
      .catch(() => { if (aliveRef.current && req === reqRef.current) setError(true); })
      .finally(() => { if (aliveRef.current && req === reqRef.current) setLoading(false); });
  }, [tenantId, c.credentialId]);

  useEffect(() => { load(); }, [load]);

  return (
    <Dialog open onClose={onClose} wide
      title={
        <span className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
          <span>Credential 상세</span>
          <StatusBadge status={c.status} />
          {position && (
            <>
              <span className="spacer" />
              <span className="hint" style={{ margin: 0, fontWeight: 400 }}>{position.index} / {position.total}</span>
              <IconButton label="이전 credential" icon={<Icons.ChevronLeft size={16} />} disabled={!onPrev} onClick={onPrev} />
              <IconButton label="다음 credential" icon={<Icons.ChevronRight size={16} />} disabled={!onNext} onClick={onNext} />
            </>
          )}
        </span>
      }
      sub={
        <span className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
          <span className="mono truncate" style={{ fontSize: 12, color: 'var(--mute)' }}>{c.credentialId}</span>
          <CopyBtn value={c.credentialId} label="credentialId 복사" iconOnly />
        </span>
      }
      footerStart={onRevoke && c.status === 'ACTIVE'
        ? <Button variant="danger" onClick={() => onRevoke(c)}>Credential 회수…</Button>
        : undefined}
      footer={<Button onClick={onClose}>닫기</Button>}>

      {/* 상세 — 이미 가진 c 로 즉시 렌더 */}
      <section className="dlg-sec">
        <span className="t-over">식별 정보</span>
        <KvList columns={2} items={[
          { k: 'externalUserId', v: c.externalUserId, mono: true },
          { k: '별칭', v: c.nickname ?? '—' },
          { k: '생성', v: ymdHm(c.createdAt) },
          { k: '마지막 사용', v: c.lastUsedAt ? timeAgo(c.lastUsedAt) : '—' },
        ]} />
      </section>

      <section className="dlg-sec">
        <span className="t-over">인증기</span>
        <KvList columns={2} items={[
          { k: '인증기', v: c.authenticatorName ?? (c.aaguid ?? 'unknown') },
          { k: '전송 방식', v: c.transports.length ? <span className="row" style={{ gap: 4 }}>{c.transports.map((t) => <span key={t} className="tag">{t}</span>)}</span> : '—' },
          { k: '서명 카운터', v: c.signatureCounter, mono: true },
          { k: 'AAGUID', v: c.aaguid ?? '—', mono: true },
          { k: 'attestation', v: c.attestationFormat ?? '—', mono: true },
        ]} />
      </section>

      <section className="dlg-sec">
        <span className="t-over">인증 기록 (최근 20건)</span>
        {loading ? (
          <div className="stack-2" aria-busy="true" style={{ padding: '8px 0' }}>
            <span className="sr-only">불러오는 중…</span>
            {[0, 1, 2].map((i) => <span key={i} className="skeleton" style={{ width: '100%' }} />)}
          </div>
        ) : error ? (
          <div className="stack-2" style={{ alignItems: 'center', padding: '16px 0' }}>
            <p className="muted" style={{ margin: 0, fontSize: 13 }}>기록을 불러오지 못했습니다.</p>
            <Button size="sm" onClick={() => load()}>재시도</Button>
          </div>
        ) : events.length === 0 ? (
          <p className="muted" style={{ margin: 0, padding: '20px 0', textAlign: 'center', fontSize: 13 }}>아직 인증 이력이 없습니다.</p>
        ) : (
          <div style={{ border: '1px solid var(--line-soft)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
            <table className="table">
              <thead>
                <tr><th>시간</th><th>결과</th><th>사유</th><th className="num">서명 카운터</th></tr>
              </thead>
              <tbody>
                {events.map((e, i) => (
                  <tr key={i}>
                    <td style={{ fontSize: 13, color: 'var(--ink-2)' }}>{ymdHm(e.createdAt)}</td>
                    <td><Badge tone={e.result === 'SUCCESS' ? 'ok' : 'bad'}>{statusLabel(e.result)}</Badge></td>
                    <td>{e.failureReason ? <span className="tag">{e.failureReason}</span> : <span className="muted">—</span>}</td>
                    <td className="num mono" style={{ fontSize: 12 }}>{e.signCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </Dialog>
  );
}
