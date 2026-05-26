import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Build everything under /admin/ so the asset URLs match Spring's
// static serving path. e.g. /admin/assets/index-abc.js
export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
  },
  server: {
    proxy: {
      '/admin/api': 'http://localhost:8081',
      '/admin/login': 'http://localhost:8081',
      '/admin/logout': 'http://localhost:8081',
    },
  },
});
