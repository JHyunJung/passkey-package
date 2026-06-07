import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { Dialog } from '@/shell/Dialog';
import { StatusBadge } from '@/shell/StatusBadge';
import { useToast } from '@/shell/ToastHost';
import { apiKeysApi } from '@/api/apiKeys';
import { copyToClipboard } from '@/lib/clipboard';
import type { Tenant, ApiKey } from '@/api/designTypes';

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

// ── MetricCard ────────────────────────────────────────────────────────────────

function MetricCard({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) {
  return (
    <div className="card">
      <div className="card__body">
        <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>{label}</div>
        <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em' }}>{value}</div>
        {sub && <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>{sub}</div>}
      </div>
    </div>
  );
}

// ── Field ─────────────────────────────────────────────────────────────────────

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

// ── Scope options ─────────────────────────────────────────────────────────────

const NAME_MAX = 64;

const SCOPE_OPTIONS: { value: string; label: string; desc: string }[] = [
  { value: 'registration', label: 'registration', desc: '패스키 등록 + self-service credential 관리' },
  { value: 'authentication', label: 'authentication', desc: '패스키 인증(로그인)' },
];

// 만료 프리셋(개월). null = 무기한.
const EXPIRY_OPTIONS: { months: number | null; label: string }[] = [
  { months: 6, label: '6개월' },
  { months: 12, label: '12개월' },
  { months: 24, label: '24개월' },
  { months: 36, label: '36개월' },
  { months: null, label: '무기한' },
];

// now + N개월의 날짜 미리보기(YYYY-MM-DD, Asia/Seoul). null이면 null.
// 말일 보정: 1/31 + 1개월처럼 다음 달에 같은 일자가 없으면 그 달 말일로 클램프.
function previewExpiry(months: number | null): string | null {
  if (months == null) return null;
  const now = new Date();
  const lastDayOfTargetMonth = new Date(now.getFullYear(), now.getMonth() + months + 1, 0).getDate();
  const day = Math.min(now.getDate(), lastDayOfTargetMonth);
  const target = new Date(now.getFullYear(), now.getMonth() + months, day,
                          now.getHours(), now.getMinutes(), now.getSeconds());
  return target.toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' }); // en-CA → YYYY-MM-DD
}

// 만료 상태 판정.
function expiryState(expiresAt: string | null): 'none' | 'expired' | 'soon' | 'ok' {
  if (!expiresAt) return 'none';
  const exp = new Date(expiresAt).getTime();
  const now = Date.now();
  if (exp <= now) return 'expired';
  if (exp - now <= 30 * 24 * 60 * 60 * 1000) return 'soon';
  return 'ok';
}

// ── ApiKeysTab ────────────────────────────────────────────────────────────────

export default function ApiKeysTab({ tenant }: { tenant: Tenant }) {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [loading, setLoading] = useState(true);
  const [showNew, setShowNew] = useState(false);
  const [issued, setIssued] = useState<{ key: ApiKey; plaintext: string; oldKeyExpiresAt?: string } | null>(null);
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const [rotating, setRotating] = useState<ApiKey | null>(null);
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const list = await apiKeysApi.list(tenant.id);
      setKeys(list);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: 'API key 로드 실패', message: msg });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { reload(); }, [tenant.id]);

  async function handleIssue(name: string, scopes: string[], expiresInMonths: number | null) {
    try {
      const result = await apiKeysApi.create(tenant.id, name, scopes, expiresInMonths);
      setShowNew(false);
      setIssued(result);
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '발급 실패', message: msg });
    }
  }

  async function handleRevoke(k: ApiKey) {
    try {
      await apiKeysApi.revoke(k.id);
      toast({ kind: 'warn', title: 'API key가 회수되었습니다.', message: `${k.prefix} · ${k.name}` });
      setRevoking(null);
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '회수 실패', message: msg });
    }
  }

  async function handleRotate(k: ApiKey) {
    try {
      const res = await apiKeysApi.rotate(k.id);
      setRotating(null);
      setIssued({
        key: { ...k, prefix: res.prefix, scopes: res.scopes },
        plaintext: res.plaintextKey,
        oldKeyExpiresAt: res.oldKeyExpiresAt,
      });
      await reload();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '회전 실패', message: msg });
    }
  }

  const activeCount = keys.filter((k) => k.status === 'ACTIVE').length;

  if (loading && keys.length === 0) {
    return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  }

  return (
    <div className="stack-4">
      <div className="grid-3">
        <MetricCard label="총 API Key" value={keys.length} sub={`활성 ${activeCount} · 만료 ${keys.filter((k) => k.status === 'EXPIRED').length} · 회수 ${keys.filter((k) => k.status === 'REVOKED').length}`} />
        <MetricCard label="최근 발급" value="1일 전" sub={`production · ${tail(keys[0]?.id || '—', 6)}`} />
        <MetricCard label="권장 rotation" value="90일" sub="다음 키 회전: 73일 후" />
      </div>

      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">API Keys</h3>
            <div className="card__sub">RP 백엔드에서 Crosscert Passkey API 호출 시 사용. plaintext는 발급 시 1회만 노출됩니다.</div>
          </div>
          <button className="btn btn--primary btn--sm" onClick={() => setShowNew(true)}><Icons.Plus size={12} /> 새 키 발급</button>
        </div>

        <table className="table">
          <thead>
            <tr>
              <th>Prefix</th>
              <th>이름</th>
              <th>Status</th>
              <th>마지막 사용</th>
              <th>생성</th>
              <th>만료일</th>
              <th style={{ textAlign: 'right' }}>액션</th>
            </tr>
          </thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.id} style={{ opacity: (k.status === 'REVOKED' || k.status === 'EXPIRED') ? 0.55 : 1 }}>
                <td>
                  <div className="row">
                    <Icons.Key size={13} />
                    <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{k.prefix}<span className="faint">.•••••</span></span>
                  </div>
                </td>
                <td>{k.name}</td>
                <td><StatusBadge status={k.status} /></td>
                <td>{k.lastUsedAt ? <span className="muted">{timeAgo(k.lastUsedAt)}</span> : <span className="faint">미사용</span>}</td>
                <td><span className="muted">{fmtDateTime(k.createdAt)}</span></td>
                <td>
                  {(() => {
                    if (k.status === 'REVOKED') return <span className="faint">—</span>;
                    const st = expiryState(k.expiresAt);
                    if (st === 'none') return <span className="faint">무기한</span>;
                    if (st === 'expired') return (
                      <span style={{ color: 'var(--danger)', fontSize: 12 }}>{fmtDateTime(k.expiresAt!)}</span>
                    );
                    return (
                      <span className={st === 'soon' ? undefined : 'muted'} style={st === 'soon' ? { color: 'var(--warning)' } : undefined}>
                        {fmtDateTime(k.expiresAt!)}
                      </span>
                    );
                  })()}
                </td>
                <td style={{ textAlign: 'right' }}>
                  {k.status === 'ACTIVE' && (
                    <span style={{ display: 'inline-flex', gap: 6, justifyContent: 'flex-end' }}>
                      <button className="btn btn--xs" onClick={() => setRotating(k)}>
                        <Icons.Refresh size={12} /> 회전
                      </button>
                      <button className="btn btn--xs" onClick={() => setRevoking(k)} style={{ color: 'var(--danger)', borderColor: 'color-mix(in oklab, var(--danger) 30%, var(--border))' }}>
                        <Icons.Trash size={12} /> 회수
                      </button>
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <NewKeyDialog open={showNew} onClose={() => setShowNew(false)} onIssue={handleIssue} />
      <IssuedKeyModal issued={issued} onClose={() => { setIssued(null); }} />
      <RotateKeyDialog k={rotating} onClose={() => setRotating(null)} onConfirm={handleRotate} />
      <RevokeKeyDialog k={revoking} onClose={() => setRevoking(null)} onConfirm={handleRevoke} />
    </div>
  );
}

