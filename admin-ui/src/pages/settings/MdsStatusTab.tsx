import { useState, useEffect, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';
import { mdsStatusApi, MDS_HISTORY_STRIP_LIMIT, type MdsStatus, type MdsHistoryRow } from '@/api/mdsStatus';
import {
  Badge, Button, CardBody, CardHeader, DataTable, KpiCard, StatusStrip, useToast, type BadgeTone, type Column,
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
  { key: 'at', header: '시각', render: (r) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{fmtDateTime(r.startedAt)}</span> },
  { key: 'ver', header: '버전', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{r.version != null ? `#${r.version}` : '—'}</span> },
  { key: 'change', header: '변경', render: (r) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{r.changeSummary ?? '—'}</span> },
  { key: 'status', header: '상태', render: (r) => <Badge tone={RUN[r.status].tone}>{RUN[r.status].label}</Badge> },
  { key: 'ms', header: '응답 시간', align: 'right', render: (r) => <span className="mono" style={{ fontSize: 12 }}>{r.durationMs != null ? `${r.durationMs}ms` : '—'}</span> },
];

// ── MdsStatusTab ──────────────────────────────────────────────────────────────

export default function MdsStatusTab() {
  const [status, setStatus] = useState<MdsStatus | null>(null);
  const [history, setHistory] = useState<MdsHistoryRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const toast = useToast();

  const [loadError, setLoadError] = useState<string | null>(null);

  // 상태와 이력을 함께 읽고 둘 다 끝난 뒤에 그린다 — 이력이 늦게 와서 '정상' 헤드라인이 먼저 보이는 일을 막는다.
  async function load() {
    setLoading(true);
    const [st, hist] = await Promise.allSettled([mdsStatusApi.get(), mdsStatusApi.history(MDS_HISTORY_STRIP_LIMIT)]);
    if (st.status === 'fulfilled') {
      setStatus(st.value);
      setLoadError(null);
    } else {
      const msg = st.reason instanceof Error ? st.reason.message : '알 수 없는 오류';
      setStatus(null);
      setLoadError(msg);
      toast({ kind: 'err', title: 'MDS 상태 로드 실패', message: msg });
    }
    setHistory(hist.status === 'fulfilled' ? hist.value : []);
    setLoading(false);
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

  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (loading) {
    return (
      <section className="card">
        <div className="card__body muted" style={{ fontSize: 13 }}>MDS 상태 로딩 중…</div>
      </section>
    );
  }

  if (!status) {
    return (
      <section className="card" role="alert" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 20, padding: '20px 24px' }}>
        <span aria-hidden="true" style={{ width: 48, height: 48, borderRadius: 12, background: HEAD_TONE.bad.bg, color: HEAD_TONE.bad.fg, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}>
          <Icons.Alert size={24} />
        </span>
        <div className="col" style={{ gap: 2, flex: '1 1 320px', minWidth: 0 }}>
          <span className="t-over">MDS 동기화 상태</span>
          <strong style={{ fontSize: 20, lineHeight: '28px' }}>MDS 상태를 불러오지 못했습니다</strong>
          {loadError && <span className="card__sub">{loadError}</span>}
        </div>
        <Button onClick={() => void load()} icon={<Icons.Refresh size={14} />}>다시 시도</Button>
      </section>
    );
  }

  // 헤드라인의 기준은 상태(status). 시드뿐이면 중립, 아니면 가장 최근 실행이 실패일 때만 실패로 표시한다.
  const neverSynced = isNeverSynced(status);
  const nextUpdate = status.nextUpdate && !isSeedTime(status.nextUpdate) ? status.nextUpdate : null;
  const versionText = !neverSynced && status.version != null ? `#${status.version}` : '—';
  const latest = history[0];
  const headTone: 'ok' | 'bad' | 'neutral' = neverSynced ? 'neutral' : latest?.status === 'FAILED' ? 'bad' : 'ok';
  const headTitle = headTone === 'bad' ? `동기화 실패 · BLOB ${versionText}` : headTone === 'neutral' ? '동기화 이력 없음' : `정상 · BLOB ${versionText}`;
  const headIcon: ReactNode = headTone === 'ok' ? <Icons.Check size={24} /> : <Icons.Alert size={24} />;
  const rate = status.successRate30d ?? { ok: 0, total: 0 };
  const pct = rate.total > 0 ? Math.round((rate.ok / rate.total) * 100) : 0;
  const stripResults = [...history].reverse().map((r) => RUN[r.status].strip);
  const failCount = history.filter((r) => r.status === 'FAILED').length;

  return (
    <div className="stack-6">
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
            FIDO MDS3 BLOB 을 매일 03:00 KST 에 갱신합니다{nextUpdate ? ` · 다음 갱신 ${nextUpdate} 03:00` : ''}
          </span>
        </div>
        <Button onClick={() => void handleSync()} disabled={syncing} icon={<Icons.Refresh size={14} />}>
          {syncing ? '갱신 중…' : '즉시 갱신'}
        </Button>
      </section>

      <div className="kpis kpis--3">
        <KpiCard label="MDS BLOB 버전" value={<span className="mono">{versionText}</span>} />
        <KpiCard label="Trust anchors" value={status.trustAnchorCount ?? 0} />
        <KpiCard label="마지막 동기화" value={neverSynced ? '—' : timeAgo(status.fetchedAt)} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.4fr)', gap: 20, alignItems: 'start' }}>
        <section className="card">
          <CardHeader title="동기화 설정" />
          <CardBody>
            <dl style={DL_STYLE}>
              <dt style={DT_STYLE}>endpoint</dt>
              <dd style={DD_STYLE} className="mono"><span style={{ fontSize: 12 }}>https://mds3.fidoalliance.org/</span></dd>
              <dt style={DT_STYLE}>next 갱신 예정</dt>
              <dd style={DD_STYLE}>{nextUpdate ? `${nextUpdate} 03:00 KST` : '알 수 없음'}</dd>
              <dt style={DT_STYLE}>동기화 성공률 (30d)</dt>
              <dd style={DD_STYLE}><span>{pct}%</span><span className="muted"> · {rate.ok} / {rate.total}</span></dd>
              <dt style={DT_STYLE}>신뢰 모드</dt>
              <dd style={DD_STYLE}><Badge tone="info" dot={false}>{status.trustMode ?? 'MDS_STRICT_OPTIONAL'}</Badge></dd>
            </dl>
          </CardBody>
        </section>

        <section className="card">
          <CardHeader
            title="최근 동기화 이력"
            actions={history.length > 0 ? (
              <StatusStrip results={stripResults} label={`최근 ${history.length}회 동기화 결과 · 실패 ${failCount}회`} />
            ) : undefined}
          />
          <DataTable
            columns={columns}
            rows={history.slice(0, TABLE_ROWS)}
            rowKey={(r) => String(r.id)}
            empty={<p className="hint" style={{ margin: 0, padding: '16px 20px' }}>동기화 이력이 없습니다.</p>}
          />
        </section>
      </div>
    </div>
  );
}
