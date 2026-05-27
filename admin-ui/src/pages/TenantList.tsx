import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { ApiError } from '../api/types';
import type { TenantView } from '../api/types';
import { useToast } from '../components/Toast';
import { Plus, Search, Building } from '../components/Icons';
import { formatDateTime } from '../lib/formatDateTime';

export default function TenantList() {
  const [tenants, setTenants] = useState<TenantView[]>([]);
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const toast = useToast();
  const nav = useNavigate();

  useEffect(() => {
    api.get<TenantView[]>('/admin/api/tenants')
      .then((r) => { setTenants(r); setLoading(false); })
      .catch((e: unknown) => {
        setLoading(false);
        if (e instanceof ApiError) {
          setFetchError(e.serverMessage ?? '테넌트 목록을 가져오지 못했습니다');
          toast({ kind: 'err', title: '테넌트 목록을 가져오지 못했습니다', message: e.serverMessage, traceId: e.traceId });
        } else {
          setFetchError('테넌트 목록을 가져오지 못했습니다');
          toast({ kind: 'err', title: '테넌트 목록을 가져오지 못했습니다' });
        }
      });
  }, [toast]);

  const filtered = useMemo(
    () => tenants.filter((t) =>
      !q || t.slug.toLowerCase().includes(q.toLowerCase())
         || (t.displayName ?? '').toLowerCase().includes(q.toLowerCase())
    ),
    [tenants, q]
  );

  const total = tenants.length;
  // Server emits lowercase "active"
  const activeCount = tenants.filter((t) => t.status === 'active').length;

  return (
    <div className="stack-6">
      <div className="page__head">
        <div>
          <h1 className="page__title">Tenants</h1>
          <div className="page__sub">RP 회사별 격리된 Passkey 환경. 모든 데이터가 tenant_id로 row-level 분리됩니다.</div>
        </div>
        <button className="btn btn--primary" onClick={() => nav('/tenants/new')}>
          <Plus size={14} /> 신규 tenant
        </button>
      </div>

      <div className="grid-4">
        <Metric label="활성 TENANT" value={String(activeCount)} sub={`전체 ${total}건`} />
        <Metric label="전체 TENANT" value={String(total)} />
        <Metric label="조회 결과" value={String(filtered.length)} sub="현재 필터 기준" />
        <Metric label="환경" value="prod" sub="Crosscert · multi-tenant" />
      </div>

      <div className="card">
        <div className="card__head">
          <div className="row" style={{ flex: 1, gap: 10 }}>
            <Search size={14} className="muted" />
            <input
              className="input"
              placeholder="tenant id · name 검색"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              style={{ flex: 1, maxWidth: 360 }}
            />
          </div>
          <span className="muted" style={{ fontSize: 12 }}>{filtered.length} / {total}건</span>
        </div>
        {loading ? (
          <div className="card__body"><Skeleton /></div>
        ) : fetchError ? (
          <div className="empty">
            <div className="empty__art"><Building size={20} /></div>
            <div className="empty__title">목록을 불러오지 못했습니다</div>
            <div className="empty__sub">{fetchError}</div>
          </div>
        ) : filtered.length === 0 ? (
          <div className="empty">
            <div className="empty__art"><Building size={20} /></div>
            <div className="empty__title">{q ? '검색 결과 없음' : '등록된 tenant 없음'}</div>
            <div className="empty__sub">{q ? '검색어를 바꿔보세요.' : '신규 tenant를 추가해 시작하세요.'}</div>
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>TENANT</th>
                <th>RP ID</th>
                <th>RP NAME</th>
                <th>ORIGINS</th>
                <th>UV</th>
                <th>MDS</th>
                <th>STATUS</th>
                <th>CREATED</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((t) => (
                <tr key={t.slug} onClick={() => nav('/tenants/' + t.id)} style={{ cursor: 'pointer' }}>
                  <td>
                    <div style={{ fontWeight: 500 }}>{t.displayName ?? t.slug}</div>
                    <div className="mono muted">{t.slug}</div>
                  </td>
                  <td className="mono">{t.rpId}</td>
                  <td>{t.rpName}</td>
                  <td className="mono">{t.allowedOrigins.length}개</td>
                  <td>{t.requireUserVerification ? '✓' : '—'}</td>
                  <td>{t.mdsRequired ? '✓' : '—'}</td>
                  <td><span className={`badge badge--${t.status === 'active' ? 'success' : 'warning'} badge--dot`}>{t.status}</span></td>
                  <td className="mono muted">{formatDateTime(t.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function Metric({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card" style={{ padding: 'var(--card-pad)' }}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      {sub && <div className="metric-delta">{sub}</div>}
    </div>
  );
}

function Skeleton() {
  return (
    <div className="stack-2">
      {[0, 1, 2, 3].map((i) => <div key={i} className="skeleton" style={{ height: 36 }} />)}
    </div>
  );
}
