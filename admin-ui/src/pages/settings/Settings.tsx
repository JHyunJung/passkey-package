import { useState } from 'react';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import AdminUsersTab from './AdminUsersTab';
import MdsStatusTab from './MdsStatusTab';

type TabId = 'admin-users' | 'mds';

export default function Settings() {
  const [tab, setTab] = useState<TabId>('admin-users');

  return (
    <div className="p-6 max-w-[1280px] mx-auto" style={{ padding: '24px' }}>
      <div style={{ marginBottom: 20 }}>
        <h1 style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.011em', margin: 0 }}>
          Settings
        </h1>
      </div>
      <Tabs value={tab} onValueChange={(v) => setTab(v as TabId)}>
        <TabsList>
          <TabsTrigger value="admin-users">Admin Users</TabsTrigger>
          <TabsTrigger value="mds">MDS Status</TabsTrigger>
        </TabsList>
        <TabsContent value="admin-users">
          <AdminUsersTab />
        </TabsContent>
        <TabsContent value="mds">
          <MdsStatusTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
