import { useState, useEffect, useMemo } from 'react';
import { Icons } from '@/icons/Icons';
import { Dialog } from '@/shell/Dialog';
import { useToast } from '@/shell/ToastHost';
import { auditApi } from '@/api/audit';
import type { Tenant, AuditEvent, ChainVerifyResult } from '@/api/designTypes';

// ── Local utilities (mirrors design globals) ──────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  const mo = Math.floor(d / 30);
  return `${mo}개월 전`;
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function tail(s: string | null | undefined, n: number): string {
  if (!s) return '—';
  return s.slice(-n);
}

function fmt(n: number): string {
  return n.toLocaleString('ko-KR');
}

function Field({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
    </div>
  );
}

// ── EventTypeBadge ────────────────────────────────────────────────────────────

function EventTypeBadge({ type }: { type: string }) {
  const map: Record<string, string> = {
    CREDENTIAL_AUTHENTICATED: 'success',
    CREDENTIAL_REGISTERED: 'info',
    CREDENTIAL_REVOKED: 'danger',
    API_KEY_ISSUED: 'violet',
    API_KEY_REVOKED: 'danger',
    WEBAUTHN_CONFIG_UPDATED: 'warning',
    ATTESTATION_POLICY_UPDATED: 'warning',
    SIGNATURE_COUNTER_REGRESSION: 'danger',
    ATTESTATION_TRUST_FAILED: 'danger',
  };
  return (
    <span className={`badge badge--${map[type] || 'default'} badge--dot mono`} style={{ fontSize: 10 }}>
      {type}
    </span>
  );
}

// ── ChainVerifyCard ───────────────────────────────────────────────────────────

function ChainVerifyCard({
  onOpen,
  result,
}: {
  onOpen: () => void;
  result: ChainVerifyResult | null;
}) {
  return (
    <div className="card">
      <div className="card__body" style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
        <div
          style={{
            width: 40,
            height: 40,
            borderRadius: 10,
            background: 'var(--accent-soft)',
            color: 'var(--accent)',
            display: 'grid',
            placeItems: 'center',
            flex: 'none',
          }}
        >
          <Icons.Hash size={20} />
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 14, fontWeight: 600 }}>Audit Hash Chain 검증</div>
          <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>
            기간 내 모든 audit row의 prevHash → SHA-256 chain을 재계산하여 변조 여부를 확인합니다.
          </div>
        </div>
        {result && (
          <div className="row" style={{ gap: 16 }}>
            <div className="stack-1" style={{ textAlign: 'right' }}>
              <div className="muted" style={{ fontSize: 11 }}>verifiedRows</div>
              <div style={{ fontWeight: 600, fontFamily: 'var(--mono)' }}>{fmt(result.verifiedRows)}</div>
            </div>
            {result.intact ? (
              <span className="badge badge--success badge--dot" style={{ fontSize: 12, padding: '4px 10px' }}>
                INTACT
              </span>
            ) : (
              <span className="badge badge--danger badge--dot" style={{ fontSize: 12, padding: '4px 10px' }}>
                위변조 {result.tamperedEntryIds.length}건
              </span>
            )}
          </div>
        )}
        <button className="btn btn--primary btn--sm" onClick={onOpen}>
          <Icons.Hash size={12} /> 검증 실행
        </button>
        {result && result.intact && (
          <button className="btn btn--sm">
            <Icons.Download size={12} /> 보고서
          </button>
        )}
      </div>
    </div>
  );
}

// ── ChainVerifyDialog ─────────────────────────────────────────────────────────

