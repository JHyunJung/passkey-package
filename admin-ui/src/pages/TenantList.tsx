import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantView } from '../api/types';

export default function TenantList() {
  const [tenants, setTenants] = useState<TenantView[]>([]);
  useEffect(() => { api.get<TenantView[]>('/admin/api/tenants').then(setTenants); }, []);
  return (
    <div>
      <h2>Tenants</h2>
      <p><Link to="/tenants/new">+ New tenant</Link></p>
      <table border={1} cellPadding={4} cellSpacing={0}>
        <thead><tr><th>ID</th><th>Display</th><th>Status</th><th>RP ID</th></tr></thead>
        <tbody>
          {tenants.map(t => (
            <tr key={t.id}>
              <td>{t.id}</td><td>{t.displayName}</td><td>{t.status}</td><td>{t.rpId}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
