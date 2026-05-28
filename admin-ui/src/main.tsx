import React from 'react';
import ReactDOM from 'react-dom/client';
import './styles/globals.css';
import { ToastHost } from '@/shell/ToastHost';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ToastHost>
      <div style={{ padding: 40 }}>
        <h1>shell primitives (Phase E1.3)</h1>
      </div>
    </ToastHost>
  </React.StrictMode>
);
