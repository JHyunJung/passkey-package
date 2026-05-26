import { useState } from 'react';
import { api } from '../api/client';
import type { ApiKeyCreateResponse } from '../api/types';

interface Props {
  tenantId: string;
  onClose: () => void;
  onIssued: () => void;
}

export default function ApiKeyCreateModal({ tenantId, onClose, onIssued }: Props) {
  const [name, setName] = useState('primary');
  const [issued, setIssued] = useState<ApiKeyCreateResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setError(null);
    try {
      const resp = await api.post<ApiKeyCreateResponse>('/admin/api/api-keys', {
        tenantId, name, scopesJson: '[]',
      });
      setIssued(resp);
      onIssued();
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <div style={{
      position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      <div style={{ background: 'white', padding: '1.5rem', minWidth: 360 }}>
        {issued ? (
          <>
            <h3>API Key issued</h3>
            <p><strong>This is the only time the full key will be shown.</strong> Copy it now.</p>
            <pre style={{ background: '#eef', padding: '0.5rem', wordBreak: 'break-all' }}>
              {issued.plainText}
            </pre>
            <button onClick={() => navigator.clipboard.writeText(issued.plainText)}>
              Copy to clipboard
            </button>
            <button onClick={onClose} style={{ marginLeft: '0.5rem' }}>I have saved the key</button>
          </>
        ) : (
          <>
            <h3>Issue API Key for {tenantId}</h3>
            <label>Name<input value={name} onChange={e => setName(e.target.value)} /></label>
            <div style={{ marginTop: '1rem' }}>
              <button onClick={submit}>Issue</button>
              <button onClick={onClose} style={{ marginLeft: '0.5rem' }}>Cancel</button>
            </div>
            {error && <p style={{ color: 'red' }}>{error}</p>}
          </>
        )}
      </div>
    </div>
  );
}
