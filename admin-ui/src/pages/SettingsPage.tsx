// admin-ui/src/pages/SettingsPage.tsx
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import AccountTab from './settings/AccountTab';
import AdminUsersTab from './settings/AdminUsersTab';
import MdsStatusTab from './settings/MdsStatusTab';
import SystemInfoTab from './settings/SystemInfoTab';
import SecurityPolicyTab from './settings/SecurityPolicyTab';
import { signupRequestsApi } from '@/api/signupRequests';
import type { Me } from '@/api/types';
import { isPlatform } from '@/me/roles';
import { PageHeader, Tabs, type TabItem } from '@/ui';

type SettingsTab = 'account' | 'admins' | 'mds' | 'system' | 'security';
const PLATFORM_TABS: readonly string[] = ['admins', 'mds', 'system', 'security'];

/** ?tab= 값을 탭으로. PLATFORM 전용 탭은 PLATFORM 일 때만 허용, 그 외는 항상 '내 계정'. */
function resolveTab(raw: string | null, platform: boolean): SettingsTab {
  return platform && raw !== null && PLATFORM_TABS.includes(raw) ? (raw as SettingsTab) : 'account';
}

export default function SettingsPage({ me, onMeChange }: { me: Me; onMeChange: (m: Me) => void }) {
  const platform = isPlatform(me);
  const [params, setParams] = useSearchParams();
  const tab = resolveTab(params.get('tab'), platform);
  const [pending, setPending] = useState<number | undefined>(undefined);

  // Admin 사용자 탭 배지 = 대기 중 가입 요청 수 (PLATFORM 만 — RP 는 엔드포인트 권한 없음)
  useEffect(() => {
    if (!platform) return;
    let cancelled = false;
    signupRequestsApi.list()
      .then((rows) => { if (!cancelled) setPending(rows.length); })
      .catch(() => { /* 배지는 보조 정보 — 실패 시 숨김 */ });
    return () => { cancelled = true; };
  }, [platform]);

  const items: TabItem[] = [
    { id: 'account', label: '내 계정' },
    ...(platform
      ? [
          { id: 'admins', label: 'Admin 사용자', count: pending },
          { id: 'mds', label: 'MDS Status' },
          { id: 'system', label: '시스템' },
          { id: 'security', label: '보안 정책' },
        ]
      : []),
  ];

  function onTabChange(id: string) {
    const next = new URLSearchParams(params);
    if (id === 'account') next.delete('tab');
    else next.set('tab', id);
    setParams(next);
  }

  return (
    <div className="page">
      <div className="stack-4">
        <PageHeader title="설정" sub="콘솔 운영자, MDS 신뢰 anchor, 시스템 상태." />
        <Tabs ariaLabel="설정 탭" items={items} active={tab} onChange={onTabChange} />
      </div>

      {tab === 'account' && <AccountTab me={me} onMeChange={onMeChange} />}
      {platform && tab === 'admins' && <AdminUsersTab onPendingCountChange={setPending} />}
      {platform && tab === 'mds' && <MdsStatusTab />}
      {platform && tab === 'system' && <SystemInfoTab />}
      {platform && tab === 'security' && <SecurityPolicyTab />}
    </div>
  );
}
