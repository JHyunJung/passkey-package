import { useEffect, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { systemInfoApi, type SystemInfoData, type SystemInfoComponent } from '@/api/systemInfo';

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

// ── ComponentRow ──────────────────────────────────────────────────────────────

function ComponentRow({ name, version, status, instances, note }: SystemInfoComponent) {
  return (
    <div className="row" style={{ justifyContent: 'space-between' }}>
      <div>
        <div style={{ fontWeight: 600, fontSize: 13 }}>{name}</div>
        {note && <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>{note}</div>}
      </div>
      <div className="row">
        <span className="mono muted" style={{ fontSize: 11 }}>{version}</span>
        <span className="mono muted" style={{ fontSize: 11 }}>× {instances}</span>
        <span className="badge badge--success badge--dot">{status}</span>
      </div>
    </div>
  );
}

// ── SystemInfoTab ─────────────────────────────────────────────────────────────

export default function SystemInfoTab() {
  const [info, setInfo] = useState<SystemInfoData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    systemInfoApi.get()
      .then((d) => { if (!cancelled) setInfo(d); })
      .catch(() => { if (!cancelled) setInfo(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  if (loading || !info) {
    return (
      <div className="page">
        <div style={{ padding: 40, color: 'var(--text-mute)' }}>System Info 로딩 중…</div>
      </div>
    );
  }

  return (
    <div className="stack-4">
      <div className="grid-3">
        <MetricCard label="Server 버전" value={info.serverVersion} sub={`${info.deployedAt.slice(0, 10)} 배포`} />
        <MetricCard label="API 응답 (p95)" value={info.apiP95Ms != null ? `${info.apiP95Ms}ms` : '—'} sub={`평균 ${info.apiAvgMs ?? '—'}${info.apiAvgMs != null ? 'ms' : ''} · p99 ${info.apiP99Ms ?? '—'}${info.apiP99Ms != null ? 'ms' : ''}`} />
        <MetricCard label="Uptime" value={info.uptimePercent != null ? `${info.uptimePercent}%` : '—'} sub={`${info.uptimeDays}d · ${info.uptimeIncidentMinutes ?? '—'}${info.uptimeIncidentMinutes != null ? '분 incident' : ''}`} />
      </div>

      <div className="grid-2">
        <div className="card">
          <div className="card__head"><h3 className="card__title">백엔드 컴포넌트</h3></div>
          <div className="card__body stack-3">
            {info.components.map((c, i) => (
              <ComponentRow key={i} {...c} />
            ))}
          </div>
        </div>

        <div className="card">
          <div className="card__head"><h3 className="card__title">호스트 정보</h3></div>
          <div className="card__body stack-3">
            <KvLine k="API hostname" v={<code className="mono" style={{ background: 'var(--surface-3)', padding: '2px 8px', borderRadius: 4, fontSize: 12 }}>{info.host.apiHostname}</code>} />
            <KvLine k="Admin console" v={<code className="mono" style={{ background: 'var(--surface-3)', padding: '2px 8px', borderRadius: 4, fontSize: 12 }}>{info.host.adminConsole}</code>} />
            <KvLine k="region" v={info.host.region} />
            <KvLine k="환경" v={<span className="badge badge--violet">{info.host.environment}</span>} />
            <KvLine k="배포 방식" v={info.host.deployMethod} />
          </div>
        </div>
      </div>
    </div>
  );
}
