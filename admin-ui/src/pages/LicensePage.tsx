import { useEffect, useState } from 'react';
import { licenseApi, type LicenseView } from '@/api/license';

export default function LicensePage() {
  const [license, setLicense] = useState<LicenseView | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    licenseApi.get().then(setLicense).catch(e => setErr(String(e)));
  }, []);

  if (err) return <div style={{ padding: 24, color: 'var(--danger, red)' }}>Error: {err}</div>;
  if (!license) return <div style={{ padding: 24, color: 'var(--text-mute)' }}>Loading…</div>;
  if (license.deploymentMode !== 'onprem') {
    return <div style={{ padding: 24, color: 'var(--text-mute)' }}>License management is on-prem only.</div>;
  }

  const stateColor: Record<string, string> = {
    VALID: 'var(--success)',
    WARNING: 'var(--warn, #d97706)',
    NETWORK_GRACE: 'var(--warn, #d97706)',
    DEAD: 'var(--danger, red)',
  };
  const stateColor_ = license.state ? (stateColor[license.state] ?? 'var(--text-soft)') : 'var(--text-soft)';

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">License</h1>
          <div className="page__sub">온프레미스 라이선스 상태 및 상세 정보.</div>
        </div>
      </div>

      <div style={{ maxWidth: 720 }}>
        <dl style={{
          display: 'grid',
          gridTemplateColumns: 'max-content 1fr',
          gap: '10px 24px',
          fontSize: 13,
        }}>
          <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>State</dt>
          <dd style={{ margin: 0 }}>
            <span style={{
              display: 'inline-block',
              padding: '2px 8px',
              borderRadius: 4,
              fontSize: 12,
              fontWeight: 600,
              color: stateColor_,
              background: `color-mix(in oklab, ${stateColor_} 12%, transparent)`,
              border: `1px solid color-mix(in oklab, ${stateColor_} 25%, transparent)`,
            }}>
              {license.state ?? '(unknown)'}
            </span>
          </dd>

          <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>Customer (sub)</dt>
          <dd className="mono" style={{ margin: 0 }}>{license.sub ?? '(none)'}</dd>

          <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>License ID (jti)</dt>
          <dd className="mono" style={{ margin: 0, wordBreak: 'break-all' }}>{license.jti ?? '(none)'}</dd>

          <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>Expires at</dt>
          <dd style={{ margin: 0 }}>{license.expiresAt ?? '(unknown)'}</dd>

          <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>Days until expiry</dt>
          <dd style={{ margin: 0 }}>{license.daysUntilExpiry}</dd>

          <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>Features</dt>
          <dd style={{ margin: 0 }}>{license.features.length > 0 ? license.features.join(', ') : '(none)'}</dd>

          <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>Last verified at</dt>
          <dd style={{ margin: 0 }}>{license.lastVerifiedAt ?? '(never)'}</dd>

          <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>Next heartbeat at</dt>
          <dd style={{ margin: 0 }}>{license.nextHeartbeatAt ?? '(none scheduled)'}</dd>

          {license.graceRemainingHours !== null && (
            <>
              <dt style={{ color: 'var(--text-mute)', fontWeight: 500, paddingTop: 1 }}>Grace remaining</dt>
              <dd style={{ margin: 0 }}>{license.graceRemainingHours} hour(s)</dd>
            </>
          )}
        </dl>
      </div>
    </div>
  );
}
