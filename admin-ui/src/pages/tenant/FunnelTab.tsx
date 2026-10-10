// admin-ui/src/pages/tenant/FunnelTab.tsx
import { Fragment, useEffect, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { Alert, Button, Card, CardBody, CardHeader, DataTable, KpiCard, Segmented, type Column } from '@/ui';
import { funnelApi } from '@/api/funnel';
import type { FunnelData, FunnelSeries, FunnelByEventType } from '@/api/funnel';
import type { Tenant } from '@/api/designTypes';
import { downloadCsv } from '@/lib/csvExport';
import { fmtNum, ymd } from './tabFormat';
import { fmtDeltaPp, fmtPct, funnelSummary, priorPeriodDelta } from './funnelHelpers';

type Win = 1 | 7 | 30;
const WIN_OPTIONS: { value: string; label: string }[] = [
  { value: '1', label: '24h' }, { value: '7', label: '7d' }, { value: '30', label: '30d' },
];
const PERIOD_LABEL: Record<Win, string> = { 1: '최근 24시간', 7: '최근 7일', 30: '최근 30일' };
// 단계 막대: 옅은 → 진한 브랜드 (캔버스 4단계 블루)
const STEP_COLORS = ['var(--brand-300)', 'var(--brand-500)', 'var(--brand-600)', 'var(--brand-700)'];
const DIST_COLORS = ['var(--brand-700)', 'var(--brand-500)', 'var(--brand-300)', 'var(--brand-900)', 'var(--brand-600)'];
const today = () => ymd(new Date().toISOString());

export default function FunnelTab({ tenant }: { tenant: Tenant }) {
  const [windowDays, setWindowDays] = useState<Win>(7);
  const [data, setData] = useState<FunnelData | null>(null);
  const [failed, setFailed] = useState(false);
  const [series30, setSeries30] = useState<FunnelSeries[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    setData(null); // 기간·테넌트 전환 시 이전 숫자가 새 라벨 아래 보이지 않게
    funnelApi.get(tenant.id, windowDays)
      .then((d) => { if (!cancelled) setData(d); })
      .catch(() => { if (!cancelled) { setData(null); setFailed(true); } });
    return () => { cancelled = true; };
  }, [tenant.id, windowDays]);

  // 직전 기간 대비: 7d 에서만. 30일 series 로 최근 7일 vs 그 앞 7일 인증 성공률을 비교한다.
  useEffect(() => {
    setSeries30(null);
    if (windowDays !== 7) return;
    let cancelled = false;
    funnelApi.get(tenant.id, 30)
      .then((d) => { if (!cancelled) setSeries30(d.series); })
      .catch(() => { /* 비교 줄만 생략 */ });
    return () => { cancelled = true; };
  }, [tenant.id, windowDays]);

  const s = data ? funnelSummary(data) : null;
  const delta = windowDays === 7 && series30 ? priorPeriodDelta(series30, 7) : null;
  const now = Date.now();

  function exportFunnel() {
    if (!s) return;
    downloadCsv(
      `funnel-${tenant.slug}-${windowDays}d-${today()}.csv`,
      ['step', 'count', 'conversionFromPrev', 'dropFromPrev'],
      s.steps.map((st) => [st.label, st.value, st.convFromPrev === null ? '' : st.convFromPrev.toFixed(4), st.dropFromPrev]),
    );
  }

  return (
    <div className="stack-6">
      <div className="row" style={{ gap: 16 }}>
        <Segmented ariaLabel="기간" value={String(windowDays)} onChange={(v) => setWindowDays(Number(v) as Win)} options={WIN_OPTIONS} />
        <div className="spacer" />
        <Button icon={<Icons.Download size={14} />} onClick={exportFunnel} disabled={!s}>내보내기</Button>
      </div>

      {failed && <Alert tone="bad" title="퍼널 데이터를 불러오지 못했습니다." />}
      {!data || !s ? (
        !failed && <p className="muted" style={{ margin: 0 }}>불러오는 중…</p>
      ) : (
        <>
          <div className="kpis kpis--3">
            <KpiCard label="전체 전환율 (등록 시도 → 인증 성공)" value={fmtPct(s.overallRate)}
              hint={delta !== null ? `직전 7일 대비 ${fmtDeltaPp(delta)} (인증 성공률)` : undefined} />
            <KpiCard label="최대 이탈 단계"
              value={<span style={{ fontSize: 20 }}>{s.biggestDrop ? `${s.biggestDrop.from} → ${s.biggestDrop.to}` : '—'}</span>}
              hint={s.biggestDrop ? `이탈 ${fmtNum(s.biggestDrop.lost)}건 · ${fmtPct(s.biggestDrop.rate)}` : '이탈 없음'} />
            <KpiCard label="기간" value={<span style={{ fontSize: 20 }}>{PERIOD_LABEL[windowDays]}</span>}
              hint={`${ymd(new Date(now - (windowDays === 1 ? 1 : windowDays - 1) * 86_400_000).toISOString())} – ${ymd(new Date(now).toISOString())}`} />
          </div>

          <Card>
            <CardHeader title="Conversion Funnel" sub={`등록 → 인증 단계별 전환 — ${PERIOD_LABEL[windowDays]}.`} />
            <CardBody>
              <div style={{ overflowX: 'auto' }}>
                <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, minWidth: 640 }}>
                  {s.steps.map((st, i) => {
                    const max = Math.max(1, ...s.steps.map((x) => x.value)); // 0/0 방지
                    return (
                      <Fragment key={st.label}>
                        {i > 0 && (
                          <div className="stack-1" style={{ alignItems: 'center', paddingBottom: 110, flex: 'none' }}>
                            <span className="tag" style={{ background: 'var(--brand-100)', color: 'var(--brand-700)' }}>{fmtPct(st.convFromPrev)}</span>
                            <span style={{ color: 'var(--faint)', display: 'inline-flex' }}><Icons.ChevronRight size={18} /></span>
                          </div>
                        )}
                        <div className="stack-2" style={{ flex: '1 1 0', alignItems: 'stretch' }}>
                          <div className="stack-1" style={{ gap: 2 }}>
                            <strong style={{ fontSize: 13 }}>{st.label}</strong>
                            <span className="hint" style={{ margin: 0 }}>{fmtNum(st.value)}건 · {fmtPct(st.pctOfFirst, 0)}</span>
                          </div>
                          <div style={{ height: 200, display: 'flex', alignItems: 'flex-end', background: 'var(--canvas)', borderRadius: 6 }}>
                            <div style={{ width: '100%', height: `${(st.value / max) * 100}%`, background: STEP_COLORS[i], borderRadius: 6 }} />
                          </div>
                          <span className="hint" style={{ margin: 0 }}>{i > 0 && st.dropFromPrev > 0 ? `이탈 ${fmtNum(st.dropFromPrev)}건` : ' '}</span>
                        </div>
                      </Fragment>
                    );
                  })}
                </div>
              </div>
            </CardBody>
          </Card>

          <div className="grid-2">
            <SeriesCard series={data.series} slug={tenant.slug} />
            <DistributionCard data={data.byEventType} slug={tenant.slug} />
          </div>
        </>
      )}
    </div>
  );
}

