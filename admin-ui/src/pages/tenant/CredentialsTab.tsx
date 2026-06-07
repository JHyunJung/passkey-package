import { useState, useEffect, useMemo } from 'react';
import { Icons } from '@/icons/Icons';
import { Dialog } from '@/shell/Dialog';
import { StatusBadge } from '@/shell/StatusBadge';
import { useToast } from '@/shell/ToastHost';
import { credentialsApi } from '@/api/credentials';
import { downloadCsv } from '@/lib/csvExport';
import type { Tenant, Credential } from '@/api/designTypes';
import CredentialDetailDialog from './CredentialDetailDialog';

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

function tail(s: string, n: number): string {
  return s.slice(-n);
}

// ── Field ─────────────────────────────────────────────────────────────────────

function Field({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
    </div>
  );
}

// ── CredentialsTab ─────────────────────────────────────────────────────────────

export default function CredentialsTab({ tenant }: { tenant: Tenant }) {
  const [items, setItems] = useState<Credential[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);  // 페이지당 조회 건수 (50/100/200)
  const [q, setQ] = useState('');
  const [searchMode, setSearchMode] = useState<'keyword' | 'aaguid' | 'status'>('keyword');
  const [loading, setLoading] = useState(true);
  const [revoking, setRevoking] = useState<Credential | null>(null);
  const [selected, setSelected] = useState<Credential | null>(null);
  const toast = useToast();

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    credentialsApi.list(tenant.id, page, size)
      .then((res) => {
        if (cancelled) return;
        setItems(res.items);
        setTotal(res.total);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: 'credentials 로드 실패', message: msg });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [tenant.id, page, size]);

  async function reload() {
    setLoading(true);
    try {
      const res = await credentialsApi.list(tenant.id, page, size);
      setItems(res.items);
      setTotal(res.total);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: 'credentials 로드 실패', message: msg });
    } finally {
      setLoading(false);
    }
  }

  // 클라이언트 사이드 검색 (디자인 패턴 그대로)
  const filtered = useMemo(() => {
    const ql = q.toLowerCase();
    if (!ql) return items;
    return items.filter((c) => {
      if (searchMode === 'aaguid') {
        return c.aaguid?.toLowerCase().includes(ql) ?? false;
      }
      if (searchMode === 'status') {
        return c.status.toLowerCase().includes(ql);
      }
      return c.externalUserId.toLowerCase().includes(ql)
          || (c.nickname?.toLowerCase().includes(ql) ?? false)
          || c.credentialId.toLowerCase().includes(ql);
    });
  }, [items, q, searchMode]);

  const totalPages = Math.max(1, Math.ceil(total / size));

  async function handleRevoke() {
    if (!revoking) return;
    try {
      await credentialsApi.revoke(tenant.id, revoking.credentialId);
      toast({
        kind: 'warn',
        title: 'Credential이 회수되었습니다.',
        message: `${tail(revoking.credentialId, 12)} · ${revoking.externalUserId}`,
      });
      setRevoking(null);
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '회수 실패', message: msg });
    }
  }

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head" style={{ gap: 10 }}>
          <div className="row" style={{ gap: 10, flex: 1 }}>
            <div style={{ position: 'relative', flex: 1, maxWidth: 360 }}>
              <span style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-mute)' }}><Icons.Search size={13} /></span>
              <input
                className="input"
                placeholder={
                  searchMode === 'aaguid' ? 'aaguid 검색 (예: ea9b8d66)'
                  : searchMode === 'status' ? 'status: ACTIVE 또는 REVOKED'
                  : 'externalUserId · nickname · credentialId 검색'
                }
                value={q}
                onChange={(e) => setQ(e.target.value)}
                style={{ paddingLeft: 28, height: 30 }}
              />
            </div>
            <button className="btn btn--sm" onClick={() => {
              setSearchMode((m) => m === 'keyword' ? 'aaguid' : m === 'aaguid' ? 'status' : 'keyword');
            }}>
              <Icons.Filter size={12} /> aaguid · status
            </button>
            <span className="muted" style={{ fontSize: 12 }}>{filtered.length}건</span>
          </div>
          <div className="row">
            <label className="row" style={{ gap: 6, fontSize: 12, color: 'var(--text-mute)' }}>
              페이지당
              <select
                className="input"
                value={size}
                onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                style={{ height: 30, padding: '0 8px', width: 'auto' }}
              >
                <option value={50}>50건</option>
                <option value={100}>100건</option>
                <option value={200}>200건</option>
              </select>
            </label>
            <button className="btn btn--sm" onClick={() => {
              if (!filtered || filtered.length === 0) return;
              downloadCsv(
                `credentials-${tenant.slug}-${new Date().toISOString().slice(0,10)}.csv`,
                ['credentialId', 'externalUserId', 'label', 'authenticatorName', 'status', 'aaguid', 'transports', 'signatureCounter', 'lastUsedAt', 'createdAt'],
                filtered.map((c) => [
                  c.credentialId,
                  c.externalUserId,
                  c.nickname ?? '',
                  c.authenticatorName ?? '',
                  c.status,
                  c.aaguid ?? '',
                  c.transports.join('|'),
                  c.signatureCounter,
                  c.lastUsedAt ?? '',
                  c.createdAt,
                ]),
              );
            }}>
              <Icons.Download size={12} /> CSV
            </button>
          </div>
        </div>
        {loading ? (
          <div style={{ padding: '40px 14px', textAlign: 'center', color: 'var(--text-mute)', fontSize: 13 }}>로딩 중…</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Credential ID</th>
                <th>사용자</th>
                <th>별칭</th>
                <th>인증기</th>
                <th>전송 방식</th>
                <th style={{ textAlign: 'right' }}>서명 카운터</th>
                <th>상태</th>
                <th>마지막 사용</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((c) => (
                <tr
                  key={c.credentialId}
                  onClick={() => setSelected(c)}
                  style={{ opacity: c.status === 'REVOKED' ? 0.55 : 1, cursor: 'pointer' }}
                >
                  <td className="mono" style={{ fontSize: 12 }}>{tail(c.credentialId, 12)}</td>
                  <td className="mono" style={{ fontSize: 12 }}>{c.externalUserId}</td>
                  <td>{c.nickname ?? <span className="faint">—</span>}</td>
                  <td>
                    <div className="row" style={{ gap: 6 }}>
                      <Icons.Fingerprint size={12} />
                      {c.authenticatorName ? (
                        // MDS 룩업으로 모델/상태가 식별된 경우
                        <span className="badge badge--accent" style={{ fontSize: 10 }} title={c.aaguid ?? undefined}>
                          {c.authenticatorName}
                        </span>
                      ) : (
                        // MDS 미식별 — aaguid 축약(없으면 unknown)
                        <span className="badge" style={{ fontSize: 10 }} title={c.aaguid ?? undefined}>
                          {c.aaguid ? tail(c.aaguid.replace(/-/g, ''), 8) : 'unknown'}
                        </span>
                      )}
                    </div>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: 3 }}>
                      {c.transports.map((t) => <span key={t} className="badge" style={{ fontSize: 10 }}>{t}</span>)}
                    </div>
                  </td>
                  <td style={{ textAlign: 'right' }} className="mono">
                    {c.signatureCounter > 0 ? (
                      c.signatureCounter
                    ) : c.lastUsedAt ? (
                      // 인증됐는데 counter=0 → counterless 인증기(Touch ID/Windows Hello/
                      // iCloud·Android 싱크 패스키). WebAuthn 상 정상이며 counter 기반
                      // 복제 탐지가 적용되지 않는다.
                      <span className="faint" title="counterless 인증기 — 플랫폼·싱크 패스키는 signCount가 0으로 고정됩니다(WebAuthn 정상). counter 기반 복제 탐지 미적용.">
                        N/A
                      </span>
                    ) : (
                      // 아직 인증 이력 없음.
                      <span className="faint">0</span>
                    )}
                  </td>
                  <td><StatusBadge status={c.status} /></td>
                  <td><span className="muted">{timeAgo(c.lastUsedAt)}</span></td>
                  <td>
                    {c.status === 'ACTIVE' && (
                      <button
                        className="btn btn--xs"
                        onClick={(e) => { e.stopPropagation(); setRevoking(c); }}
                        style={{ color: 'var(--danger)' }}
                      >
                        <Icons.Trash size={12} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={9} style={{ textAlign: 'center', padding: '32px 14px', color: 'var(--text-mute)', fontSize: 13 }}>
                    {q ? '검색 결과가 없습니다.' : 'Credential이 없습니다.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        )}
        <div style={{ padding: '10px 14px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12, color: 'var(--text-mute)', borderTop: '1px solid var(--border)' }}>
          <span>page {page + 1} of {totalPages} · 페이지당 {size}건</span>
          <div className="row" style={{ gap: 4 }}>
            <button className="btn btn--xs" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>
              <Icons.ChevronLeft size={12} />
            </button>
            {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
              const start = Math.max(0, Math.min(page - 2, totalPages - 5));
              const p = start + i;
              return (
                <button
                  key={p}
                  className="btn btn--xs"
                  style={p === page ? { background: 'var(--accent)', color: 'white', borderColor: 'var(--accent)' } : {}}
                  onClick={() => setPage(p)}
                >
                  {p + 1}
                </button>
              );
            })}
            <button className="btn btn--xs" onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}>
              <Icons.ChevronRight size={12} />
            </button>
          </div>
        </div>
      </div>

      {revoking && (
        <RevokeCredentialDialog
          c={revoking}
          onClose={() => setRevoking(null)}
          onConfirm={handleRevoke}
        />
      )}

      {selected && (
        <CredentialDetailDialog
          c={selected}
          tenantId={tenant.id}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}

// ── RevokeCredentialDialog ────────────────────────────────────────────────────

function RevokeCredentialDialog({
  c,
  onClose,
  onConfirm,
}: {
  c: Credential;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const [typed, setTyped] = useState('');
  useEffect(() => { setTyped(''); }, [c]);
  const required = tail(c.credentialId, 8);
  const ok = typed === required;

  return (
    <Dialog
      open
      onClose={onClose}
      title="Credential 회수"
      sub="회수된 credential은 다음 ceremony부터 인증에 실패합니다. 사용자는 패스키를 재등록해야 합니다."
      footer={
        <>
          <button className="btn" onClick={onClose}>취소</button>
          <button className="btn btn--danger" disabled={!ok} onClick={onConfirm}>확인 — 회수</button>
        </>
      }
    >
      <div style={{ padding: 14, border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface-2)' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '130px 1fr', rowGap: 8, fontSize: 13 }}>
          <div className="muted">externalUserId</div><div className="mono">{c.externalUserId}</div>
          <div className="muted">nickname</div><div>{c.nickname ?? '—'}</div>
          <div className="muted">authenticator</div>
          <div>
            <span className="badge badge--accent">
              {c.aaguid ? tail(c.aaguid.replace(/-/g, ''), 8) : 'unknown'}
            </span>
          </div>
          <div className="muted">credentialId</div><div className="mono" style={{ fontSize: 11 }}>{c.credentialId}</div>
          <div className="muted">마지막 사용</div><div className="muted">{fmtDateTime(c.lastUsedAt)}</div>
        </div>
      </div>

      <Field label={<>확인을 위해 credentialId의 마지막 8자를 입력하세요: <code style={{ background: 'var(--surface-3)', padding: '1px 6px', borderRadius: 3, fontFamily: 'var(--mono)' }}>{required}</code></>}>
        <input
          autoFocus
          className="input mono"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          placeholder={required}
          style={{ marginTop: 6 }}
        />
      </Field>
    </Dialog>
  );
}
