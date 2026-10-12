// admin-ui/src/pages/tenant/CredentialsTab.tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import {
  Alert, Button, Card, ConfirmDialog, CopyBtn, DataTable, EmptyState, FilterChip, IconButton, LoadError, Pagination, RemovableChip,
  Segmented, Skeleton, StatusBadge, useToast, type Column,
} from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { credentialsApi } from '@/api/credentials';
import { downloadCsv } from '@/lib/csvExport';
import { kstToday } from '@/lib/kst';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import type { Tenant, Credential } from '@/api/designTypes';
import CredentialDetailDialog from './CredentialDetailDialog';
import { KvList } from './KvList';
import { fmtNum, timeAgo } from './tabFormat';
import { applyClientFilter, authenticatorLabel, hasClientFilter, type StatusFilter } from './credentialHelpers';

const PAGE_SIZES = [50, 100, 200];
const SEARCH_DEBOUNCE_MS = 300;
const tail = (s: string, n: number) => s.slice(-n);

const TRANSPORT_LABELS: Record<string, string> = {
  internal: '내장', usb: 'USB', nfc: 'NFC', ble: '블루투스', hybrid: '휴대폰 연동', 'smart-card': '스마트카드',
};
const STATUS_FILTER_LABELS: Record<StatusFilter, string> = { ALL: '전체', ACTIVE: '활성', REVOKED: '회수됨' };

