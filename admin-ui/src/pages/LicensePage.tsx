import { Fragment, ReactNode } from 'react';
import { licenseApi, type LicenseState, type LicenseView } from '@/api/license';
import { formatDateTime } from '@/lib/formatDateTime';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { Alert, Badge, Button, Card, CardHeader, CopyBtn, LoadError, PageHeader, Skeleton, type BadgeTone } from '@/ui';

const STATE_BADGE: Record<LicenseState, { tone: BadgeTone; label: string }> = {
  VALID: { tone: 'ok', label: '정상' },
  WARNING: { tone: 'warn', label: '만료 임박' },
  NETWORK_GRACE: { tone: 'warn', label: '서버 연결 불가' },
  DEAD: { tone: 'bad', label: '만료' },
};

const DAYS_COLOR: Partial<Record<LicenseState, string>> = { WARNING: 'var(--warn)', DEAD: 'var(--bad)' };

const PAGE_TITLE = '라이선스';
const PAGE_SUB = '이 설치본(온프레미스)의 라이선스 상태와 상세 정보입니다.';

/** "5분 전" / "55분 후" / "3시간 전" / "2일 후" */
function relativeTime(iso: string): string {
  const diff = new Date(iso).getTime() - Date.now();
  if (isNaN(diff)) return iso;
  const future = diff > 0;
  const suffix = future ? '후' : '전';
  const mins = Math.floor(Math.abs(diff) / 60_000);
  if (mins < 1) return future ? '곧' : '방금';
  if (mins < 60) return `${mins}분 ${suffix}`;
  const hours = Math.floor(mins / 60);
  if (hours < 48) return `${hours}시간 ${suffix}`;
  return `${Math.floor(hours / 24)}일 ${suffix}`;
}

function ActionCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="card" aria-label={title}>
      <div className="card__head"><h2 className="card__title">{title}</h2></div>
      <div className="card__body" style={{ display: 'flex', flexDirection: 'column', gap: 12, fontSize: 13 }}>{children}</div>
    </section>
  );
}

function Steps({ items }: { items: string[] }) {
  return (
    <ol style={{ margin: 0, paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 6 }}>
      {items.map((s) => <li key={s}>{s}</li>)}
    </ol>
  );
}

const RECHECK_STEP = '이 화면에서 "지금 다시 확인"을 누릅니다. 상태가 "정상"으로 바뀌면 끝입니다.';
const REQUEST_STEP = '영업 담당자에게 갱신을 요청합니다. 아래 상세의 라이선스 ID와 고객사 이름을 함께 보내 주세요.';
const APPLY_STEP = '받은 라이선스 파일을 서버에 적용합니다.';

/** 상태만 알리지 않고 다음 행동을 함께 보인다(V3 · H9). VALID 는 할 일이 없으므로 카드 없음. */
function NextAction({ license }: { license: LicenseView }) {
  if (license.state === 'WARNING') {
    return (
      <ActionCard title="지금 해야 할 일 — 라이선스 갱신">
        <p style={{ margin: 0 }}>{`라이선스가 ${license.daysUntilExpiry}일 후 만료됩니다. 만료되면 패스키 등록·인증이 중단됩니다.`}</p>
        <Steps items={[REQUEST_STEP, APPLY_STEP, RECHECK_STEP]} />
      </ActionCard>
    );
  }
  if (license.state === 'NETWORK_GRACE') {
    const left = license.graceRemainingHours !== null ? `${license.graceRemainingHours}시간 안에` : '곧';
    return (
      <ActionCard title="지금 해야 할 일 — 라이선스 서버 연결 복구">
        <p style={{ margin: 0 }}>{`라이선스 서버에 연결할 수 없습니다. ${left} 연결되지 않으면 서비스가 중단됩니다.`}</p>
        <Steps items={[
          '방화벽에서 라이선스 서버(443 포트)로 나가는 연결을 허용했는지 확인합니다.',
          '프록시를 쓰는 경우 프록시 설정을 확인합니다.',
          RECHECK_STEP,
        ]} />
      </ActionCard>
    );
  }
  if (license.state === 'DEAD') {
    return (
      <ActionCard title="지금 해야 할 일 — 새 라이선스 적용">
        <p style={{ margin: 0 }}>라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다. 새 라이선스 파일을 적용하면 바로 복구됩니다.</p>
        <Steps items={[REQUEST_STEP, APPLY_STEP, RECHECK_STEP]} />
      </ActionCard>
    );
  }
  return null;
}