function ChainVerifyDialog({
  open,
  onClose,
  onRun,
}: {
  open: boolean;
  onClose: () => void;
  onRun: (from: string, to: string) => Promise<void>;
}) {
  const [from, setFrom] = useState('2026-05-01');
  const [to, setTo] = useState('2026-05-15');
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);

  async function run() {
    setRunning(true);
    setProgress(0);
    // Animate progress bar while API call runs
    const tick = setInterval(() => {
      setProgress((p) => {
        const next = p + 7 + Math.random() * 9;
        return next >= 95 ? 95 : next; // cap at 95 until API returns
      });
    }, 90);
    try {
      await onRun(from, to);
      clearInterval(tick);
      setProgress(100);
    } catch {
      // error handled by parent
    } finally {
      clearInterval(tick);
      setRunning(false);
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      wide
      title="Hash chain 검증"
      sub="대상 기간 안에 있는 audit row를 chain으로 재계산합니다. 행 수에 따라 수십 초가 소요될 수 있습니다."
      footer={
        <>
          <button className="btn" onClick={onClose} disabled={running}>
            취소
          </button>
          <button className="btn btn--primary" disabled={running} onClick={run}>
            {running ? `검증 중… ${Math.floor(progress)}%` : '검증 실행'}
          </button>
        </>
      }
    >
      <div className="grid-2" style={{ marginBottom: 14 }}>
        <Field label="from">
          <input
            className="input mono"
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </Field>
        <Field label="to">
          <input
            className="input mono"
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
        </Field>
      </div>

      {running && (
        <div
          className="stack-3"
          style={{
            padding: 14,
            border: '1px solid var(--border)',
            borderRadius: 8,
            background: 'var(--surface-2)',
          }}
        >
          <div style={{ height: 6, borderRadius: 4, background: 'var(--border)', overflow: 'hidden' }}>
            <div
              style={{
                width: `${progress}%`,
                height: '100%',
                background: 'linear-gradient(90deg, var(--accent), var(--accent-hover))',
                transition: 'width 90ms linear',
              }}
            />
          </div>
          <div
            className="row"
            style={{
              justifyContent: 'space-between',
              fontSize: 12,
              color: 'var(--text-mute)',
              fontFamily: 'var(--mono)',
            }}
          >
            <span>SHA-256 prevHash → currentHash …</span>
            <span>{fmt(Math.floor(progress * 1842))} / 184,293 행</span>
          </div>
        </div>
      )}

      {!running && (
        <div
          style={{
            padding: 12,
            background: 'var(--surface-3)',
            borderRadius: 8,
            fontSize: 12,
            color: 'var(--text-soft)',
          }}
        >
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
            <Icons.Info size={13} />
            <div>
              검증은 read-only이며 audit row를 변경하지 않습니다. 결과는 화면에 인라인으로 표시되며, PDF로
              내보낼 수 있습니다 (v1.1).
            </div>
          </div>
        </div>
      )}
    </Dialog>
  );
}

// ── PayloadDialog ─────────────────────────────────────────────────────────────

function PayloadDialog({ event, onClose }: { event: AuditEvent; onClose: () => void }) {
  if (!event) return null;
  return (
    <Dialog
      open
      onClose={onClose}
      wide
      title="Audit Event"
      sub={fmtDateTime(event.ts)}
      footer={
        <button className="btn" onClick={onClose}>
          닫기
        </button>
      }
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '130px 1fr',
          rowGap: 10,
          fontSize: 13,
          marginBottom: 14,
        }}
      >
        <div className="muted">eventType</div>
        <div>
          <EventTypeBadge type={event.eventType} />
        </div>
        <div className="muted">actor</div>
        <div>
          <span className="badge" style={{ marginRight: 6 }}>
            {event.actorType}
          </span>
          <span className="mono" style={{ fontSize: 12 }}>
            {event.actorId}
          </span>
        </div>
        <div className="muted">subject</div>
        <div>
          <span className="badge" style={{ marginRight: 6 }}>
            {event.subjectType}
          </span>
          <span className="mono" style={{ fontSize: 12 }}>
            {event.subjectId}
          </span>
        </div>
      </div>
      <div className="label">payload</div>
      <pre
        style={{
          margin: 0,
          padding: 14,
          background: 'var(--surface-3)',
          borderRadius: 8,
          fontSize: 12,
          fontFamily: 'var(--mono)',
          overflow: 'auto',
          color: 'var(--text)',
        }}
      >
        {JSON.stringify(event.payload, null, 2)}
      </pre>
    </Dialog>
  );
}

// ── AuditTab ──────────────────────────────────────────────────────────────────