// ── NewKeyDialog ──────────────────────────────────────────────────────────────

function NewKeyDialog({ open, onClose, onIssue }: {
  open: boolean;
  onClose: () => void;
  onIssue: (name: string, scopes: string[], expiresInMonths: number | null) => void;
}) {
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['registration', 'authentication']);
  const [expiresInMonths, setExpiresInMonths] = useState<number | null>(24); // 기본 24개월

  function toggle(v: string) {
    setScopes((prev) => prev.includes(v) ? prev.filter((s) => s !== v) : [...prev, v]);
  }
  function submit() {
    if (!name || scopes.length === 0) return;
    onIssue(name, scopes, expiresInMonths);
    setName('');
    setScopes(['registration', 'authentication']);
    setExpiresInMonths(24);
  }

  return (
    <Dialog open={open} onClose={onClose} title="새 API key 발급"
      sub="발급 후 plaintext는 단 한 번만 노출됩니다. 안전한 장소에 즉시 보관하세요."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" disabled={!name || scopes.length === 0} onClick={submit}>발급</button>
      </>}
    >
      <Field label="용도 (이름)" hint={`배포 환경이나 용도를 짧게. 예: production, staging, mobile-app (최대 ${NAME_MAX}자)`}>
        <input autoFocus className="input" maxLength={NAME_MAX} value={name} onChange={(e) => setName(e.target.value)} placeholder="production" />
        <div className="muted" style={{ fontSize: 11, textAlign: 'right', marginTop: 4 }}>{name.length} / {NAME_MAX}</div>
      </Field>
      <div style={{ marginTop: 14 }}>
        <label className="label">권한 범위 (scope) — 하나 이상</label>
        <div className="stack-2" style={{ marginTop: 6 }}>
          {SCOPE_OPTIONS.map((o) => (
            <label key={o.value} style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 8, cursor: 'pointer', background: scopes.includes(o.value) ? 'var(--accent-soft)' : 'transparent' }}>
              <input type="checkbox" checked={scopes.includes(o.value)} onChange={() => toggle(o.value)} style={{ marginTop: 3 }} />
              <div>
                <div style={{ fontWeight: 600, fontSize: 13 }}>{o.label}</div>
                <div className="muted" style={{ fontSize: 12 }}>{o.desc}</div>
              </div>
            </label>
          ))}
        </div>
      </div>
      <div style={{ marginTop: 14 }}>
        <label className="label">만료 기간</label>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
          {EXPIRY_OPTIONS.map((o) => {
            const selected = expiresInMonths === o.months;
            return (
              <button
                key={o.label}
                type="button"
                onClick={() => setExpiresInMonths(o.months)}
                style={{
                  padding: '6px 12px', borderRadius: 8,
                  border: `1px solid ${selected ? 'var(--accent)' : 'var(--border)'}`,
                  background: selected ? 'var(--accent-soft)' : 'var(--surface)',
                  color: selected ? 'var(--accent)' : 'var(--text)',
                  fontWeight: selected ? 600 : 500, cursor: 'pointer', fontSize: 13,
                }}
              >
                {o.label}
              </button>
            );
          })}
        </div>
        <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
          {expiresInMonths == null
            ? '만료 없음 — 키가 무기한으로 유효합니다.'
            : `만료일: ${previewExpiry(expiresInMonths)}`}
        </div>
      </div>
    </Dialog>
  );
}

