import { useState } from 'react';
import AdminUsersTab from './settings/AdminUsersTab';
import MdsStatusTab from './settings/MdsStatusTab';
import SystemInfoTab from './settings/SystemInfoTab';
import SecurityPolicyTab from './settings/SecurityPolicyTab';

type SettingsTab = 'admins' | 'mds' | 'system' | 'security';

export default function SettingsPage() {
  const [tab, setTab] = useState<SettingsTab>('admins');

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

      {tab === 'admins' && <AdminUsersTab />}
      {tab === 'mds' && <MdsStatusTab />}
      {tab === 'system' && <SystemInfoTab />}
      {tab === 'security' && <SecurityPolicyTab />}
    </div>
  );
}
