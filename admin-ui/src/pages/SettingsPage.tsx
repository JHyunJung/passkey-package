import { useState } from 'react';
import AccountTab from './settings/AccountTab';
import AdminUsersTab from './settings/AdminUsersTab';
import MdsStatusTab from './settings/MdsStatusTab';
import SystemInfoTab from './settings/SystemInfoTab';
import SecurityPolicyTab from './settings/SecurityPolicyTab';
import type { Me } from '@/api/types';

type SettingsTab = 'account' | 'admins' | 'mds' | 'system' | 'security';

export default function SettingsPage({ me, onMeChange }: { me: Me; onMeChange: (m: Me) => void }) {
  const [tab, setTab] = useState<SettingsTab>('account');

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">설정</h1>
          <div className="page__sub">콘솔 운영자, MDS 신뢰 anchor, 시스템 상태.</div>
        </div>
      </div>

      <div className="tabs">
        <button
          className={`tabs__btn ${tab === 'account' ? 'tabs__btn--active' : ''}`}
          onClick={() => setTab('account')}
        >
          내 계정
        </button>
        <button
          className={`tabs__btn ${tab === 'admins' ? 'tabs__btn--active' : ''}`}
          onClick={() => setTab('admins')}
        >
          Admin 사용자
        </button>
        <button
          className={`tabs__btn ${tab === 'mds' ? 'tabs__btn--active' : ''}`}
          onClick={() => setTab('mds')}
        >
          MDS Status
        </button>
        <button
          className={`tabs__btn ${tab === 'system' ? 'tabs__btn--active' : ''}`}
          onClick={() => setTab('system')}
        >
          시스템
        </button>
        <button
          className={`tabs__btn ${tab === 'security' ? 'tabs__btn--active' : ''}`}
          onClick={() => setTab('security')}
        >
          보안 정책
        </button>
      </div>

      {tab === 'account' && <AccountTab me={me} onMeChange={onMeChange} />}
      {tab === 'admins' && <AdminUsersTab />}
      {tab === 'mds' && <MdsStatusTab />}
      {tab === 'system' && <SystemInfoTab />}
      {tab === 'security' && <SecurityPolicyTab />}
    </div>
  );
}
