// admin-ui/src/pages/TenantsListPage.tsx
import { useState, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { tenantsApi } from '@/api/tenants';
import { apiKeysApi } from '@/api/apiKeys';
import type { Tenant } from '@/api/designTypes';
import type { ApiKeyView } from '@/api/types';
import { downloadCsv } from '@/lib/csvExport';
import { kstDate, kstToday } from '@/lib/kst';
import { isExpiringSoon } from '@/lib/apiKeyExpiry';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { statusLabel } from '@/i18n/labels';
import { illustrations } from '@/assets/illustrations';
import {
  Alert, Button, Card, DataTable, Dialog, EmptyState, Field, KpiCard, LoadError, PageHeader, Pagination,
  RemovableChip, Segmented, Skeleton, StatusBadge, useToast, type Column,
} from '@/ui';

// ── local utilities ──────────────────────────────────────────────────────────

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

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function fmt(n: number): string {
  return n.toLocaleString();
}

function signed(n: number): string {
  return n >= 0 ? `+${fmt(n)}` : `−${fmt(Math.abs(n))}`;
}

type StatusFilter = 'ALL' | 'ACTIVE' | 'SUSPENDED';
const PAGE_SIZES = [50, 100];

// ── TenantsListPage ───────────────────────────────────────────────────────────

export default function TenantsListPage() {
  // 로드 상태는 useLoad 하나로: loading → Skeleton, failed(데이터 없음) → LoadError, 재시도 중에는 이전 데이터 유지.
  const list = useLoad(() => tenantsApi.list(), []);
  const loaded = list.data !== undefined;
  const tenants = useMemo<Tenant[]>(() => list.data ?? [], [list.data]);
  const [keys, setKeys] = useState<ApiKeyView[] | null>(null);
  const [showNew, setShowNew] = useState(false);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);
  const navigate = useNavigate();
  const toast = useToast();

  // 만료 임박 키 계산용 전체 키 목록. 실패해도 목록 화면은 그대로 — KPI 보조 줄만 숨긴다.
  useEffect(() => {
    apiKeysApi.listAll().then(setKeys).catch(() => setKeys(null));
  }, []);

  function onOpen(id: string) {
    navigate(`/tenants/${id}?tab=overview`);
  }

  // 실패하면 throw 한다 — NewTenantDialog 가 다이얼로그 안에 인라인으로 보여 준다(토스트 중복 없음).
  async function handleCreate(input: { name: string; slug: string; rpId: string }) {
    const t = await tenantsApi.create(input);
    toast({ kind: 'ok', title: 'Tenant 생성 완료', message: `${t.name} · WebAuthn 설정으로 이동합니다` });
    setShowNew(false);
    // 안내문("다음 단계: WebAuthn config …")과 버튼 라벨("생성하고 설정으로 이동")대로,
    // 생성 직후 새 테넌트의 WebAuthn config 탭으로 이동시켜 온보딩 흐름을 잇는다.
    navigate(`/tenants/${t.id}?tab=webauthn`);
  }

  function applyStatus(s: StatusFilter) { setStatusFilter(s); setPage(1); }
  function applySearch(v: string) { setQ(v); setPage(1); }
  function clearFilters() { setStatusFilter('ALL'); setQ(''); setPage(1); }

  const filtered = useMemo(
    () =>
      tenants.filter((t) => {
        const matchesQ =
          !q ||
          t.name.toLowerCase().includes(q.toLowerCase()) ||
          t.slug.includes(q.toLowerCase());
        const matchesStatus = statusFilter === 'ALL' || t.status === statusFilter;
        return matchesQ && matchesStatus;
      }),
    [q, statusFilter, tenants],
  );

  const counts = useMemo(() => ({
    ALL: tenants.length,
    ACTIVE: tenants.filter((t) => t.status === 'ACTIVE').length,
    SUSPENDED: tenants.filter((t) => t.status === 'SUSPENDED').length,
  }), [tenants]);

  const totalCredentials = useMemo(() => tenants.reduce((a, t) => a + t.credentials, 0), [tenants]);
  const totalKeys = useMemo(() => tenants.reduce((a, t) => a + t.apiKeys, 0), [tenants]);
  const credDelta = useMemo(() => {
    const ds = tenants.map((t) => t.credentialsDelta7d).filter((d): d is number => d !== undefined);
    return ds.length > 0 ? ds.reduce((a, b) => a + b, 0) : undefined;
  }, [tenants]);
  const expiringKeys = useMemo(() => {
    if (keys === null) return null;
    const now = new Date();
    return keys.filter((k) => isExpiringSoon(k, now)).length;
  }, [keys]);

  const pageRows = filtered.slice((page - 1) * pageSize, page * pageSize);
  const hasFilter = statusFilter !== 'ALL' || q !== '';

  const columns: Column<Tenant>[] = [
    {
      key: 'tenant', header: 'Tenant',
      render: (t) => (
        <div className="row" style={{ gap: 10, flexWrap: 'nowrap', minWidth: 0 }}>
          <span className="avatar avatar--sq" style={{ width: 30, height: 30, fontSize: 13 }} aria-hidden="true">{t.name.slice(0, 1).toUpperCase()}</span>
          <div className="col" style={{ minWidth: 0 }}>
            <strong className="truncate" style={{ fontSize: 13, maxWidth: 260 }} title={t.name}>{t.name}</strong>
            <span className="mono muted" style={{ fontSize: 12 }}>{`…${tail(t.id, 8)}`}</span>
          </div>
        </div>
      ),
    },
    { key: 'slug', header: '슬러그', render: (t) => <span className="mono" style={{ fontSize: 12 }}>{t.slug}</span> },
    {
      key: 'rpId', header: 'RP ID',
      render: (t) => <span className="mono truncate" style={{ fontSize: 12, display: 'inline-block', maxWidth: 240, verticalAlign: 'middle' }} title={t.rpId}>{t.rpId}</span>,
    },
    { key: 'credentials', header: 'Credentials', align: 'right', render: (t) => fmt(t.credentials) },
    { key: 'apiKeys', header: 'API 키', align: 'right', render: (t) => fmt(t.apiKeys) },
    { key: 'status', header: '상태', render: (t) => <StatusBadge status={t.status} /> },
    { key: 'lastEvent', header: '마지막 이벤트', render: (t) => <span className="muted">{timeAgo(t.lastEventAt)}</span> },
    { key: 'createdAt', header: '생성일', render: (t) => <span className="muted">{t.createdAt ? kstDate(t.createdAt) : '—'}</span> },
    {
      key: 'open', header: '열기', srOnlyHeader: true, width: 40,
      render: () => <span style={{ color: 'var(--faint)', display: 'inline-flex' }}><Icons.ChevronRight size={16} /></span>,
    },
  ];

  return (
    <div className="page">
      {/* '신규 tenant'(POST /admin/api/tenants)는 PLATFORM_OPERATOR 전용. 이 페이지(/tenants)
          자체가 App.tsx 의 RequirePlatform 으로 감싸져 있어 RP_ADMIN 은 진입 시 자기 테넌트로
          redirect 된다 — 따라서 여기서 별도 버튼 게이팅은 불필요(가드가 단일 진입점). */}
      <PageHeader
        title="Tenants"
        sub="RP 회사별로 격리된 Passkey 환경 — 각 tenant 는 자체 rpId · credential · API 키를 가집니다."
        actions={<Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>신규 tenant</Button>}
      />

      {/* 로드 전·실패 시 빈 배열로 계산한 0 을 보이지 않는다 — 실제 값이 없으면 "—". */}
      <div className="kpis kpis--3">
        <KpiCard
          label="활성 Tenant"
          value={loaded ? fmt(counts.ACTIVE) : '—'}
          hint={loaded ? `전체 ${fmt(counts.ALL)}건 · 클릭하면 활성만 보기` : undefined}
          selected={statusFilter === 'ACTIVE'}
          onClick={loaded ? () => applyStatus(statusFilter === 'ACTIVE' ? 'ALL' : 'ACTIVE') : undefined}
        />
        <KpiCard
          label="등록 Credential"
          value={loaded ? fmt(totalCredentials) : '—'}
          hint={loaded ? `모든 tenant 합산${credDelta !== undefined ? ` · 7일 ${signed(credDelta)}` : ''}` : undefined}
        />
        <KpiCard
          label="유효 API Key"
          value={loaded ? fmt(totalKeys) : '—'}
          hint={loaded ? <>ACTIVE 만{expiringKeys !== null && <> · <span style={{ color: 'var(--warn)', fontWeight: 600 }}>{`만료 임박 ${expiringKeys}`}</span></>}</> : undefined}
        />
      </div>

      {!loaded ? (
        <Card>
          {list.status === 'failed'
            ? <LoadError what="tenant 목록" error={list.error} onRetry={list.reload} retrying={list.reloading} />
            : <Skeleton rows={6} />}
        </Card>
      ) : tenants.length === 0 ? (
        <Card>
          <EmptyState
            illustration={illustrations.emptyTenants}
            title="아직 tenant 가 없습니다"
            description="RP 회사마다 tenant 를 만들면 rpId · credential · API 키가 격리됩니다. 생성 후 WebAuthn 설정 → AAGUID 정책 → API key 발급 순으로 진행하세요."
            action={<Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>신규 tenant</Button>}
          />
        </Card>
      ) : (
        <Card>
          <div className="card__head">
            <div className="search" style={{ flex: '0 1 280px' }}>
              <Icons.Search size={15} />
              <input className="input" aria-label="tenant 검색" placeholder="name · slug 검색" value={q} onChange={(e) => applySearch(e.target.value)} />
            </div>
            <Segmented<StatusFilter>
              ariaLabel="상태"
              value={statusFilter}
              onChange={applyStatus}
              options={[
                { value: 'ALL', label: `전체 ${counts.ALL}` },
                { value: 'ACTIVE', label: `활성 ${counts.ACTIVE}` },
                { value: 'SUSPENDED', label: `정지 ${counts.SUSPENDED}` },
              ]}
            />
            <div className="spacer" />
            <span className="muted" style={{ fontSize: 12 }}>{`${filtered.length} / ${tenants.length}건`}</span>
            <Button
              icon={<Icons.Download size={14} />}
              onClick={() =>
                downloadCsv(
                  `tenants-${kstToday()}.csv`,
                  ['name', 'slug', 'rpId', 'status', 'credentials', 'apiKeys', 'createdAt'],
                  filtered.map((t) => [t.name, t.slug, t.rpId, t.status, t.credentials, t.apiKeys, t.createdAt]),
                )
              }
            >
              CSV
            </Button>
          </div>

          {hasFilter && (
            <div className="applied">
              <span className="muted" style={{ fontSize: 12 }}>적용된 필터</span>
              {statusFilter !== 'ALL' && (
                <RemovableChip removeLabel="상태 필터 제거" onRemove={() => applyStatus('ALL')}>{`상태: ${statusLabel(statusFilter)}`}</RemovableChip>
              )}
              {q !== '' && (
                <RemovableChip removeLabel="검색어 제거" onRemove={() => applySearch('')}>{`검색: ${q}`}</RemovableChip>
              )}
              <Button variant="ghost" size="sm" onClick={clearFilters}>모두 지우기</Button>
            </div>
          )}

          <DataTable
            columns={columns}
            rows={pageRows}
            rowKey={(t) => t.id}
            onRowClick={(t) => onOpen(t.id)}
            empty={
              <EmptyState
                compact
                illustration={illustrations.noResults}
                title="검색 결과가 없습니다"
                description="검색어나 상태 필터를 바꿔 보세요."
                action={<Button size="sm" onClick={clearFilters}>필터 모두 지우기</Button>}
              />
            }
          />

          <Pagination
            page={page}
            pageSize={pageSize}
            total={filtered.length}
            onPage={setPage}
            pageSizeOptions={PAGE_SIZES}
            onPageSize={(n) => { setPageSize(n); setPage(1); }}
          />
        </Card>
      )}

      {showNew && (
        <NewTenantDialog
          open={showNew}
          onClose={() => setShowNew(false)}
          onCreate={handleCreate}
        />
      )}
    </div>
  );
}

