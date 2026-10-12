import { useRef, useState, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';
import { kstDateTimeMin } from '@/lib/kst';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { MDS_TRUST_MODES } from '@/i18n/labels';
import { mdsStatusApi, MDS_HISTORY_STRIP_LIMIT, type MdsStatus, type MdsHistoryRow } from '@/api/mdsStatus';
import {
  Alert, Badge, Button, CardBody, CardHeader, DataTable, KpiCard, LoadError, Skeleton, StatusStrip, useToast, type BadgeTone, type Column,
} from '@/ui';

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
  return iso ? kstDateTimeMin(iso) : '—';
}

/** 서버 시드(mds_blob_cache: version 0, 1970-01-01)는 "한 번도 동기화되지 않음"이다. 2000년 이전 시각은 시드로 본다. */
const SEED_CUTOFF_MS = Date.UTC(2000, 0, 1);
function isSeedTime(iso: string | null | undefined): boolean {
  if (!iso) return true;
  const t = new Date(iso).getTime();
  return Number.isNaN(t) || t < SEED_CUTOFF_MS;
}
function isNeverSynced(s: MdsStatus): boolean {
  return s.version === 0 || isSeedTime(s.fetchedAt);
}

const TABLE_ROWS = 6;
const RUN: Record<MdsHistoryRow['status'], { tone: BadgeTone; label: string; strip: boolean | null }> = {
  SYNCED: { tone: 'ok', label: '동기화됨', strip: true },
  SKIPPED: { tone: 'neutral', label: '건너뜀', strip: null },
  FAILED: { tone: 'bad', label: '실패', strip: false },
};
const HEAD_TONE: Record<'ok' | 'bad' | 'neutral', { bg: string; fg: string }> = {
  ok: { bg: 'var(--ok-soft)', fg: 'var(--ok)' },
  bad: { bg: 'var(--bad-soft)', fg: 'var(--bad)' },
  neutral: { bg: 'var(--sunk)', fg: 'var(--mute)' },
};
const DL_STYLE = { display: 'grid', gridTemplateColumns: '150px minmax(0, 1fr)', gap: '12px 16px', fontSize: 13, margin: 0 } as const;
const DT_STYLE = { color: 'var(--mute)' } as const;
const DD_STYLE = { margin: 0, minWidth: 0 } as const;

const columns: Column<MdsHistoryRow>[] = [
  { key: 'at', header: '시각 (KST)', render: (r) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{fmtDateTime(r.startedAt)}</span> },
  { key: 'ver', header: '버전', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{r.version != null ? `#${r.version}` : '—'}</span> },
  { key: 'change', header: '변경', render: (r) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{r.changeSummary ?? '—'}</span> },
  { key: 'status', header: '상태', render: (r) => <Badge tone={RUN[r.status].tone}>{RUN[r.status].label}</Badge> },
  { key: 'ms', header: '응답 시간', align: 'right', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{r.durationMs != null ? `${r.durationMs}ms` : '—'}</span> },
];

// history: null = 이력 조회 실패(없음이 아니다). historyError 가 있으면 그 이유.
type MdsData = { status: MdsStatus; history: MdsHistoryRow[] | null; historyError?: unknown; historyStale?: boolean };

// 상태와 이력을 함께 읽고 둘 다 끝난 뒤에 그린다 — 이력이 늦게 와서 '정상' 헤드라인이 먼저 보이는 일을 막는다.
// 상태 조회 실패는 로드 실패. 이력 조회 실패는 "이력 없음"이 아니라 이력 실패로 따로 알리고,
// 같은 화면의 재조회라면 이전 이력을 유지한다(prev).
async function fetchMds(prev: MdsHistoryRow[] | null): Promise<MdsData> {
  const [st, hist] = await Promise.allSettled([mdsStatusApi.get(), mdsStatusApi.history(MDS_HISTORY_STRIP_LIMIT)]);
  if (st.status === 'rejected') throw st.reason;
  if (hist.status === 'fulfilled') return { status: st.value, history: hist.value };
  return { status: st.value, history: prev, historyError: hist.reason, historyStale: prev != null };
}

