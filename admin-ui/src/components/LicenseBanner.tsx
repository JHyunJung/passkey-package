import { useEffect, useState } from 'react';
import { licenseApi, type LicenseView } from '@/api/license';

/**
 * Global license banner. Renders nothing on SaaS mode (state === null),
 * a yellow/orange/red strip otherwise. Polls /admin/api/license every
 * 5 minutes to reflect state transitions without requiring a reload.
 */
export function LicenseBanner() {
  const [license, setLicense] = useState<LicenseView | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function poll() {
      try {
        const view = await licenseApi.get();
        if (!cancelled) setLicense(view);
      } catch {
        // Silent — banner is best-effort UI.
      }
    }
    poll();
    const id = setInterval(poll, 5 * 60 * 1000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  if (!license || license.deploymentMode !== 'onprem' || !license.state) return null;
  if (license.state === 'VALID') return null;

  const baseStyle: React.CSSProperties = {
    padding: '10px 16px',
    textAlign: 'center',
    fontWeight: 500,
  };

  if (license.state === 'WARNING') {
    return (
      <div style={{ ...baseStyle, background: 'var(--warning-soft)', color: 'var(--warning)' }}>
        라이센스가 {license.daysUntilExpiry}일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요.
      </div>
    );
  }
  if (license.state === 'NETWORK_GRACE') {
    return (
      <div style={{ ...baseStyle, background: '#fed7aa', color: '#92400e' }}>
        라이센스 서버와 연결할 수 없습니다. {license.graceRemainingHours ?? '?'}시간 내 차단됩니다.
      </div>
    );
  }
  // DEAD
  return (
    <div style={{ ...baseStyle, background: 'var(--danger-soft)', color: 'var(--danger)' }}>
      라이센스가 만료되어 서비스가 중단되었습니다. 영업 담당자에게 문의하세요.
    </div>
  );
}