// ── 일별 인증 시도 vs 성공 ────────────────────────────────────────────────────

function axisLabels(series: FunnelSeries[]): string[] {
  if (series.length === 0) return [];
  if (series.length === 1) return ['오늘'];
  if (series.length === 2) return [series[0].day, '오늘'];
  return [series[0].day, series[Math.floor((series.length - 1) / 2)].day, '오늘'];
}

function Legend({ color, label }: { color: string; label: string }) {
  return <span className="row" style={{ gap: 4 }}><span aria-hidden="true" style={{ width: 10, height: 10, borderRadius: 2, background: color }} />{label}</span>;
}

function SeriesCard({ series, slug }: { series: FunnelSeries[]; slug: string }) {
  const [asTable, setAsTable] = useState(false);
  const max = Math.max(1, ...series.map((d) => d.attempts));
  const columns: Column<FunnelSeries>[] = [
    { key: 'day', header: '날짜', render: (d) => <span className="mono">{d.day}</span> },
    { key: 'a', header: '시도', align: 'right', render: (d) => fmtNum(d.attempts) },
    { key: 's', header: '성공', align: 'right', render: (d) => fmtNum(d.success) },
    { key: 'r', header: '성공률', align: 'right', render: (d) => fmtPct(d.attempts > 0 ? d.success / d.attempts : null) },
  ];
  return (
    <Card>
      <CardHeader title="일별 인증 시도 vs 성공"
        actions={!asTable && <span className="row hint" style={{ gap: 10, margin: 0 }}><Legend color="var(--brand-300)" label="시도" /><Legend color="var(--brand-700)" label="성공" /></span>} />
      <CardBody className="stack-3">
        {asTable ? (
          <DataTable columns={columns} rows={series} rowKey={(d) => d.day} />
        ) : (
          <>
            <div role="img" aria-label={`일별 인증 시도와 성공 막대 차트, ${series.length}일`}
              style={{ height: 200, display: 'flex', alignItems: 'flex-end', gap: series.length > 14 ? 4 : 14, borderBottom: '1px solid var(--line)', padding: '0 4px' }}>
              {series.map((d) => (
                <div key={d.day} title={`${d.day} · 시도 ${d.attempts} · 성공 ${d.success}`} style={{ flex: '1 1 0', display: 'flex', alignItems: 'flex-end', gap: 3, height: '100%' }}>
                  <span style={{ flex: '1 1 0', height: `${(d.attempts / max) * 100}%`, background: 'var(--brand-300)', borderRadius: '3px 3px 0 0' }} />
                  <span style={{ flex: '1 1 0', height: `${(d.success / max) * 100}%`, background: 'var(--brand-700)', borderRadius: '3px 3px 0 0' }} />
                </div>
              ))}
            </div>
            <div className="row hint" style={{ justifyContent: 'space-between', padding: '0 4px', margin: 0 }}>
              {axisLabels(series).map((l, i) => <span key={`${l}-${i}`}>{l}</span>)}
            </div>
          </>
        )}
        <div className="row" style={{ gap: 8 }}>
          <Button variant="ghost" size="sm" aria-pressed={asTable} onClick={() => setAsTable((v) => !v)}>표로 보기</Button>
          <Button variant="ghost" size="sm" onClick={() => downloadCsv(`funnel-series-${slug}-${today()}.csv`, ['day', 'attempts', 'success'], series.map((d) => [d.day, d.attempts, d.success]))}>CSV</Button>
        </div>
      </CardBody>
    </Card>
  );
}