// ── IssuedKeyModal ────────────────────────────────────────────────────────────

function IssuedKeyModal({ issued, onClose }: {
  issued: { key: ApiKey; plaintext: string; oldKeyExpiresAt?: string } | null;
  onClose: () => void;
}) {
  const toast = useToast();
  const [copied, setCopied] = useState(false);
  const [checked, setChecked] = useState(false);
  useEffect(() => { if (issued) { setCopied(false); setChecked(false); } }, [issued]);
  if (!issued) return null;
  return (
    <Dialog open={true} onClose={() => { /* enforce */ }} closeOnScrim={false} wide
      title={<span style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Icons.Alert size={18} /> 새 API key가 발급되었습니다 — 지금만 표시됩니다</span>}
      sub="이 창을 닫으면 plaintext는 영구히 사라집니다. 절대 다시 표시되지 않습니다."
      footer={<>
        <div style={{ flex: 1, fontSize: 12, color: 'var(--text-mute)', display: 'flex', alignItems: 'center', gap: 8 }}>
          <Icons.Lock size={13} /> server는 plaintext의 해시만 저장합니다.
        </div>
        <button className="btn btn--primary" disabled={!checked} onClick={onClose}>{checked ? '닫기 (영구 소실)' : '체크 필요'}</button>
      </>}
    >
      <div className="stack-3">
        {issued.oldKeyExpiresAt && (
          <div style={{ padding: '8px 10px', background: 'var(--warning-soft)', color: 'var(--warning)', borderRadius: 8, fontSize: 12, display: 'flex', gap: 8 }}>
            <Icons.Alert size={14} />
            <span>구 키는 <b>{fmtDateTime(issued.oldKeyExpiresAt)}</b>에 만료됩니다. 그 전에 RP 서버를 새 키로 교체하세요.</span>
          </div>
        )}
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
          <span className="badge badge--success">발급 완료</span>
          <div style={{ flex: 1 }}>
            <div className="muted" style={{ fontSize: 12 }}>이름</div>
            <div style={{ fontWeight: 600, fontSize: 14 }}>{issued.key.name}</div>
          </div>
          <div>
            <div className="muted" style={{ fontSize: 12 }}>prefix</div>
            <div className="mono" style={{ fontSize: 12 }}>{issued.key.prefix}</div>
          </div>
          <div>
            <div className="muted" style={{ fontSize: 12 }}>만료</div>
            <div style={{ fontSize: 12 }}>{issued.key.expiresAt ? fmtDateTime(issued.key.expiresAt) : <span className="faint">무기한</span>}</div>
          </div>
          <div>
            <div className="muted" style={{ fontSize: 12 }}>id</div>
            <div className="mono" style={{ fontSize: 12 }}>{issued.key.id}</div>
          </div>
        </div>

        <div>
          <div className="label">plaintext API key</div>
          <div style={{ position: 'relative' }}>
            <div style={{
              fontFamily: 'var(--mono)', fontSize: 12, lineHeight: 1.5,
              padding: '14px 16px 14px 16px', paddingRight: 96,
              background: 'var(--surface-3)', borderRadius: 8,
              border: '1px solid var(--border)',
              wordBreak: 'break-all',
              color: 'var(--text)',
            }}>
              <span style={{ color: 'var(--accent)', fontWeight: 600 }}>{issued.key.prefix}</span>.<span>{issued.plaintext.slice(issued.key.prefix.length + 1)}</span>
            </div>
            <button className="btn btn--primary btn--sm" style={{ position: 'absolute', top: 8, right: 8 }} onClick={async () => {
              const ok = await copyToClipboard(issued.plaintext);
              if (ok) setCopied(true);
              else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다. 키를 직접 선택해 복사하세요.' });
            }}>
              {copied ? <><Icons.Check size={12} /> 복사됨</> : <><Icons.Copy size={12} /> 클립보드</>}
            </button>
          </div>
        </div>

        <label style={{ display: 'flex', gap: 10, padding: 12, background: checked ? 'var(--success-soft)' : 'var(--warning-soft)', borderRadius: 8, alignItems: 'flex-start', cursor: 'pointer', border: `1px solid ${checked ? 'color-mix(in oklab, var(--success) 25%, transparent)' : 'color-mix(in oklab, var(--warning) 25%, transparent)'}` }}>
          <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} style={{ marginTop: 2 }} />
          <div style={{ fontSize: 13 }}>
            <div style={{ fontWeight: 600, color: checked ? 'var(--success)' : 'var(--warning)' }}>안전한 장소에 복사했습니다.</div>
            <div style={{ color: 'var(--text-soft)', marginTop: 2, fontSize: 12 }}>1Password, AWS Secrets Manager 등 보안 저장소에 보관하세요. 닫기 후에는 재조회 불가능합니다.</div>
          </div>
        </label>
      </div>
    </Dialog>
  );
}

