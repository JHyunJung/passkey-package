import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { AuditLogView } from '../api/types';

export default function AuditLog() {
  const [rows, setRows] = useState<AuditLogView[]>([]);
  const [verify, setVerify] = useState<{ ok: boolean; brokenAt?: number } | null>(null);

  useEffect(() => {
    api.get<AuditLogView[]>('/admin/api/audit').then(setRows);
  }, []);

  async function runVerify() {
    const r = await api.get<{ ok: boolean; brokenAt?: number }>('/admin/api/audit/verify');
    setVerify(r);
  }

  return (
    <div>
      <h2>Audit Log</h2>
      <button onClick={runVerify}>Verify chain</button>
      {verify && <p>{verify.ok ? '✅ Chain intact' : `❌ Broken at row ${verify.brokenAt}`}</p>}
      <table border={1} cellPadding={4} cellSpacing={0} style={{ marginTop: '1rem' }}>
        <thead>
          <tr><th>ID</th><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Payload</th></tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.id}>
              <td>{r.id}</td>
              <td>{r.createdAt}</td>
              <td>{r.actorEmail}</td>
              <td>{r.action}</td>
              <td>{r.targetType ?? '-'}{r.targetId ? ` / ${r.targetId}` : ''}</td>
              <td><code>{r.payload}</code></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
