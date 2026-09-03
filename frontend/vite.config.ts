import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath, URL } from 'node:url';

// The /api proxy is what makes JSESSIONID a first-party cookie in development: the SPA and the
// backend share an origin, so there is no CORS negotiation and no SameSite problem to work
// around. Production serves the built assets from the same Spring origin, so the two behave
// identically and nothing about auth changes between them.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // Overridable so a dev server can be pointed at a scratch instance without editing
        // this file; unset, it is the local backend exactly as before.
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
});
