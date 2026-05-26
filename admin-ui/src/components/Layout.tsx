import { Link, Outlet, useNavigate } from 'react-router-dom';

export default function Layout() {
  const nav = useNavigate();
  async function logout() {
    const csrf = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1];
    await fetch('/admin/logout', {
      method: 'POST',
      credentials: 'include',
      headers: csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {},
    });
    nav('/login');
  }
  return (
    <div>
      <nav style={{ display: 'flex', gap: '1rem', padding: '1rem', background: '#f0f0f0' }}>
        <Link to="/tenants">Tenants</Link>
        <Link to="/api-keys">API Keys</Link>
        <Link to="/audit">Audit Log</Link>
        <Link to="/mds">MDS</Link>
        <span style={{ flex: 1 }} />
        <button onClick={logout}>Logout</button>
      </nav>
      <main style={{ padding: '1rem' }}>
        <Outlet />
      </main>
    </div>
  );
}
