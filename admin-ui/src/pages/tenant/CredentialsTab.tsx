// admin-ui/src/pages/tenant/CredentialsTab.tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import {
  Button, Card, ConfirmByTyping, CopyBtn, DataTable, Dialog, EmptyState, FilterChip, IconButton, Pagination, RemovableChip,
  Segmented, StatusBadge, useToast, type Column,
} from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { credentialsApi } from '@/api/credentials';
import { downloadCsv } from '@/lib/csvExport';
import type { Tenant, Credential } from '@/api/designTypes';
import CredentialDetailDialog from './CredentialDetailDialog';
import { KvList } from './KvList';
import { fmtNum, timeAgo } from './tabFormat';
import { applyClientFilter, authenticatorLabel, hasClientFilter, type StatusFilter } from './credentialHelpers';

const PAGE_SIZES = [50, 100, 200];
const SEARCH_DEBOUNCE_MS = 300;
const tail = (s: string, n: number) => s.slice(-n);

export default function CredentialsTab({ tenant }: { tenant: Tenant }) {
  const [items, setItems] = useState<Credential[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);                 // 0-based (서버 page)
  const [size, setSize] = useState(50);                // 50/100/200
  const [q, setQ] = useState('');                      // 입력 중 검색어
  const [appliedQ, setAppliedQ] = useState('');        // 서버에 보낸 검색어(q)
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [aaguid, setAaguid] = useState('');
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [loading, setLoading] = useState(true);
  const [revoking, setRevoking] = useState<Credential | null>(null);
  const [selected, setSelected] = useState<Credential | null>(null);
  // 상세 이전/다음이 페이지 경계를 넘을 때, 새 페이지 로드 후 선택할 위치
  const pendingSelect = useRef<'first' | 'last' | null>(null);
  const toast = useToast();

  // 검색어 → 서버 q (디바운스, 바뀌면 첫 페이지로)
  useEffect(() => {
    const next = q.trim();
    if (next === appliedQ) return;
    const t = setTimeout(() => { setAppliedQ(next); setPage(0); }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [q, appliedQ]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    credentialsApi.list(tenant.id, page, size, appliedQ || undefined)
      .then((res) => {
        if (cancelled) return;
        setItems(res.items);
        setTotal(res.total);
        const pend = pendingSelect.current;
        pendingSelect.current = null;
        if (pend && res.items.length > 0) setSelected(pend === 'first' ? res.items[0] : res.items[res.items.length - 1]);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        pendingSelect.current = null;
        toast({ kind: 'err', title: 'credentials 로드 실패', message: e instanceof Error ? e.message : String(e) });
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tenant.id, page, size, appliedQ]);

  async function reload() {
    setLoading(true);
    try {
      const res = await credentialsApi.list(tenant.id, page, size, appliedQ || undefined);
      setItems(res.items);
      setTotal(res.total);
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'credentials 로드 실패', message: e instanceof Error ? e.message : String(e) });
    } finally {
      setLoading(false);
    }
  }

  const clientFiltered = hasClientFilter({ aaguid, status });
  const visible = useMemo(() => applyClientFilter(items, { aaguid, status }), [items, aaguid, status]);
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);

  function clearAll() {
    setQ('');
    setAppliedQ('');
    setAaguid('');
    setStatus('ALL');
    setPage(0);
  }

  // ── 상세 이전/다음 ──
  const selIdx = selected ? visible.findIndex((c) => c.credentialId === selected.credentialId) : -1;
  const canPrev = selIdx > 0 || (selIdx === 0 && !clientFiltered && page > 0);
  const canNext = (selIdx >= 0 && selIdx < visible.length - 1) || (selIdx === visible.length - 1 && selIdx >= 0 && !clientFiltered && page < lastPage);
  function goPrev() {
    if (selIdx > 0) { setSelected(visible[selIdx - 1]); return; }
    pendingSelect.current = 'last';
    setPage((p) => p - 1);
  }
  function goNext() {
    if (selIdx < visible.length - 1) { setSelected(visible[selIdx + 1]); return; }
    pendingSelect.current = 'first';
    setPage((p) => p + 1);
  }
  const position = selIdx >= 0
    ? (clientFiltered ? { index: selIdx + 1, total: visible.length } : { index: page * size + selIdx + 1, total })
    : undefined;

  async function handleRevoke() {
    if (!revoking) return;
    try {
      await credentialsApi.revoke(tenant.id, revoking.credentialId);
      toast({ kind: 'warn', title: 'Credential이 회수되었습니다.', message: `${tail(revoking.credentialId, 12)} · ${revoking.externalUserId}` });
      setRevoking(null);
      await reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: '회수 실패', message: e instanceof Error ? e.message : String(e) });
    }
  }

  function exportCsv() {
    if (visible.length === 0) return;
    downloadCsv(
      `credentials-${tenant.slug}-${new Date().toISOString().slice(0, 10)}.csv`,
      ['credentialId', 'externalUserId', 'label', 'authenticatorName', 'status', 'aaguid', 'transports', 'signatureCounter', 'lastUsedAt', 'createdAt'],
      visible.map((c) => [
        c.credentialId, c.externalUserId, c.nickname ?? '', c.authenticatorName ?? '', c.status, c.aaguid ?? '',
        c.transports.join('|'), c.signatureCounter, c.lastUsedAt ?? '', c.createdAt,
      ]),
    );
  }

  const columns: Column<Credential>[] = [
    {
      key: 'id', header: 'Credential ID',
      render: (c) => (
        <span className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
          <span className="mono truncate" style={{ fontSize: 12, maxWidth: 120 }} title={c.credentialId}>{c.credentialId}</span>
          <CopyBtn value={c.credentialId} label="Credential ID 복사" iconOnly />
        </span>
      ),
    },
    { key: 'user', header: '사용자', render: (c) => <span className="mono" style={{ fontSize: 12 }}>{c.externalUserId}</span> },
    { key: 'nick', header: '별칭', render: (c) => c.nickname ? <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{c.nickname}</span> : <span className="muted">—</span> },
    {
      key: 'authn', header: '인증기',
      render: (c) => (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }} title={c.aaguid ?? undefined}>
          <span aria-hidden="true" style={{ width: 22, height: 22, borderRadius: 6, background: 'var(--brand-100)', color: 'var(--brand-700)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}>
            <Icons.Fingerprint size={13} />
          </span>
          <span className={c.authenticatorName ? undefined : 'mono'} style={c.authenticatorName ? { fontSize: 13 } : { fontSize: 12, color: 'var(--mute)' }}>{authenticatorLabel(c)}</span>
        </span>
      ),
    },
    { key: 'tr', header: '전송 방식', render: (c) => <span className="row" style={{ gap: 4 }}>{c.transports.map((t) => <span key={t} className="tag">{t}</span>)}</span> },
    {
      key: 'cnt', header: '서명 카운터', align: 'right',
      render: (c) => c.signatureCounter > 0
        ? <span className="mono" style={{ fontSize: 12 }}>{c.signatureCounter}</span>
        : c.lastUsedAt
          // 인증됐는데 counter=0 → counterless 인증기(플랫폼·싱크 패스키). WebAuthn 정상, counter 기반 복제 탐지 미적용.
          ? <span className="mono" style={{ fontSize: 12, color: 'var(--mute)' }} title="counterless 인증기 — 플랫폼·싱크 패스키는 signCount가 0으로 고정됩니다(WebAuthn 정상). counter 기반 복제 탐지 미적용.">N/A</span>
          : <span className="mono" style={{ fontSize: 12, color: 'var(--mute)' }}>0</span>,
    },
    { key: 'st', header: '상태', render: (c) => <StatusBadge status={c.status} /> },
    { key: 'used', header: '마지막 사용', render: (c) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{timeAgo(c.lastUsedAt)}</span> },
    {
      key: 'revoke', header: '회수', srOnlyHeader: true, width: 48,
      render: (c) => c.status === 'ACTIVE' ? (
        <IconButton label={`회수 · …${tail(c.credentialId, 8)}`} tone="danger" icon={<Icons.Trash size={15} />}
          onClick={(e) => { e.stopPropagation(); setRevoking(c); }} />
      ) : null,
    },
  ];

  const noData = total === 0 && appliedQ === '' && !clientFiltered;
  const empty = noData
    ? <EmptyState illustration={illustrations.emptyCredentials} title="Credential이 없습니다" description="사용자가 RP 에서 패스키를 등록하면 여기에 나타납니다." />
    : <EmptyState illustration={illustrations.noResults} title="검색 결과가 없습니다." description="검색어나 필터를 바꿔 보세요." action={<Button size="sm" onClick={clearAll}>필터 지우기</Button>} />;

  return (
    <div className="stack-6">
      <Card>
        <div className="card__head">
          <div className="search" style={{ flex: '0 1 340px' }}>
            <Icons.Search size={15} />
            <input className="input" aria-label="Credential 검색" placeholder="externalUserId · credentialId 검색" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <FilterChip active={filtersOpen} onClick={() => setFiltersOpen((v) => !v)}><Icons.Plus size={13} />필터 · aaguid · status</FilterChip>
          {aaguid.trim() !== '' && <RemovableChip mono removeLabel="aaguid 필터 제거" onRemove={() => setAaguid('')}>aaguid: {aaguid.trim()}</RemovableChip>}
          {status !== 'ALL' && <RemovableChip removeLabel="status 필터 제거" onRemove={() => setStatus('ALL')}>status: {status}</RemovableChip>}
          <div className="spacer" />
          <span className="hint" style={{ margin: 0 }}>{clientFiltered ? `${fmtNum(visible.length)}건 (현재 페이지)` : `${fmtNum(total)}건`}</span>
          <Button icon={<Icons.Download size={14} />} onClick={exportCsv} disabled={visible.length === 0}>CSV</Button>
        </div>
        {filtersOpen && (
          <div className="row" style={{ gap: 16, padding: '10px 20px', borderBottom: '1px solid var(--line-soft)', background: 'var(--canvas)' }}>
            <label className="row" style={{ gap: 6, fontSize: 12, color: 'var(--mute)', flexWrap: 'nowrap' }}>
              aaguid
              <input className="input mono" style={{ width: 280, minHeight: 30 }} placeholder="예: ea9b8d66" value={aaguid} onChange={(e) => setAaguid(e.target.value)} />
            </label>
            <Segmented ariaLabel="status 필터" value={status} onChange={setStatus}
              options={[{ value: 'ALL', label: '전체' }, { value: 'ACTIVE', label: 'ACTIVE' }, { value: 'REVOKED', label: 'REVOKED' }]} />
            <span className="hint" style={{ margin: 0 }}>현재 페이지 안에서 거릅니다.</span>
          </div>
        )}
        {loading
          ? <p className="muted" style={{ padding: '40px 20px', margin: 0, textAlign: 'center', fontSize: 13 }}>불러오는 중…</p>
          : <DataTable columns={columns} rows={visible} rowKey={(c) => c.credentialId} onRowClick={setSelected} empty={empty} />}
        <Pagination page={page + 1} pageSize={size} total={total} onPage={(p) => setPage(p - 1)}
          pageSizeOptions={PAGE_SIZES} onPageSize={(n) => { setSize(n); setPage(0); }} />
      </Card>

      {revoking && (
        <RevokeCredentialDialog key={revoking.credentialId} c={revoking} onClose={() => setRevoking(null)} onConfirm={handleRevoke} />
      )}

      {selected && (
        <CredentialDetailDialog
          c={selected}
          tenantId={tenant.id}
          onClose={() => setSelected(null)}
          position={position}
          onPrev={!loading && canPrev ? goPrev : undefined}
          onNext={!loading && canNext ? goNext : undefined}
          onRevoke={(c) => { setSelected(null); setRevoking(c); }}
        />
      )}
    </div>
  );
}

// ── Credential 회수 — 마지막 8자 확인 ────────────────────────────────────────

function RevokeCredentialDialog({ c, onClose, onConfirm }: { c: Credential; onClose: () => void; onConfirm: () => void }) {
  const [ok, setOk] = useState(false);
  const required = tail(c.credentialId, 8);
  return (
    <Dialog open onClose={onClose} role="alertdialog" title="Credential 회수" sub="이 사용자는 해당 패스키로 더 이상 로그인할 수 없습니다."
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="danger-solid" disabled={!ok} onClick={onConfirm}>확인 — 회수</Button>
      </>}>
      <KvList keyWidth={100} items={[
        { k: 'credentialId', v: `…${required}`, mono: true },
        { k: '사용자', v: c.externalUserId, mono: true },
        { k: '인증기', v: authenticatorLabel(c) },
      ]} />
      <ConfirmByTyping
        id="credential-revoke-confirm"
        expected={required}
        onMatchChange={setOk}
        label={<>확인을 위해 마지막 8자 <span className="mono" style={{ background: 'var(--sunk)', padding: '0 4px', borderRadius: 'var(--r-xs)' }}>{required}</span> 를 입력하세요</>}
      />
    </Dialog>
  );
}