export default function AuditTab({ tenant, isPlatformOperator = false }: { tenant: Tenant; isPlatformOperator?: boolean }) {
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [page] = useState(0);
  const [size] = useState(50);
  const [filter, setFilter] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [verifyResult, setVerifyResult] = useState<ChainVerifyResult | null>(null);
  const [showVerify, setShowVerify] = useState(false);
  const [open, setOpen] = useState<AuditEvent | null>(null);
  const toast = useToast();

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    auditApi
      .list(tenant.id, page, size)
      .then((res) => {
        if (!cancelled) setItems(res.items);
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          const msg = e instanceof Error ? e.message : String(e);
          toast({ kind: 'err', title: 'audit 로드 실패', message: msg });
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [tenant.id, page, size]);

  const types = useMemo(() => Array.from(new Set(items.map((e) => e.eventType))), [items]);
  const filtered = filter.size === 0 ? items : items.filter((e) => filter.has(e.eventType));

  async function handleVerifyRun(from: string, to: string) {
    try {
      const result = await auditApi.verify(tenant.id, from, to);
      setVerifyResult(result);
      setShowVerify(false);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: 'chain 검증 실패', message: msg });
      setShowVerify(false);
      throw e; // let dialog know it failed (so it exits running state)
    }
  }

  return (
    <div className="stack-4">
      {isPlatformOperator && <ChainVerifyCard onOpen={() => setShowVerify(true)} result={verifyResult} />}

      <div className="card">
        <div className="card__head" style={{ gap: 10, flexWrap: 'wrap' }}>
          <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
            <Icons.Filter size={13} />
            {types.map((t) => (
              <button
                key={t}
                onClick={() => {
                  const next = new Set(filter);
                  next.has(t) ? next.delete(t) : next.add(t);
                  setFilter(next);
                }}
                className={filter.has(t) ? 'badge badge--accent' : 'badge'}
                style={{
                  cursor: 'pointer',
                  border: filter.has(t)
                    ? '1px solid var(--accent)'
                    : '1px solid var(--border)',
                }}
              >
                {t}
              </button>
            ))}
            {filter.size > 0 && (
              <button className="btn btn--ghost btn--xs" onClick={() => setFilter(new Set())}>
                전체 해제
              </button>
            )}
          </div>
          <div className="row">
            <button className="btn btn--sm">
              <Icons.Calendar size={12} /> 최근 24시간 ▾
            </button>
            <button className="btn btn--sm">
              <Icons.Download size={12} /> 내보내기
            </button>
          </div>
        </div>

        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-mute)' }}>
            Loading…
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>시각</th>
                <th>eventType</th>
                <th>actor</th>
                <th>subject</th>
                <th>payload</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((e, i) => (
                <tr key={i} onClick={() => setOpen(e)} style={{ cursor: 'pointer' }}>
                  <td>
                    <div className="stack-1">
                      <div style={{ fontWeight: 500, fontSize: 12 }}>{fmtDateTime(e.ts)}</div>
                      <div className="faint" style={{ fontSize: 11 }}>
                        {timeAgo(e.ts)}
                      </div>
                    </div>
                  </td>
                  <td>
                    <EventTypeBadge type={e.eventType} />
                  </td>
                  <td>
                    <div className="stack-1">
                      <span className="badge" style={{ fontSize: 10 }}>
                        {e.actorType}
                      </span>
                      <span className="mono faint" style={{ fontSize: 11 }}>
                        {tail(e.actorId, 10)}
                      </span>
                    </div>
                  </td>
                  <td>
                    <div className="stack-1">
                      <span className="badge" style={{ fontSize: 10 }}>
                        {e.subjectType}
                      </span>
                      <span className="mono faint" style={{ fontSize: 11 }}>
                        {tail(e.subjectId, 12)}
                      </span>
                    </div>
                  </td>
                  <td>
                    <code
                      style={{
                        fontSize: 11,
                        color: 'var(--text-soft)',
                        display: 'inline-block',
                        maxWidth: 360,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {JSON.stringify(e.payload)}
                    </code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {isPlatformOperator && (
        <ChainVerifyDialog
          open={showVerify}
          onClose={() => setShowVerify(false)}
          onRun={handleVerifyRun}
        />
      )}
      {open && <PayloadDialog event={open} onClose={() => setOpen(null)} />}
    </div>
  );
}
