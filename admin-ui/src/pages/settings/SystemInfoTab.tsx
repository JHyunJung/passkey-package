import { useEffect, useState } from 'react';
import { systemInfoApi, type SystemInfoData, type SystemInfoComponent } from '@/api/systemInfo';
import { Icons } from '@/icons/Icons';
import { illustrations } from '@/assets/illustrations';
import { kstDate, kstTime } from '@/lib/kst';
import { Badge, Button, CardBody, CardHeader, EmptyState, KpiCard, type BadgeTone } from '@/ui';

type ComponentStatus = SystemInfoComponent['status'];

const STATUS: Record<ComponentStatus, { tone: BadgeTone; label: string; dot: string }> = {
  OK: { tone: 'ok', label: '정상', dot: 'var(--ok)' },
  DEGRADED: { tone: 'warn', label: '저하', dot: 'var(--warn-dot)' },
  DOWN: { tone: 'bad', label: '중단', dot: 'var(--bad)' },
};

/** 전체 상태 = 가장 나쁜 컴포넌트 상태. */
function overallStatus(components: SystemInfoComponent[]): ComponentStatus {
  if (components.some((c) => c.status === 'DOWN')) return 'DOWN';
  if (components.some((c) => c.status === 'DEGRADED')) return 'DEGRADED';
  return 'OK';
}

const CAPTION = { margin: 0 } as const;

export default function SystemInfoTab() {
  const [info, setInfo] = useState<SystemInfoData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    systemInfoApi.get()
      .then((d) => { if (!cancelled) setInfo(d); })
      .catch(() => { if (!cancelled) setError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [attempt]);

  if (loading) {
    return (
      <div className="kpis kpis--3" aria-busy="true">
        <span className="sr-only">System Info 로딩 중…</span>
        {[0, 1, 2].map((i) => (
          <div key={i} className="kpi">
            <span className="skeleton" style={{ width: 80 }} />
            <span className="skeleton" style={{ width: 120, height: 22, marginTop: 8 }} />
          </div>
        ))}
      </div>
    );
  }

  if (error || !info) {
    return (
      <section className="card">
        <EmptyState
          illustration={illustrations.loadError}
          title="System Info 를 불러오지 못했습니다."
          description="네트워크 상태를 확인한 뒤 다시 시도하세요."
          action={<Button onClick={() => setAttempt((a) => a + 1)}>다시 시도</Button>}
        />
      </section>
    );
  }

  const overall = overallStatus(info.components);
  const okCount = info.components.filter((c) => c.status === 'OK').length;
  const profiles = info.host.environment.split(',').map((p) => p.trim()).filter(Boolean);

  return (
    <div className="stack-6">
      <div className="kpis kpis--3">
        <KpiCard
          label="전체 상태"
          value={<span className="row" style={{ gap: 8 }}><span className="dot" style={{ width: 10, height: 10, background: STATUS[overall].dot }} />{STATUS[overall].label}</span>}
          hint={`컴포넌트 ${okCount} / ${info.components.length}`}
        />
        <KpiCard
          label="Server 버전"
          value={<span className="mono" style={{ fontSize: 22 }}>{info.serverVersion}</span>}
          hint={info.deployedAt ? `${info.deployedAt.slice(0, 10)} 배포` : '배포 시각 정보 없음'}
        />
        <KpiCard label="Uptime" value={`${info.uptimeDays}d`} />
      </div>

      <div className="grid-2">
        <section className="card">
          <CardHeader title="백엔드 컴포넌트" />
          <ul aria-label="백엔드 컴포넌트" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {info.components.map((c, i) => (
              <li
                key={i}
                className="row"
                style={{ gap: 12, flexWrap: 'nowrap', padding: '12px 20px', borderBottom: i < info.components.length - 1 ? '1px solid var(--line-soft)' : 0 }}
              >
                <span
                  aria-hidden="true"
                  style={{ width: 30, height: 30, borderRadius: 8, background: 'var(--canvas)', border: '1px solid var(--line)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: 'var(--ink-2)', flex: 'none' }}
                >
                  <Icons.Server size={15} />
                </span>
                <span className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                  <span className="mono truncate">{c.name}</span>
                  {c.note && <span className="hint" style={CAPTION}>{c.note}</span>}
                </span>
                <span className="hint mono truncate" style={{ ...CAPTION, maxWidth: '45%' }} title={c.version}>{c.version}</span>
                {c.latencyMs != null && <span className="hint" style={CAPTION}>{c.latencyMs}ms</span>}
                <Badge tone={STATUS[c.status].tone}>{STATUS[c.status].label}</Badge>
              </li>
            ))}
          </ul>
        </section>

        <section className="card">
          <CardHeader title="호스트 정보" actions={<Badge tone="info" dot={false}>환경 · <span>{info.host.environment}</span></Badge>} />
          <CardBody>
            <dl className="kv" style={{ gridTemplateColumns: '120px minmax(0, 1fr)' }}>
              <dt>활성 프로필</dt>
              <dd>
                <span className="chips">{profiles.map((p) => <span key={p} className="tag">{p}</span>)}</span>
              </dd>
              <dt>배포 시각</dt>
              <dd className="mono" style={{ fontSize: 12 }}>{info.deployedAt ? `${kstDate(info.deployedAt)} ${kstTime(info.deployedAt)} KST` : '—'}</dd>
            </dl>
          </CardBody>
        </section>
      </div>
    </div>
  );
}