// ── NewTenantDialog ───────────────────────────────────────────────────────────

const NEXT_STEPS = [
  { title: 'WebAuthn config', desc: 'origin · UV · attestation' },
  { title: 'AAGUID 정책', desc: '허용할 인증기' },
  { title: 'API key 발급', desc: 'RP 서버 연동' },
];

export function NewTenantDialog({ open, onClose, onCreate }: {
  open: boolean;
  onClose: () => void;
  /** 거부(throw)하면 다이얼로그 안에 인라인 경고를 보이고 입력값을 유지한다. 성공 시 닫기는 호출자가 한다. */
  onCreate: (i: { name: string; slug: string; rpId: string }) => Promise<void> | void;
}) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [rpId, setRpId] = useState('');
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<unknown>(null);
  const slugRe = /^[a-z0-9][a-z0-9-]{1,62}$/;
  const slugOk = slugRe.test(slug);
  // rpId 는 WebAuthn RP ID — registrable domain(hostname). 스킴/포트/경로 없이
  // 점이 포함된 호스트명이어야 한다(예: passkey.acme.com). placeholder 그대로 저장 방지.
  const rpIdRe = /^(?!.*\.example\.com$)([a-z0-9-]+\.)+[a-z]{2,}$/;
  const rpIdOk = rpIdRe.test(rpId.trim());

  // 닫힐 때(open: true → false)만 폼을 비운다. 생성 실패로 열린 채 남으면 입력값은 유지된다.
  useEffect(() => {
    if (!open) { setName(''); setSlug(''); setRpId(''); setTouched(false); setError(null); }
  }, [open]);

  async function submit() {
    if (busyRef.current) return; // 연타 방지 — state 반영 전 두 번째 클릭도 막는다.
    setTouched(true);
    if (!name || !slugOk || !rpIdOk) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await onCreate({ name, slug, rpId: rpId.trim() });
      // 성공 시 닫기는 부모(handleCreate → setShowNew(false))가 한다.
    } catch (e) {
      setError(e);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  const slugError = touched && slug && !slugOk
    ? '영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요.'
    : undefined;
  const rpIdError = touched && rpId && !rpIdOk
    ? '스킴·포트·경로 없이 실제 도메인 hostname을 입력하세요(예: passkey.acme.com). example.com은 placeholder라 사용할 수 없습니다.'
    : undefined;

  const info = error ? describeError(error) : null;
  const meta = info ? [info.httpStatus ? `HTTP ${info.httpStatus}` : null, info.traceId ? `trace ${info.traceId}` : null].filter(Boolean).join(' · ') : '';

  return (
    <Dialog
      open={open}
      onClose={busy ? () => {} : onClose}
      dismissible={!busy}
      title="신규 Tenant 생성"
      sub="RP 회사 하나에 대한 격리된 Passkey 환경을 만듭니다."
      footer={<>
        <Button onClick={onClose} disabled={busy}>취소</Button>
        <Button variant="primary" onClick={() => void submit()} disabled={busy}>{busy ? '생성 중…' : '생성하고 설정으로 이동'}</Button>
      </>}
    >
      {info && (
        <Alert tone="bad" title="Tenant를 만들지 못했습니다">
          {`${info.title}. ${info.detail} 입력한 내용은 그대로 있습니다.`}
          {meta && <span className="mono" style={{ display: 'block', marginTop: 4, fontSize: 12 }}>{meta}</span>}
        </Alert>
      )}
      <Field label="표시 이름" htmlFor="nt-name">
        <input id="nt-name" className="input" placeholder="예: Acme Corp" value={name} readOnly={busy} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Slug (영문 식별자)" htmlFor="nt-slug" help="소문자·숫자·하이픈. URL 과 로그에서 tenant 를 식별합니다." error={slugError}>
        <input id="nt-slug" className="input mono" placeholder="acme-corp" value={slug} readOnly={busy}
               aria-invalid={!!slugError} aria-describedby={slugError ? 'nt-slug-error' : undefined}
               onChange={(e) => setSlug(e.target.value.toLowerCase())} />
      </Field>
      <Field
        label="RP ID"
        htmlFor="nt-rpid"
        error={rpIdError}
        help={
          <span className="row" style={{ gap: 5, color: 'var(--warn)', flexWrap: 'nowrap' }}>
            <Icons.Lock size={13} />
            <span>생성 후에는 변경할 수 없습니다. 기존 credential 이 이 값에 묶입니다.</span>
          </span>
        }
      >
        <input id="nt-rpid" className="input mono" placeholder="예: passkey.acme.com" value={rpId} readOnly={busy}
               aria-invalid={!!rpIdError} aria-describedby={rpIdError ? 'nt-rpid-error' : undefined}
               onChange={(e) => setRpId(e.target.value.toLowerCase().trim())} />
      </Field>

      <div className="stack-2" style={{ gap: 10, padding: '14px 16px', borderRadius: 'var(--r)', background: 'var(--brand-50)', border: '1px solid var(--brand-100)' }}>
        <span className="t-over" id="nt-next-steps" style={{ color: 'var(--brand-700)' }}>다음 단계</span>
        <ol aria-labelledby="nt-next-steps" className="stack-2" style={{ listStyle: 'none', margin: 0, padding: 0, gap: 10 }}>
          {NEXT_STEPS.map((s, i) => (
            <li key={s.title} className="row" style={{ gap: 10, flexWrap: 'nowrap', fontSize: 13 }}>
              <span aria-hidden="true" style={{ width: 22, height: 22, borderRadius: '50%', border: '1.5px solid var(--brand-700)', color: 'var(--brand-700)', fontSize: 11, fontWeight: 700, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}>{i + 1}</span>
              <strong>{s.title}</strong>
              <span className="muted">{s.desc}</span>
            </li>
          ))}
        </ol>
      </div>
    </Dialog>
  );
}
