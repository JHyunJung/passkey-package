import React from 'react';
import ReactDOM from 'react-dom/client';
import './styles/globals.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <div style={{ padding: 40 }}>
      <h1>admin-ui redesign (Phase E1.2)</h1>
      <p style={{ color: 'var(--text-mute)' }}>
        tokens.css imported. accent: <span style={{ color: 'var(--accent)' }}>accent color text</span>
      </p>
    </div>
  </React.StrictMode>
);
