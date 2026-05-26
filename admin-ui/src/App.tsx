import { Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Layout from './components/Layout';
import TenantList from './pages/TenantList';
import TenantCreate from './pages/TenantCreate';
import ApiKeyList from './pages/ApiKeyList';
import AuditLog from './pages/AuditLog';
import MdsStatus from './pages/MdsStatus';
import KeyManagement from './pages/KeyManagement';
import { ToastProvider } from './components/Toast';
import IdleTimeout from './components/IdleTimeout';

export default function App() {
  return (
    <ToastProvider>
      <IdleTimeout />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<Layout />}>
          <Route path="/tenants" element={<TenantList />} />
          <Route path="/tenants/new" element={<TenantCreate />} />
          <Route path="/api-keys" element={<ApiKeyList />} />
          <Route path="/audit" element={<AuditLog />} />
          <Route path="/mds" element={<MdsStatus />} />
          <Route path="/keys" element={<KeyManagement />} />
        </Route>
        <Route path="*" element={<Navigate to="/tenants" replace />} />
      </Routes>
    </ToastProvider>
  );
}
