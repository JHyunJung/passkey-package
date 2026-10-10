// admin-ui/src/pages/TenantsListPage.tsx
import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { tenantsApi } from '@/api/tenants';
import { apiKeysApi } from '@/api/apiKeys';
import type { Tenant } from '@/api/designTypes';
import type { ApiKeyView } from '@/api/types';
import { downloadCsv } from '@/lib/csvExport';
import { kstDate, kstToday } from '@/lib/kst';
import { isExpiringSoon } from '@/lib/apiKeyExpiry';
import { statusLabel } from '@/i18n/labels';
import { illustrations } from '@/assets/illustrations';
import {
  Button, Card, DataTable, Dialog, EmptyState, Field, KpiCard, PageHeader, Pagination,
  RemovableChip, Segmented, StatusBadge, useToast, type Column,
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
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [keys, setKeys] = useState<ApiKeyView[] | null>(null);
  const [showNew, setShowNew] = useState(false);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);
  const navigate = useNavigate();
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const list = await tenantsApi.list();
      setTenants(list);
      setLoadFailed(false);
    } catch (e: unknown) {
      const err = e as { message?: string };
      setLoadFailed(true);
      toast({ kind: 'err', title: 'tenant 목록 로드 실패', message: err?.message });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []);

  // 만료 임박 키 계산용 전체 키 목록. 실패해도 목록 화면은 그대로 — KPI 보조 줄만 숨긴다.
  useEffect(() => {
    apiKeysApi.listAll().then(setKeys).catch(() => setKeys(null));
  }, []);

  function onOpen(id: string) {
    navigate(`/tenants/${id}?tab=overview`);
  }

  async function handleCreate(input: { name: string; slug: string; rpId: string }) {
    try {
      const t = await tenantsApi.create(input);
      toast({ kind: 'ok', title: 'Tenant 생성 완료', message: `${t.name} · WebAuthn 설정으로 이동합니다` });
      setShowNew(false);
      // 안내문("다음 단계: WebAuthn config …")과 버튼 라벨("생성하고 설정으로 이동")대로,
      // 생성 직후 새 테넌트의 WebAuthn config 탭으로 이동시켜 온보딩 흐름을 잇는다.
      navigate(`/tenants/${t.id}?tab=webauthn`);
    } catch (e: unknown) {
      const err = e as { message?: string };
      toast({ kind: 'err', title: '생성 실패', message: err?.message || '오류' });
    }
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

      <div className="kpis kpis--3">
        <KpiCard
          label="활성 Tenant"
          value={fmt(counts.ACTIVE)}
          hint={`전체 ${fmt(counts.ALL)}건 · 클릭하면 활성만 보기`}
          selected={statusFilter === 'ACTIVE'}
          onClick={() => applyStatus(statusFilter === 'ACTIVE' ? 'ALL' : 'ACTIVE')}
        />
        <KpiCard
          label="등록 Credential"
          value={fmt(totalCredentials)}
          hint={`모든 tenant 합산${credDelta !== undefined ? ` · 7일 ${signed(credDelta)}` : ''}`}
        />
        <KpiCard
          label="유효 API Key"
          value={fmt(totalKeys)}
          hint={<>ACTIVE 만{expiringKeys !== null && <> · <span style={{ color: 'var(--warn)', fontWeight: 600 }}>{`만료 임박 ${expiringKeys}`}</span></>}</>}
        />
      </div>

      {loading ? (
        <Card><div className="empty"><p className="empty__sub">불러오는 중…</p></div></Card>
      ) : loadFailed && tenants.length === 0 ? (
        <Card>
          <EmptyState
            illustration={illustrations.loadError}
            title="tenant 목록을 불러오지 못했습니다"
            description="서버에 연결할 수 없거나 접근 권한이 없습니다."
            action={<Button icon={<Icons.Refresh size={14} />} onClick={() => void reload()}>다시 시도</Button>}
          />
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
  onCreate: (i: { name: string; slug: string; rpId: string }) => void;
}) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [rpId, setRpId] = useState('');
  const [touched, setTouched] = useState(false);
  const slugRe = /^[a-z0-9][a-z0-9-]{1,62}$/;
  const slugOk = slugRe.test(slug);
  // rpId 는 WebAuthn RP ID — registrable domain(hostname). 스킴/포트/경로 없이
  // 점이 포함된 호스트명이어야 한다(예: passkey.acme.com). placeholder 그대로 저장 방지.
  const rpIdRe = /^(?!.*\.example\.com$)([a-z0-9-]+\.)+[a-z]{2,}$/;
  const rpIdOk = rpIdRe.test(rpId.trim());

  // Reset form when dialog is closed (open: true → false) so stale input
  // doesn't bleed into the next open. This also runs when a create fails and
  // the parent leaves the dialog open — state is preserved until actual close.
  useEffect(() => {
    if (!open) { setName(''); setSlug(''); setRpId(''); setTouched(false); }
  }, [open]);

  function submit() {
    setTouched(true);
    if (!name || !slugOk || !rpIdOk) return;
    onCreate({ name, slug, rpId: rpId.trim() });
    // Do NOT clear here — let the parent's handleCreate success path call
    // setShowNew(false), which triggers the useEffect above to reset state.
  }

  const slugError = touched && slug && !slugOk
    ? '영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요.'
    : undefined;
  const rpIdError = touched && rpId && !rpIdOk
    ? '스킴·포트·경로 없이 실제 도메인 hostname을 입력하세요(예: passkey.acme.com). example.com은 placeholder라 사용할 수 없습니다.'
    : undefined;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="신규 Tenant 생성"
      sub="RP 회사 하나에 대한 격리된 Passkey 환경을 만듭니다."
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" onClick={submit}>생성하고 설정으로 이동</Button>
      </>}
    >
      <Field label="표시 이름" htmlFor="nt-name">
        <input id="nt-name" className="input" placeholder="예: Acme Corp" value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Slug (영문 식별자)" htmlFor="nt-slug" help="소문자·숫자·하이픈. URL 과 로그에서 tenant 를 식별합니다." error={slugError}>
        <input id="nt-slug" className="input mono" placeholder="acme-corp" value={slug}
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
        <input id="nt-rpid" className="input mono" placeholder="예: passkey.acme.com" value={rpId}
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
