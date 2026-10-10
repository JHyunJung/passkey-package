import { Fragment, ReactNode, useEffect, useState } from 'react';
import { licenseApi, type LicenseState, type LicenseView } from '@/api/license';
import { formatDateTime } from '@/lib/formatDateTime';
import { Alert, Badge, Card, CardHeader, CopyBtn, PageHeader, type BadgeTone } from '@/ui';

const STATE_BADGE: Record<LicenseState, { tone: BadgeTone; label: string }> = {
  VALID: { tone: 'ok', label: '정상' },
  WARNING: { tone: 'warn', label: '만료 임박' },
  NETWORK_GRACE: { tone: 'warn', label: '서버 연결 불가' },
  DEAD: { tone: 'bad', label: '만료' },
};

const DAYS_COLOR: Partial<Record<LicenseState, string>> = { WARNING: 'var(--warn)', DEAD: 'var(--bad)' };

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

export default function LicensePage() {
  const [license, setLicense] = useState<LicenseView | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    licenseApi.get().then(setLicense).catch(e => setErr(String(e)));
  }, []);

  if (err) return <div className="page"><Alert tone="bad">Error: {err}</Alert></div>;
  if (!license) return <div className="page"><p className="muted" style={{ margin: 0 }}>Loading…</p></div>;
  if (license.deploymentMode !== 'onprem') {
    return <div className="page"><p className="muted" style={{ margin: 0 }}>License management is on-prem only.</p></div>;
  }

  const badge = license.state ? STATE_BADGE[license.state] : null;
  const heartbeat = [
    license.lastVerifiedAt && `Last verified ${relativeTime(license.lastVerifiedAt)}`,
    license.nextHeartbeatAt && `Next heartbeat ${relativeTime(license.nextHeartbeatAt)}`,
  ].filter(Boolean).join(' · ');

  const rows: { k: string; v: ReactNode }[] = [
    {
      k: 'Customer',
      v: license.sub ? <><span>{license.sub}</span> <span className="hint" style={{ margin: 0 }}>(sub)</span></> : '(none)',
    },
    {
      k: 'License ID (jti)',
      v: license.jti ? (
        <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <span className="mono" style={{ wordBreak: 'break-all' }}>{license.jti}</span>
          <CopyBtn value={license.jti} label="License ID 복사" iconOnly />
        </span>
      ) : '(none)',
    },
    { k: 'Expires at', v: license.expiresAt ? formatDateTime(license.expiresAt) : '(unknown)' },
    { k: 'Days until expiry', v: license.daysUntilExpiry },
    {
      k: 'Features',
      v: license.features.length > 0
        ? <span className="row" style={{ gap: 6 }}>{license.features.map((f) => <span key={f} className="tag">{f}</span>)}</span>
        : '(none)',
    },
    { k: 'Last verified at', v: license.lastVerifiedAt ? formatDateTime(license.lastVerifiedAt) : '(never)' },
    { k: 'Next heartbeat at', v: license.nextHeartbeatAt ? formatDateTime(license.nextHeartbeatAt) : '(none scheduled)' },
    ...(license.graceRemainingHours !== null ? [{ k: 'Grace remaining', v: `${license.graceRemainingHours} hour(s)` }] : []),
  ];

  return (
    <div className="page">
      <PageHeader title="License" sub="온프레미스 라이선스 상태 및 상세 정보." />

      <div className="stack-5" style={{ maxWidth: 820 }}>
        {/* 상태 헤드라인 */}
        <Card>
          <div className="row" style={{ gap: 20, padding: '20px 24px' }}>
            <div className="col" style={{ gap: 2, flex: '1 1 240px', minWidth: 0 }}>
              <span className="t-over">State</span>
              <span className="row" style={{ gap: 10 }}>
                <strong style={{ fontSize: 20 }}>{license.state ?? '(unknown)'}</strong>
                {badge && <Badge tone={badge.tone}>{badge.label}</Badge>}
              </span>
              {heartbeat && <span className="card__sub">{heartbeat}</span>}
            </div>
            <div className="col" style={{ gap: 6, flex: '1 1 240px', minWidth: 0 }}>
              <div className="row">
                <span className="hint" style={{ margin: 0 }}>만료까지</span>
                <div className="spacer" />
                <strong style={{ fontSize: 13, color: license.state ? DAYS_COLOR[license.state] : undefined }}>{license.daysUntilExpiry}일</strong>
              </div>
              {license.expiresAt && <span className="hint" style={{ margin: 0 }}>Expires at {formatDateTime(license.expiresAt)}</span>}
            </div>
          </div>
        </Card>

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
