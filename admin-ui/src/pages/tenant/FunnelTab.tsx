import { useState, useEffect } from 'react';
import { funnelApi } from '@/api/funnel';
import type { FunnelData, FunnelSeries, FunnelByEventType } from '@/api/funnel';
import type { Tenant } from '@/api/designTypes';

// ── Local util ────────────────────────────────────────────────────────────────

function fmt(n: number): string {
  return new Intl.NumberFormat('ko-KR').format(n);
}

// ── Types ─────────────────────────────────────────────────────────────────────

type FunnelTabProps = { tenant: Tenant };

// ── FunnelTab ─────────────────────────────────────────────────────────────────

export default function FunnelTab({ tenant }: FunnelTabProps) {
  const [windowDays, setWindowDays] = useState<1 | 7 | 30>(7);
  const [data, setData] = useState<FunnelData | null>(null);

  useEffect(() => {
    funnelApi.get(tenant.id, windowDays).then(setData);
  }, [tenant.id, windowDays]);

  if (!data) return null;

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">Conversion Funnel</h3>
            <div className="card__sub">ceremony 단계별 시도/성공 비율. 최근 {windowDays}일.</div>
          </div>
          <div className="row" style={{ gap: 4 }}>
            {([1, 7, 30] as (1 | 7 | 30)[]).map((d) => (
              <button key={d} onClick={() => setWindowDays(d)} className="btn btn--sm" style={{ background: windowDays === d ? 'var(--accent-soft)' : 'var(--surface)', color: windowDays === d ? 'var(--accent)' : 'var(--text)', borderColor: windowDays === d ? 'var(--accent)' : 'var(--border)' }}>{d === 1 ? '24h' : `${d}d`}</button>
            ))}
          </div>
        </div>
        <div className="card__body">
          <Funnel f={data} />
        </div>
      </div>

      <div className="grid-2">
        <div className="card">
          <div className="card__head"><h3 className="card__title">일별 인증 시도 vs 성공</h3></div>
          <div className="card__body">
            <BarChart data={data.series} />
          </div>
        </div>
        <div className="card">
          <div className="card__head"><h3 className="card__title">이벤트 타입별 분포</h3></div>
          <div className="card__body">
            <EventDistribution data={data.byEventType} />
          </div>
        </div>
      </div>
    </div>
  );
}

// ── Funnel ────────────────────────────────────────────────────────────────────

function Funnel({ f }: { f: FunnelData }) {
  const reg = f.registration; const auth = f.authentication;
  const steps = [
    { label: '등록 시도', value: reg.attempts, color: 'var(--info)', ratio: undefined as number | undefined },
    { label: '등록 성공', value: reg.success, color: 'var(--accent)', ratio: reg.ratio },
    { label: '인증 시도', value: auth.attempts, color: 'var(--violet)', ratio: undefined as number | undefined },
    { label: '인증 성공', value: auth.success, color: 'var(--success)', ratio: auth.ratio },
  ];
  const max = Math.max(...steps.map((s) => s.value));
  return (
    <div className="stack-3">
      {steps.map((s) => (
        <div key={s.label}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <div className="row" style={{ gap: 8 }}>
              <span style={{ width: 8, height: 8, borderRadius: 999, background: s.color }} />
              <span style={{ fontWeight: 500, fontSize: 13 }}>{s.label}</span>
              {s.ratio !== undefined && <span className="badge badge--success" style={{ fontSize: 10 }}>{(s.ratio * 100).toFixed(1)}%</span>}
            </div>
            <div className="mono" style={{ fontSize: 13, fontWeight: 600 }}>{fmt(s.value)}</div>
          </div>
          <div style={{ height: 8, background: 'var(--surface-3)', borderRadius: 999, overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${(s.value / max) * 100}%`, background: s.color, borderRadius: 999, transition: 'width 600ms ease' }} />
          </div>
        </div>
      ))}
    </div>
  );
}

// ── BarChart ──────────────────────────────────────────────────────────────────

function BarChart({ data }: { data: FunnelSeries[] }) {
  const max = Math.max(...data.map((d) => d.attempts));
  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: `repeat(${data.length}, 1fr)`, alignItems: 'flex-end', gap: 14, height: 160, padding: '0 4px' }}>
        {data.map((d) => (
          <div key={d.day} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
            <div style={{ position: 'relative', width: '100%', maxWidth: 32, height: 130, display: 'flex', alignItems: 'flex-end' }}>
              <div style={{ width: '100%', height: `${(d.attempts / max) * 100}%`, background: 'var(--accent-soft-2)', borderRadius: '4px 4px 0 0', position: 'relative' }}>
                <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: `${(d.success / d.attempts) * 100}%`, background: 'var(--accent)', borderRadius: '4px 4px 0 0' }} />
              </div>
            </div>
            <div className="muted" style={{ fontSize: 11 }}>{d.day}</div>
          </div>
        ))}
      </div>
      <div className="row" style={{ marginTop: 10, gap: 14, fontSize: 11, color: 'var(--text-mute)' }}>
        <div className="row" style={{ gap: 6 }}><span style={{ width: 10, height: 8, background: 'var(--accent-soft-2)', borderRadius: 2 }} /> 시도</div>
        <div className="row" style={{ gap: 6 }}><span style={{ width: 10, height: 8, background: 'var(--accent)', borderRadius: 2 }} /> 성공</div>
      </div>
    </div>
  );
}

// ── EventDistribution ─────────────────────────────────────────────────────────

function EventDistribution({ data }: { data: FunnelByEventType[] }) {
  const total = data.reduce((a, b) => a + b.n, 0);
  const palette = ['var(--info)', 'var(--accent)', 'var(--violet)', 'var(--success)', 'var(--warning)', 'var(--danger)'];
  return (
    <div className="stack-3">
      <div style={{ display: 'flex', height: 12, borderRadius: 6, overflow: 'hidden' }}>
        {data.map((d, i) => (
          <div key={d.type} style={{ flexBasis: `${(d.n / total) * 100}%`, background: palette[i % palette.length] }} title={`${d.type}: ${d.n}`} />
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6 }}>
        {data.map((d, i) => (
          <div key={d.type} className="row" style={{ justifyContent: 'space-between', fontSize: 12 }}>
            <div className="row" style={{ gap: 6 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: palette[i % palette.length] }} />
              <span className="mono" style={{ fontSize: 11 }}>{d.type}</span>
            </div>
            <span className="mono muted">{fmt(d.n)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
