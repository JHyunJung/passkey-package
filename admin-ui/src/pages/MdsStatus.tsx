import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { MdsStatusView, SyncResult } from '../api/types';

export default function MdsStatus() {
  const [status, setStatus] = useState<MdsStatusView | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [lastResult, setLastResult] = useState<SyncResult | null>(null);

  function refresh() {
    api.get<MdsStatusView>('/admin/api/mds/status').then(setStatus);
  }

  useEffect(refresh, []);

  async function forceSync() {
    setSyncing(true);
    try {
      const r = await api.post<SyncResult>('/admin/api/mds/sync', {});
      setLastResult(r);
      refresh();
    } finally {
      setSyncing(false);
    }
  }

  return (
    <div>
      <h2>MDS Status</h2>
      {status === null ? (
        <p>Loading…</p>
      ) : (
        <table border={1} cellPadding={4} cellSpacing={0}>
          <tbody>
            <tr><th>Version</th><td>{status.version}</td></tr>
            <tr><th>Next update</th><td>{status.nextUpdate ?? '-'}</td></tr>
            <tr><th>Last fetched</th><td>{status.fetchedAt ?? '(never)'}</td></tr>
          </tbody>
        </table>
      )}
      <p style={{ marginTop: '1rem' }}>
        <button onClick={forceSync} disabled={syncing}>
          {syncing ? 'Syncing…' : 'Force sync now'}
        </button>
      </p>
      {lastResult && (
        <p>
          Last result: <code>{lastResult.status}</code>
          {lastResult.version != null && ` (version ${lastResult.version})`}
          {lastResult.error && ` — ${lastResult.error}`}
        </p>
      )}
    </div>
  );
}
