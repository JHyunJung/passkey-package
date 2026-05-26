import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { ApiKeyView, TenantView } from '../api/types';
import ApiKeyCreateModal from './ApiKeyCreateModal';

export default function ApiKeyList() {
  const [tenants, setTenants] = useState<TenantView[]>([]);
  const [tenantId, setTenantId] = useState<string>('');
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [modalOpen, setModalOpen] = useState(false);

  useEffect(() => {
    api.get<TenantView[]>('/admin/api/tenants').then(ts => {
      setTenants(ts);
      if (ts.length > 0) setTenantId(ts[0].id);
    });
  }, []);

  useEffect(() => {
    if (!tenantId) return;
    let cancelled = false;
    api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${encodeURIComponent(tenantId)}`)
      .then(ks => { if (!cancelled) setKeys(ks); });
    return () => { cancelled = true; };
  }, [tenantId]);

  async function revoke(id: number) {
    if (!confirm('Revoke this key? Existing RP calls using it will fail.')) return;
    await api.delete(`/admin/api/api-keys/${id}`);
    api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${encodeURIComponent(tenantId)}`).then(setKeys);
  }

  return (
    <div>
      <h2>API Keys</h2>
      <label>Tenant
        <select value={tenantId} onChange={e => setTenantId(e.target.value)}>
          {tenants.map(t => <option key={t.id} value={t.id}>{t.id}</option>)}
        </select>
      </label>
      <button onClick={() => setModalOpen(true)} disabled={!tenantId}>+ Issue new</button>
      <table border={1} cellPadding={4} cellSpacing={0} style={{ marginTop: '1rem' }}>
        <thead>
          <tr><th>Prefix</th><th>Name</th><th>Created</th><th>Last used</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {keys.map(k => (
            <tr key={k.id}>
              <td>{k.prefix}</td>
              <td>{k.name}</td>
              <td>{k.createdAt}</td>
              <td>{k.lastUsedAt ?? '-'}</td>
              <td>{k.revokedAt ? 'revoked' : 'active'}</td>
              <td>{!k.revokedAt && <button onClick={() => revoke(k.id)}>Revoke</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {modalOpen && tenantId && (
        <ApiKeyCreateModal
          tenantId={tenantId}
          onIssued={() => api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${encodeURIComponent(tenantId)}`).then(setKeys)}
          onClose={() => setModalOpen(false)}
        />
      )}
    </div>
  );
}
