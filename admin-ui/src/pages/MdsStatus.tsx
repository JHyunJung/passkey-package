import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { ApiError } from '../api/types';
import type { MdsStatusView, SyncResult } from '../api/types';
import { useToast } from '../components/Toast';
import { Refresh, Activity } from '../components/Icons';
import { formatDateTime, formatDate } from '../lib/formatDateTime';
import PlatformOnlyGuard from '../components/PlatformOnlyGuard';

export default function MdsStatus() {
  const [status, setStatus] = useState<MdsStatusView | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [last, setLast] = useState<SyncResult | null>(null);
  const toast = useToast();

  function refresh() {
    api.get<MdsStatusView>('/admin/api/mds/status').then(setStatus).catch(() => {});
  }

  useEffect(refresh, []);

  async function sync() {
    setSyncing(true);
    try {
      const r = await api.post<SyncResult>('/admin/api/mds/sync', {});
      setLast(r);
      toast({ kind: r.status === 'SYNCED' ? 'ok' : 'warn', title: `Sync ${r.status}`, message: r.error });
      refresh();
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({ kind: 'err', title: 'Sync 실패', message: e?.serverMessage ?? String(err), traceId: e?.traceId });
    } finally {
      setSyncing(false);
    }
  }

  return (
    <PlatformOnlyGuard>
      <div className="stack-6">
        <div className="page__head">
          <div>
            <h1 className="page__title">MDS Status</h1>
            <div className="page__sub">FIDO Alliance Metadata Service BLOB 동기화 상태.</div>
          </div>
          <button className="btn btn--primary" onClick={sync} disabled={syncing}>
            <Refresh size={14} /> {syncing ? '동기화 중…' : '지금 동기화'}
          </button>
        </div>

        <div className="grid-3">
          <Metric label="VERSION" value={status?.version != null ? String(status.version) : '—'} sub="현재 BLOB" />
          <Metric label="NEXT UPDATE" value={formatDate(status?.nextUpdate)} sub="MDS 권고 다음 갱신" />
          <Metric label="LAST FETCHED" value={formatDateTime(status?.fetchedAt)} sub="KST" />
        </div>

        {last && (
          <div className={`banner banner--${last.status === 'SYNCED' ? 'success' : last.status === 'SKIPPED' ? 'info' : 'danger'}`}>
            <Activity size={16} className="banner__icon" />
            <div>
              <div className="banner__title">Last sync: {last.status} {last.version != null && `· v${last.version}`}</div>
              {last.error && <div className="banner__body">{last.error}</div>}
            </div>
          </div>
        )}
      </div>
    </PlatformOnlyGuard>
  );
}

function Metric({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card" style={{ padding: 'var(--card-pad)' }}>
      <div className="metric-label">{label}</div>
      <div className="metric-value" style={{ fontSize: 20 }}>{value}</div>
      {sub && <div className="metric-delta">{sub}</div>}
    </div>
  );
}