/** 신뢰 모드: 사람이 읽는 라벨 + 설명, 원문은 툴팁. 값이 없으면 기본값을 지어내지 않고 "—". */
function TrustMode({ mode }: { mode: string | null | undefined }) {
  if (!mode) return <span>— <span className="hint" style={{ margin: 0 }}>설정되지 않음</span></span>;
  const known = Object.prototype.hasOwnProperty.call(MDS_TRUST_MODES, mode) ? MDS_TRUST_MODES[mode] : undefined;
  return (
    <span className="col" style={{ gap: 6, alignItems: 'flex-start' }}>
      <span title={mode}><Badge tone="info" dot={false}>{known ? known.label : mode}</Badge></span>
      {known && <span className="hint" style={{ margin: 0 }}>{known.description}</span>}
    </span>
  );
}

// ── MdsStatusTab ──────────────────────────────────────────────────────────────

export default function MdsStatusTab() {
  const lastHistory = useRef<MdsHistoryRow[] | null>(null);
  const load = useLoad(async () => {
    const d = await fetchMds(lastHistory.current);
    lastHistory.current = d.history;
    return d;
  }, []);
  const [syncing, setSyncing] = useState(false);
  const toast = useToast();

  // 상태 갱신(setSyncing)은 한 틱 늦으므로 ref 로 재진입을 막는다. 어떤 경로로 끝나도 finally 에서 푼다.
  const syncingRef = useRef(false);
  async function handleSync() {
    if (syncingRef.current) return;
    syncingRef.current = true;
    setSyncing(true);
    try {
      const result = await mdsStatusApi.sync();
      if (result.status === 'SYNCED') {
        toast({ kind: 'ok', title: 'MDS 즉시 갱신 완료', message: `버전 ${result.version}` });
      } else if (result.status === 'SKIPPED') {
        toast({ kind: 'warn', title: 'MDS 갱신 건너뜀', message: '다른 인스턴스가 동기화 중입니다.' });
      } else {
        // 서버 원문(result.error)은 화면에 싣지 않는다.
        toast({ kind: 'err', title: 'MDS 갱신 실패', message: '갱신하지 못했습니다. 잠시 후 다시 시도하세요.' });
      }
      void load.reload();
    } catch (e: unknown) {
      toast({ kind: 'err', title: 'MDS 갱신 실패', message: describeError(e).title });
    } finally {
      syncingRef.current = false;
      setSyncing(false);
    }
  }

  if (load.status === 'failed' && !load.data) {
    return (
      <section className="card">
        <LoadError what="MDS 상태" error={load.error} onRetry={load.reload} retrying={load.reloading} />
      </section>
    );
  }
  if (!load.data) {
    return <section className="card"><Skeleton rows={5} label="MDS 상태 불러오는 중" /></section>;
  }

  const { status, history: historyRaw, historyError, historyStale } = load.data;
  const history = historyRaw ?? [];
  const historyFailed = historyRaw == null;
  // 헤드라인의 기준은 상태(status). 시드뿐이면 중립, 아니면 가장 최근 실행이 실패일 때만 실패로 표시한다.
  const neverSynced = isNeverSynced(status);
  const nextUpdate = status.nextUpdate && !isSeedTime(status.nextUpdate) ? status.nextUpdate : null;
  const versionText = !neverSynced && status.version != null ? `#${status.version}` : '—';
  const latest = history[0];
  const headTone: 'ok' | 'bad' | 'neutral' = neverSynced || historyFailed ? 'neutral' : latest?.status === 'FAILED' ? 'bad' : 'ok';
  const headTitle = headTone === 'bad' ? `동기화 실패 · BLOB ${versionText}` : headTone === 'neutral' ? (historyFailed && !neverSynced ? `이력 확인 불가 · BLOB ${versionText}` : '동기화 이력 없음') : `정상 · BLOB ${versionText}`;
  const headIcon: ReactNode = headTone === 'ok' ? <Icons.Check size={24} /> : <Icons.Alert size={24} />;
  const rate = status.successRate30d;
  const stripResults = [...history].reverse().map((r) => RUN[r.status].strip);
  const failCount = history.filter((r) => r.status === 'FAILED').length;

  return (
    <div className="stack-6">
      {load.status === 'failed' && (
        <div className="stack-3">
          <Alert tone="warn" title="다시 확인하지 못했습니다">
            {`${describeError(load.error).title}. 아래는 마지막으로 읽은 MDS 상태입니다.`}
          </Alert>
          <div><Button onClick={() => void load.reload()} disabled={load.reloading}>{load.reloading ? '다시 시도 중…' : '다시 시도'}</Button></div>
        </div>
      )}
      <section className="card" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 20, padding: '20px 24px' }}>
        <span
          aria-hidden="true"
          style={{ width: 48, height: 48, borderRadius: 12, background: HEAD_TONE[headTone].bg, color: HEAD_TONE[headTone].fg, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}
        >
          {headIcon}
        </span>
        <div className="col" style={{ gap: 2, flex: '1 1 320px', minWidth: 0 }}>
          <span className="t-over">MDS 동기화 상태</span>
          <strong style={{ fontSize: 20, lineHeight: '28px' }}>{headTitle}</strong>
          <span className="card__sub">
            FIDO MDS3 BLOB 을 매일 03:00 KST 에 갱신합니다{nextUpdate ? ` · 다음 갱신 ${nextUpdate} 03:00 KST` : ''}
          </span>
        </div>
        <Button onClick={() => void handleSync()} disabled={syncing} icon={<Icons.Refresh size={14} />}>
          {syncing ? '갱신 중…' : '즉시 갱신'}
        </Button>
      </section>

      <div className="kpis kpis--3">
        <KpiCard label="MDS BLOB 버전" value={<span className="mono">{versionText}</span>} />
        <KpiCard label="신뢰 앵커 (Trust anchors)" value={status.trustAnchorCount ?? '—'} />
        <KpiCard label="마지막 동기화" value={neverSynced ? '—' : timeAgo(status.fetchedAt)} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.4fr)', gap: 20, alignItems: 'start' }}>
        <section className="card">
          <CardHeader title="동기화 설정" />
          <CardBody>
            <dl style={DL_STYLE}>
              <dt style={DT_STYLE}>주소 (endpoint)</dt>
              <dd style={DD_STYLE} className="mono"><span style={{ fontSize: 12 }}>https://mds3.fidoalliance.org/</span></dd>
              <dt style={DT_STYLE}>다음 갱신 예정</dt>
              <dd style={DD_STYLE}>{nextUpdate ? `${nextUpdate} 03:00 KST` : '—'}</dd>
              <dt style={DT_STYLE}>동기화 성공률 (30일)</dt>
              <dd style={DD_STYLE}>
                {rate && rate.total > 0
                  ? <><span>{Math.round((rate.ok / rate.total) * 100)}%</span><span className="muted"> · {rate.ok} / {rate.total}</span></>
                  : '—'}
              </dd>
              <dt style={DT_STYLE}>신뢰 모드</dt>
              <dd style={DD_STYLE}><TrustMode mode={status.trustMode} /></dd>
            </dl>
          </CardBody>
        </section>

        <section className="card">
          <CardHeader
            title="최근 동기화 이력"
            actions={!historyFailed && history.length > 0 ? (
              <StatusStrip results={stripResults} label={`최근 ${history.length}회 동기화 결과 · 실패 ${failCount}회`} />
            ) : undefined}
          />
          {historyFailed ? (
            <LoadError what="동기화 이력" error={historyError} onRetry={load.reload} retrying={load.reloading} compact />
          ) : (
            <>
              {historyStale && (
                <div className="stack-3" style={{ padding: '10px 20px' }}>
                  <Alert tone="warn" title="다시 확인하지 못했습니다">
                    {`${describeError(historyError).title}. 아래는 마지막으로 읽은 동기화 이력입니다.`}
                  </Alert>
                  <div><Button onClick={() => void load.reload()} disabled={load.reloading}>{load.reloading ? '다시 시도 중…' : '다시 시도'}</Button></div>
                </div>
              )}
              <DataTable
                columns={columns}
                rows={history.slice(0, TABLE_ROWS)}
                rowKey={(r) => String(r.id)}
                empty={<p className="hint" style={{ margin: 0, padding: '16px 20px' }}>동기화 이력이 없습니다.</p>}
              />
            </>
          )}
        </section>
      </div>
    </div>
  );
}
