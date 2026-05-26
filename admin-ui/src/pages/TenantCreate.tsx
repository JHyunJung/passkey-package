import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { TenantCreateRequest, TenantView } from '../api/types';

const DEFAULT_POLICY = JSON.stringify({
  acceptedFormats: ['none', 'packed'],
  requireUserVerification: true,
  mdsRequired: false,
}, null, 2);

export default function TenantCreate() {
  const nav = useNavigate();
  const [form, setForm] = useState<TenantCreateRequest>({
    id: '', displayName: '', rpId: 'localhost', rpName: '',
    allowedOriginsJson: '["http://localhost"]',
    attestationPolicyJson: DEFAULT_POLICY,
  });
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.post<TenantView>('/admin/api/tenants', form);
      nav('/tenants');
    } catch (err) {
      setError(String(err));
    }
  }

  function bind<K extends keyof TenantCreateRequest>(k: K) {
    return (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm({ ...form, [k]: e.target.value });
  }

  return (
    <form onSubmit={submit} style={{ maxWidth: 600 }}>
      <h2>New tenant</h2>
      <label>ID<input value={form.id} onChange={bind('id')} required /></label>
      <label>Display name<input value={form.displayName} onChange={bind('displayName')} required /></label>
      <label>RP ID<input value={form.rpId} onChange={bind('rpId')} required /></label>
      <label>RP name<input value={form.rpName} onChange={bind('rpName')} required /></label>
      <label>Allowed origins (JSON array)
        <textarea value={form.allowedOriginsJson} onChange={bind('allowedOriginsJson')} rows={3} style={{ width: '100%' }} />
      </label>
      <label>Attestation policy (JSON)
        <textarea value={form.attestationPolicyJson} onChange={bind('attestationPolicyJson')} rows={6} style={{ width: '100%' }} />
      </label>
      <button type="submit">Create</button>
      {error && <p style={{ color: 'red' }}>{error}</p>}
    </form>
  );
}
