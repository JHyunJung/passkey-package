import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// Build everything under /admin/ so the asset URLs match Spring's
// static serving path. e.g. /admin/assets/index-abc.js
export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
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
