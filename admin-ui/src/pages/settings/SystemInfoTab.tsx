import { useEffect, useState } from 'react';
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

const STATUS_BADGE_CLASS: Record<SystemInfoComponent['status'], string> = {
  OK: 'badge--success',
  DEGRADED: 'badge--warning',
  DOWN: 'badge--danger',
};

function ComponentRow({ name, version, status, note }: SystemInfoComponent) {
  return (
    <div className="row" style={{ justifyContent: 'space-between' }}>
      <div>
        <div style={{ fontWeight: 600, fontSize: 13 }}>{name}</div>
        {note && <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>{note}</div>}
      </div>
      <div className="row">
        <span className="mono muted" style={{ fontSize: 11 }}>{version}</span>
        <span className={`badge ${STATUS_BADGE_CLASS[status]} badge--dot`}>{status}</span>
      </div>
    </div>
  );
}

// ── SystemInfoTab ─────────────────────────────────────────────────────────────

export default function SystemInfoTab() {
  const [info, setInfo] = useState<SystemInfoData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    systemInfoApi.get()
      .then((d) => { if (!cancelled) setInfo(d); })
      .catch(() => { if (!cancelled) setError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  if (loading) {
    return (
      <div className="page">
        <div style={{ padding: 40, color: 'var(--text-mute)' }}>System Info 로딩 중…</div>
      </div>
    );
  }

  if (error || !info) {
    return (
      <div className="page">
        <div style={{ padding: 40, color: 'var(--text-mute)' }}>System Info 를 불러오지 못했습니다.</div>
      </div>
    );
  }

  return (
    <div className="stack-4">
      <div className="grid-2">
        <MetricCard
          label="Server 버전"
          value={info.serverVersion}
          sub={info.deployedAt ? `${info.deployedAt.slice(0, 10)} 배포` : '배포 시각 정보 없음'}
        />
        <MetricCard label="Uptime" value={`${info.uptimeDays}d`} sub="현재 프로세스 기동 후 경과" />
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
            <KvLine k="환경" v={<span className="badge badge--violet">{info.host.environment}</span>} />
          </div>
        </div>
      </div>
    </div>
  );
}