export default function CredentialsTab({ tenant }: { tenant: Tenant }) {
  const [page, setPage] = useState(0);                 // 0-based (서버 page)
  const [size, setSize] = useState(50);                // 50/100/200
  const [q, setQ] = useState('');                      // 입력 중 검색어
  const [appliedQ, setAppliedQ] = useState('');        // 서버에 보낸 검색어(q)
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [aaguid, setAaguid] = useState('');
  const [status, setStatus] = useState<StatusFilter>('ALL');
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

  // useLoad 는 최신 요청만 반영한다(늦게 끝난 이전 검색·페이지 응답은 버림).
  // 응답마다 어떤 화면(tenant·페이지·크기·검색어)의 것인지 기억해 둔다.
  const viewKey = `${tenant.id}|${page}|${size}|${appliedQ}`;
  const dataView = useRef(new WeakMap<object, string>());
  const load = useLoad(
    () => credentialsApi.list(tenant.id, page, size, appliedQ || undefined).then((r) => { dataView.current.set(r, viewKey); return r; }),
    [tenant.id, page, size, appliedQ],
  );
  const items = useMemo(() => load.data?.items ?? [], [load.data]);
  const total = load.data?.total ?? 0;
  // 받아 둔 목록이 지금 보는 화면의 것일 때만, 새로고침 실패에도 그 목록을 남기고 경고만 띄운다.
  // 다른 페이지·검색어 요청이 실패했는데 이전 행을 새 위치처럼 보이면 안 되므로 그때는 전체 오류.
  const sameView = load.data !== undefined && dataView.current.get(load.data) === viewKey;
  const failed = load.status === 'failed' && !sameView;
  const staleFailed = load.status === 'failed' && sameView;
  const busy = load.status === 'loading' || load.reloading;

  // 경계를 넘은 이전/다음: 새 페이지가 도착하면 첫/마지막 항목을 고른다. 실패하면 대기만 지운다.
  useEffect(() => {
    if (load.status === 'loading') return;
    const pend = pendingSelect.current;
    if (!pend) return;
    pendingSelect.current = null;
    if (load.status === 'ok' && items.length > 0) setSelected(pend === 'first' ? items[0] : items[items.length - 1]);
  }, [load.status, items]);

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

  /** ConfirmDialog 의 onConfirm — 실패하면 throw 해서 다이얼로그 안에 오류를 보인다. */
  async function handleRevoke(c: Credential) {
    await credentialsApi.revoke(tenant.id, c.credentialId);
    toast({ kind: 'ok', title: 'Credential을 회수했습니다.', message: `${tail(c.credentialId, 12)} · ${c.externalUserId}` });
    // 회수는 이미 성공했다. 다이얼로그는 바로 닫고, 목록 새로고침의 로딩·실패는 목록이 다룬다.
    void load.reload();
  }

  function exportCsv() {
    if (visible.length === 0) return;
    downloadCsv(
      `credentials-${tenant.slug}-${kstToday()}.csv`,
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
    { key: 'user', header: '사용자', render: (c) => <span className="mono truncate" style={{ fontSize: 12, display: 'inline-block', maxWidth: 200, verticalAlign: 'middle' }} title={c.externalUserId}>{c.externalUserId}</span> },
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
    { key: 'tr', header: '전송 방식', render: (c) => <span className="row" style={{ gap: 4 }}>{c.transports.map((t) => <span key={t} className="tag" title={t}>{TRANSPORT_LABELS[t] ?? t}</span>)}</span> },
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
            <input className="input" aria-label="Credential 검색" placeholder="Credential ID · 사용자 ID 검색"
              title="Credential ID 또는 사용자 ID 의 일부로 찾습니다. 별칭·인증기 이름은 검색되지 않습니다."
              value={q} disabled={failed} onChange={(e) => setQ(e.target.value)} />
          </div>
          <FilterChip active={filtersOpen} onClick={() => setFiltersOpen((v) => !v)}><Icons.Plus size={13} />필터 · aaguid · 상태</FilterChip>
          {aaguid.trim() !== '' && <RemovableChip mono removeLabel="aaguid 필터 제거" onRemove={() => setAaguid('')}>aaguid: {aaguid.trim()}</RemovableChip>}
          {status !== 'ALL' && <RemovableChip removeLabel="상태 필터 제거" onRemove={() => setStatus('ALL')}><span title={status}>상태: {STATUS_FILTER_LABELS[status]}</span></RemovableChip>}
          <div className="spacer" />
          <span className="hint" style={{ margin: 0 }}>
            {failed ? '—' : clientFiltered ? `이 페이지 ${fmtNum(items.length)}건 중 ${fmtNum(visible.length)}건` : `${fmtNum(total)}건`}
          </span>
          <Button icon={<Icons.Download size={14} />} onClick={exportCsv} disabled={failed || visible.length === 0}>
            CSV 내보내기 (현재 페이지 {fmtNum(visible.length)}건)
          </Button>
        </div>
        {filtersOpen && (
          <div className="row" style={{ gap: 16, padding: '10px 20px', borderBottom: '1px solid var(--line-soft)', background: 'var(--canvas)' }}>
            <label className="row" style={{ gap: 6, fontSize: 12, color: 'var(--mute)', flexWrap: 'nowrap' }}>
              aaguid
              <input className="input mono" style={{ width: 280, minHeight: 30 }} placeholder="예: ea9b8d66" value={aaguid} onChange={(e) => setAaguid(e.target.value)} />
            </label>
            <Segmented ariaLabel="상태 필터" value={status} onChange={setStatus}
              options={[{ value: 'ALL', label: '전체' }, { value: 'ACTIVE', label: '활성', title: 'ACTIVE' }, { value: 'REVOKED', label: '회수됨', title: 'REVOKED' }]} />
            <span className="hint" style={{ margin: 0 }}>현재 페이지 안에서 거릅니다.</span>
          </div>
        )}
        {staleFailed && (() => {
          const info = describeError(load.error);
          return (
            <Alert tone="warn" title="Credential 목록을 새로 고치지 못했습니다"
              actions={<Button variant="secondary" size="sm" onClick={() => void load.reload()} disabled={load.reloading}>{load.reloading ? '다시 시도 중…' : '다시 시도'}</Button>}>
              {info.title}. {info.detail} 아래 목록은 이전에 불러온 내용입니다.
            </Alert>
          );
        })()}
        {failed
          ? <LoadError what="Credential 목록" error={load.error} onRetry={load.reload} retrying={load.reloading} />
          : load.status === 'loading'
            ? <Skeleton rows={6} label="Credential 목록 불러오는 중" />
            : <DataTable columns={columns} rows={visible} rowKey={(c) => c.credentialId} onRowClick={setSelected} empty={empty} />}
        {!failed && (
          <Pagination page={page + 1} pageSize={size} total={total} onPage={(p) => setPage(p - 1)}
            pageSizeOptions={PAGE_SIZES} onPageSize={(n) => { setSize(n); setPage(0); }} />
        )}
      </Card>

      {revoking && (
        <RevokeCredentialDialog key={revoking.credentialId} c={revoking} onClose={() => setRevoking(null)} onConfirm={() => handleRevoke(revoking)} />
      )}

      {selected && (
        <CredentialDetailDialog
          c={selected}
          tenantId={tenant.id}
          onClose={() => setSelected(null)}
          position={position}
          onPrev={!busy && canPrev ? goPrev : undefined}
          onNext={!busy && canNext ? goNext : undefined}
          onRevoke={(c) => { setSelected(null); setRevoking(c); }}
        />
      )}
    </div>
  );
}

// ── Credential 회수 — L3 마지막 8자 입력 확인 ────────────────────────────────

function RevokeCredentialDialog({ c, onClose, onConfirm }: { c: Credential; onClose: () => void; onConfirm: () => Promise<void> }) {
  const required = tail(c.credentialId, 8);
  return (
    <ConfirmDialog open onClose={onClose} level="L3" verb="회수" title="Credential을 회수할까요?"
      sub="이 사용자는 해당 패스키로 더 이상 로그인할 수 없습니다. 되돌릴 수 없습니다."
      typeToConfirm={{
        expected: required,
        label: <>확인을 위해 마지막 8자 <span className="mono" style={{ background: 'var(--sunk)', padding: '0 4px', borderRadius: 'var(--r-xs)' }}>{required}</span> 를 입력하세요</>,
      }}
      onConfirm={onConfirm}>
      <KvList keyWidth={100} items={[
        { k: 'credentialId', v: `…${required}`, mono: true },
        { k: '사용자', v: c.externalUserId, mono: true },
        { k: '인증기', v: authenticatorLabel(c) },
      ]} />
    </ConfirmDialog>
  );
}
