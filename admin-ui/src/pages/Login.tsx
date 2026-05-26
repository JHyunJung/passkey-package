import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const nav = useNavigate();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    // First GET /me to receive the XSRF-TOKEN cookie if we don't have one yet.
    await fetch('/admin/api/me', { credentials: 'include' }).catch(() => null);
    const ok = await api.loginForm(email, password);
    if (ok) nav('/tenants');
    else setError('Invalid email or password.');
  }

  return (
    <form onSubmit={submit} style={{ maxWidth: 320, margin: '4rem auto' }}>
      <h2>Crosscert Passkey Admin</h2>
      <label>Email
        <input type="email" value={email} onChange={e => setEmail(e.target.value)} required style={{ width: '100%' }} />
      </label>
      <label>Password
        <input type="password" value={password} onChange={e => setPassword(e.target.value)} required style={{ width: '100%' }} />
      </label>
      <button type="submit" style={{ marginTop: '1rem', width: '100%' }}>Sign in</button>
      {error && <p style={{ color: 'red' }}>{error}</p>}
    </form>
  );
}
