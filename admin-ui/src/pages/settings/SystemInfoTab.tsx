import { systemInfoApi, type SystemInfoComponent } from '@/api/systemInfo';
import { Icons } from '@/icons/Icons';
import { kstDateTimeMin } from '@/lib/kst';
import { useLoad } from '@/lib/useLoad';
import { Badge, CardBody, CardHeader, KpiCard, LoadError, Skeleton, type BadgeTone } from '@/ui';

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

/** 해석 가능한 시각만 KST 로 표시한다. 없거나 깨진 값은 null. */
function deployedKst(iso: string | null): string | null {
  if (!iso || Number.isNaN(new Date(iso).getTime())) return null;
  return `${kstDateTimeMin(iso)} KST`;
}

const CAPTION = { margin: 0 } as const;

export default function SystemInfoTab() {
  const load = useLoad(() => systemInfoApi.get(), []);

  if (load.status === 'failed') {
    return (
      <section className="card">
        <LoadError what="시스템 정보" error={load.error} onRetry={load.reload} retrying={load.reloading} />
      </section>
    );
  }
  const info = load.data;
  if (!info) {
    return <section className="card"><Skeleton rows={6} label="시스템 정보 불러오는 중" /></section>;
  }

  const overall = overallStatus(info.components);
  const okCount = info.components.filter((c) => c.status === 'OK').length;
  const profiles = info.host.environment.split(',').map((p) => p.trim()).filter(Boolean);
  const deployed = deployedKst(info.deployedAt);

  return (
    <div className="stack-6">
      <div className="kpis kpis--3">
        <KpiCard
          label="전체 상태"
          value={<span className="row" style={{ gap: 8 }}><span className="dot" style={{ width: 10, height: 10, background: STATUS[overall].dot }} />{STATUS[overall].label}</span>}
          hint={`컴포넌트 ${okCount} / ${info.components.length}`}
        />
        <KpiCard
          label="서버 버전"
          value={<span className="mono" style={{ fontSize: 22 }}>{info.serverVersion}</span>}
          hint={deployed ? `${deployed} 배포` : '배포 시각 정보 없음'}
        />
        {/* 시간 단위 세분화는 서버 지원 후(스펙 §8). */}
        <KpiCard label="가동 시간" value={info.uptimeDays != null && Number.isFinite(info.uptimeDays) ? `${info.uptimeDays}일` : '—'} />
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
              <dd className="mono" style={{ fontSize: 12 }}>{deployed ?? '—'}</dd>
            </dl>
          </CardBody>
        </section>
      </div>
    </div>
  );
}