// ── RevokeKeyDialog ───────────────────────────────────────────────────────────

function RevokeKeyDialog({ k, onClose, onConfirm }: {
  k: ApiKey | null;
  onClose: () => void;
  onConfirm: (k: ApiKey) => void;
}) {
  if (!k) return null;
  return (
    <Dialog open onClose={onClose} title="API key를 회수하시겠습니까?"
      sub="회수된 키는 다음 ceremony부터 401을 받습니다. 캐시 만료까지 약 5초 이내에 완전 차단됩니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--danger" onClick={() => onConfirm(k)}>회수</button>
      </>}
    >
      <div style={{ padding: 14, border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface-2)' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '100px 1fr', rowGap: 8, fontSize: 13 }}>
          <div className="muted">prefix</div><div className="mono">{k.prefix}</div>
          <div className="muted">이름</div><div>{k.name}</div>
          <div className="muted">생성</div><div className="muted">{fmtDateTime(k.createdAt)}</div>
        </div>
      </div>
      <div style={{ marginTop: 12, padding: 10, background: 'var(--danger-soft)', color: 'var(--danger)', borderRadius: 6, fontSize: 12, display: 'flex', gap: 8 }}>
        <Icons.Alert size={14} />
        <span>이 작업은 되돌릴 수 없습니다. RP 서비스에 새 키가 배포되어 있는지 확인하세요.</span>
      </div>
    </Dialog>
  );
}

// ── RotateKeyDialog ───────────────────────────────────────────────────────────

function RotateKeyDialog({ k, onClose, onConfirm }: {
  k: ApiKey | null;
  onClose: () => void;
  onConfirm: (k: ApiKey) => void;
}) {
  if (!k) return null;
  return (
    <Dialog open onClose={onClose} title="API key를 회전하시겠습니까?"
      sub="같은 권한의 새 키가 즉시 발급됩니다. 구 키는 24시간 후 만료됩니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" onClick={() => onConfirm(k)}>회전 실행</button>
      </>}
    >
      <div style={{ padding: 14, border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface-2)' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '100px 1fr', rowGap: 8, fontSize: 13 }}>
          <div className="muted">prefix</div><div className="mono">{k.prefix}</div>
          <div className="muted">이름</div><div>{k.name}</div>
        </div>
      </div>
      <div style={{ marginTop: 12, padding: 10, background: 'var(--info-soft)', color: 'var(--info)', borderRadius: 6, fontSize: 12, display: 'flex', gap: 8 }}>
        <Icons.Info size={14} />
        <span>새 키는 발급 직후 이 화면에서 한 번만 표시됩니다. RP 서버를 24시간 안에 교체하세요.</span>
      </div>
    </Dialog>
  );
}
