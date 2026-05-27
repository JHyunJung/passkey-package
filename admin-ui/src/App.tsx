import { useEffect } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Layout from './components/Layout';
import TenantList from './pages/TenantList';
import TenantCreate from './pages/TenantCreate';
import TenantDetail from './pages/TenantDetail';
import Activity from './pages/Activity';
import AuditLog from './pages/AuditLog';
import MdsStatus from './pages/MdsStatus';
import KeyManagement from './pages/KeyManagement';
import { ToastProvider, useToast } from './components/Toast';
import IdleTimeout from './components/IdleTimeout';
import { ApiError } from './api/types';
import { MeProvider } from './me/MeContext';

function ApiErrorBridge() {
  const toast = useToast();
  useEffect(() => {
    const h = (e: PromiseRejectionEvent) => {
      const r = e.reason;
      if (r instanceof ApiError) {
        toast({
          kind: 'err',
          title: r.serverMessage,
          message: `[${r.code}] HTTP ${r.httpStatus}`,
          traceId: r.traceId,
        });
        e.preventDefault();
      }
    };
    window.addEventListener('unhandledrejection', h);
    return () => window.removeEventListener('unhandledrejection', h);
  }, [toast]);
  return null;
}

export default function App() {
  return (
    <ToastProvider>
      <ApiErrorBridge />
      <IdleTimeout />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<MeProvider><Layout /></MeProvider>}>
          <Route path="/tenants" element={<TenantList />} />
          <Route path="/tenants/new" element={<TenantCreate />} />
          <Route path="/tenants/:id" element={<TenantDetail />} />
          <Route path="/activity" element={<Activity />} />
          <Route path="/audit" element={<AuditLog />} />
          <Route path="/mds" element={<MdsStatus />} />
          <Route path="/keys" element={<KeyManagement />} />
        </Route>
        <Route path="*" element={<Navigate to="/tenants" replace />} />
      </Routes>
    </ToastProvider>
  );
}
