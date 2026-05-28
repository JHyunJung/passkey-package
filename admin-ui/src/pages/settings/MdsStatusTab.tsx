import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { mdsStatusApi, type MdsStatus } from '@/api/mdsStatus';
import { useToast } from '@/shell/ToastHost';

// ── Helpers ───────────────────────────────────────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}초 전`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  return `${Math.floor(h / 24)}일 전`;
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// ── Fixture sync history (server has no history endpoint) ─────────────────────

const syncHistoryFixture = [
  { ts: '2026-05-16T03:00:00Z', ver: '9 · 2026-05', changes: '+2 / -0 / ~1', ok: true, ms: 412 },
  { ts: '2026-05-15T03:00:00Z', ver: '9 · 2026-05', changes: '변경 없음',    ok: true, ms: 388 },
  { ts: '2026-05-14T03:00:00Z', ver: '9 · 2026-04', changes: '+1 / -0 / ~0', ok: true, ms: 401 },
  { ts: '2026-05-13T03:00:00Z', ver: '9 · 2026-04', changes: '변경 없음',    ok: true, ms: 372 },
  { ts: '2026-05-12T03:00:00Z', ver: '9 · 2026-04', changes: '변경 없음',    ok: true, ms: 421 },
];

// ── KvLine ────────────────────────────────────────────────────────────────────

function KvLine({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '180px 1fr', gap: 12, alignItems: 'center', fontSize: 13, padding: '6px 0' }}>
      <div className="muted" style={{ fontSize: 12 }}>{k}</div>
      <div>{v}</div>
    </div>
  );
}

// ── MetricCard ────────────────────────────────────────────────────────────────

function MetricCard({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) {
  return (
    <div className="card" style={{ padding: '16px 20px' }}>
      <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 700, lineHeight: 1 }}>{value}</div>
      {sub && <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>{sub}</div>}
    </div>
  );
}

// ── MdsStatusTab ──────────────────────────────────────────────────────────────

export default function MdsStatusTab() {
  const [status, setStatus] = useState<MdsStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const toast = useToast();

  async function load() {
    setLoading(true);
    try {
      const data = await mdsStatusApi.get();
      setStatus(data);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '알 수 없는 오류';
      toast({ kind: 'err', title: 'MDS 상태 로드 실패', message: msg });
    } finally {
      setLoading(false);
    }
  }

  async function handleSync() {
    setSyncing(true);
    try {
      const result = await mdsStatusApi.sync();
      if (result.status === 'SYNCED') {
        toast({ kind: 'ok', title: 'MDS 즉시 갱신 완료', message: `버전 ${result.version}` });
      } else if (result.status === 'SKIPPED') {
        toast({ kind: 'warn', title: 'MDS 갱신 건너뜀', message: '다른 인스턴스가 동기화 중입니다.' });
      } else {
        toast({ kind: 'err', title: 'MDS 갱신 실패', message: result.error ?? '알 수 없는 오류' });
      }
      await load();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '알 수 없는 오류';
      toast({ kind: 'err', title: 'MDS 갱신 실패', message: msg });
    } finally {
      setSyncing(false);
    }
  }

  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const version = status?.version != null ? `${status.version} · MDS` : '—';
  const trustAnchors = 287; // fixture — server status endpoint does not include this
  const lastFetch = status?.fetchedAt ?? null;

  if (loading) {
    return (
      <div className="stack-4">
        <div className="card">
          <div className="card__body muted" style={{ fontSize: 13 }}>MDS 상태 로딩 중…</div>
        </div>
      </div>
    );
  }

  return (
    <div className="stack-4">
      <div className="grid-3">
        <MetricCard label="MDS BLOB 버전" value={version} sub="FIDO Alliance 최신본" />
        <MetricCard label="Trust anchors" value={trustAnchors} sub="활성 authenticator 모델" />
        <MetricCard label="마지막 동기화" value={timeAgo(lastFetch)} sub={fmtDateTime(lastFetch)} />
      </div>

      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">MDS 동기화 상태</h3>
            <div className="card__sub">FIDO Metadata Service에서 authenticator 모델 정보를 매일 03:00 KST에 자동 갱신.</div>
          </div>
          <button
            className="btn btn--primary btn--sm"
            onClick={handleSync}
            disabled={syncing}
          >
            <Icons.Refresh size={12} /> {syncing ? '갱신 중…' : '즉시 갱신'}
          </button>
        </div>
        <div className="card__body stack-3">
          <KvLine k="endpoint" v={<code className="mono" style={{ background: 'var(--surface-3)', padding: '2px 8px', borderRadius: 4, fontSize: 12 }}>https://mds3.fidoalliance.org/</code>} />
          <KvLine k="next 갱신 예정" v={status?.nextUpdate ? `${status.nextUpdate} 03:00 KST` : '알 수 없음'} />
          <KvLine k="동기화 성공률 (30d)" v={<><span style={{ fontWeight: 600 }}>30 / 30</span> · <span className="badge badge--success">100%</span></>} />
          <KvLine k="현재 사용 중인 신뢰 모드" v={<span className="badge badge--info">MDS_STRICT_OPTIONAL</span>} />
        </div>
      </div>

      <div className="card">
        <div className="card__head">
          <h3 className="card__title">최근 동기화 이력</h3>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>시각</th>
              <th>버전</th>
              <th>변경</th>
              <th>Status</th>
              <th>응답 시간</th>
            </tr>
          </thead>
          <tbody>
            {syncHistoryFixture.map((r, i) => (
              <tr key={i}>
                <td><span className="muted">{fmtDateTime(r.ts)}</span></td>
                <td className="mono" style={{ fontSize: 12 }}>{r.ver}</td>
                <td>{r.changes}</td>
                <td>{r.ok ? <span className="badge badge--success badge--dot">OK</span> : <span className="badge badge--danger badge--dot">FAIL</span>}</td>
                <td className="mono muted" style={{ fontSize: 12 }}>{r.ms}ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