export default function LicensePage() {
  // "지금 다시 확인"(reload) 중에는 이전 정보를 그대로 보여 준다.
  const load = useLoad(() => licenseApi.get(), []);
  const license = load.data;

  if (!license) {
    return (
      <div className="page">
        <PageHeader title={PAGE_TITLE} sub={PAGE_SUB} />
        <Card>
          {load.status === 'failed'
            ? <LoadError what="라이선스 정보" error={load.error} onRetry={load.reload} retrying={load.reloading} />
            : <Skeleton rows={5} />}
        </Card>
      </div>
    );
  }

  if (license.deploymentMode !== 'onprem') {
    return (
      <div className="page">
        <PageHeader title={PAGE_TITLE} />
        <Card>
          <div className="empty">
            <p className="empty__title">라이선스 관리는 온프레미스 설치본에서만 사용합니다</p>
            <p className="empty__sub">클라우드 설치본은 계약에 따라 자동 관리됩니다.</p>
          </div>
        </Card>
      </div>
    );
  }

  const badge = license.state ? STATE_BADGE[license.state] : null;
  const heartbeat = [
    license.lastVerifiedAt && `마지막 확인 ${relativeTime(license.lastVerifiedAt)}`,
    license.nextHeartbeatAt && `다음 확인 ${relativeTime(license.nextHeartbeatAt)}`,
  ].filter(Boolean).join(' · ');

  const rows: { k: string; v: ReactNode }[] = [
    { k: '고객사', v: license.sub ?? '—' },
    {
      k: '라이선스 ID',
      v: license.jti ? (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <span className="mono" style={{ wordBreak: 'break-all' }}>{license.jti}</span>
          <CopyBtn value={license.jti} label="라이선스 ID 복사" iconOnly />
        </span>
      ) : '—',
    },
    { k: '만료일', v: license.expiresAt ? formatDateTime(license.expiresAt) : '—' },
    { k: '남은 일수', v: license.daysUntilExpiry },
    {
      k: '사용 가능한 기능',
      v: license.features.length > 0
        ? <span className="row" style={{ gap: 6 }}>{license.features.map((f) => <span key={f} className="tag">{f}</span>)}</span>
        : '—',
    },
    { k: '마지막 확인', v: license.lastVerifiedAt ? formatDateTime(license.lastVerifiedAt) : '—' },
    { k: '다음 확인 예정', v: license.nextHeartbeatAt ? formatDateTime(license.nextHeartbeatAt) : '—' },
    ...(license.graceRemainingHours !== null ? [{ k: '유예 기간 남은 시간', v: `${license.graceRemainingHours}시간` }] : []),
  ];

  return (
    <div className="page">
      <PageHeader title={PAGE_TITLE} sub={PAGE_SUB} />

      <div className="stack-5" style={{ maxWidth: 820 }}>
        {load.status === 'failed' && (
          <Alert tone="warn" title="다시 확인하지 못했습니다">
            {`${describeError(load.error).title}. 아래는 마지막으로 확인한 정보입니다.`}
          </Alert>
        )}

        {/* 상태 헤드라인 — 원시 enum 대신 배지(원문은 툴팁) */}
        <Card>
          <div className="row" style={{ gap: 20, padding: '20px 24px' }}>
            <div className="col" style={{ gap: 6, flex: '1 1 240px', minWidth: 0 }}>
              <span className="t-over">상태</span>
              <span className="row" style={{ gap: 10 }}>
                {badge
                  ? <span title={license.state ?? undefined}><Badge tone={badge.tone}>{badge.label}</Badge></span>
                  : <span className="muted">알 수 없음</span>}
              </span>
              {heartbeat && <span className="card__sub">{heartbeat}</span>}
            </div>
            <div className="col" style={{ gap: 6, flex: '1 1 240px', minWidth: 0 }}>
              <div className="row">
                <span className="hint" style={{ margin: 0 }}>만료까지</span>
                <div className="spacer" />
                <strong style={{ fontSize: 13, color: license.state ? DAYS_COLOR[license.state] : undefined }}>{`${license.daysUntilExpiry}일`}</strong>
              </div>
              {license.expiresAt && <span className="hint" style={{ margin: 0 }}>{`만료일 ${formatDateTime(license.expiresAt)}`}</span>}
            </div>
            <Button onClick={load.reload} disabled={load.reloading}>{load.reloading ? '확인 중…' : '지금 다시 확인'}</Button>
          </div>
        </Card>

        <NextAction license={license} />

        {/* 상세 */}
        <Card>
          <CardHeader title="상세" />
          <dl className="kv kv--rows">
            {rows.map((r) => (
              <Fragment key={r.k}>
                <dt>{r.k}</dt>
                <dd>{r.v}</dd>
              </Fragment>
            ))}
          </dl>
        </Card>
      </div>
    </div>
  );
}
