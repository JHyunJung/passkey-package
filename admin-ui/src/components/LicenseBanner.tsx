import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { licenseApi, type LicenseView } from '@/api/license';

/**
 * Global license banner. Renders nothing on SaaS mode (state === null) or VALID,
 * a warning/danger strip otherwise. Polls /admin/api/license every 5 minutes to
 * reflect state transitions without requiring a reload.
 * 상태만 알리지 않고 다음 행동 링크(/license 의 조치 카드)를 함께 보인다(V3 · H9).
 * showLink: /license 는 PLATFORM 전용 — RP 관리자에게는 막힌 링크를 보이지 않는다.
 */
export function LicenseBanner({ showLink = false }: { showLink?: boolean }) {
  const [license, setLicense] = useState<LicenseView | null>(null);
  const { pathname } = useLocation();

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

  const action = (label: string) => (showLink && pathname !== '/license'
    ? <Link to="/license" className="btn btn--sm" style={{ marginLeft: 'auto', flex: 'none' }}>{label}</Link>
    : null);

  if (license.state === 'WARNING') {
    return (
      <div className="topbanner topbanner--warning" role="status">
        <Icons.Alert size={16} />
        <strong>{`라이선스가 ${license.daysUntilExpiry}일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요.`}</strong>
        {action('라이선스 보기')}
      </div>
    );
  }
  if (license.state === 'NETWORK_GRACE') {
    const left = license.graceRemainingHours !== null ? `${license.graceRemainingHours}시간 안에` : '곧';
    return (
      <div className="topbanner topbanner--warning" role="status">
        <Icons.Alert size={16} />
        <strong>{`라이선스 서버에 연결할 수 없습니다. ${left} 연결되지 않으면 서비스가 중단됩니다.`}</strong>
        {action('조치 방법 보기')}
      </div>
    );
  }
  // DEAD — 숨길 수 없음
  return (
    <div className="topbanner topbanner--danger" role="alert">
      <Icons.Alert size={16} />
      <strong>라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다.</strong>
      {action('갱신 방법 보기')}
    </div>
  );
}