// ── 이벤트 타입별 분포 ────────────────────────────────────────────────────────

function DistributionCard({ data, slug }: { data: FunnelByEventType[]; slug: string }) {
  const [asTable, setAsTable] = useState(false);
  const total = data.reduce((a, b) => a + b.n, 0);
  const safeTotal = total > 0 ? total : 1; // 0/0 방지
  let k = 0;
  const colorOf = data.map((d) => (/FAIL/.test(d.type) ? 'var(--bad)' : DIST_COLORS[k++ % DIST_COLORS.length]));
  const columns: Column<FunnelByEventType>[] = [
    { key: 'type', header: '이벤트 타입', render: (d) => <span className="mono">{d.type}</span> },
    { key: 'n', header: '건수', align: 'right', render: (d) => fmtNum(d.n) },
    { key: 'p', header: '비율', align: 'right', render: (d) => fmtPct(total > 0 ? d.n / total : null) },
  ];
  return (
    <Card>
      <CardHeader title="이벤트 타입별 분포" />
      <CardBody className="stack-4">
        {asTable ? (
          <DataTable columns={columns} rows={data} rowKey={(d) => d.type} />
        ) : (
          <>
            <div role="img" aria-label={`이벤트 타입 분포, 총 ${fmtNum(total)}건`} className="row" style={{ height: 28, borderRadius: 6, overflow: 'hidden', flexWrap: 'nowrap', gap: 0, background: 'var(--canvas)' }}>
              {data.map((d, i) => <span key={d.type} title={`${d.type}: ${d.n}`} style={{ flex: `${d.n / safeTotal} 1 0`, height: '100%', background: colorOf[i] }} />)}
            </div>
            {data.length === 0 ? (
              <p className="hint" style={{ margin: 0 }}>이벤트가 없습니다.</p>
            ) : (
              <div className="stack-3" style={{ fontSize: 13 }}>
                {data.map((d, i) => (
                  <span key={d.type} className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                    <span aria-hidden="true" style={{ width: 10, height: 10, borderRadius: 2, background: colorOf[i], flex: 'none' }} />
                    <span className="mono truncate" style={{ fontSize: 12 }}>{d.type}</span>
                    <span className="spacer" />
                    <strong>{fmtNum(d.n)}</strong>
                  </span>
                ))}
              </div>
            )}
          </>
        )}
        <div className="row" style={{ gap: 8 }}>
          <Button variant="ghost" size="sm" aria-pressed={asTable} onClick={() => setAsTable((v) => !v)}>표로 보기</Button>
          <Button variant="ghost" size="sm" onClick={() => downloadCsv(`funnel-events-${slug}-${today()}.csv`, ['type', 'n'], data.map((d) => [d.type, d.n]))}>CSV</Button>
        </div>
      </CardBody>
    </Card>
  );
}
