/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import { tanstackRouter } from '@tanstack/router-plugin/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { securityHeaders } from './security-headers.mjs';

// The SPA and the API share one origin (ADR-023): in development Vite proxies /api to the backend.
const backend = process.env.ERP_BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [
    tanstackRouter({ target: 'react', autoCodeSplitting: true, routesDirectory: './src/routes' }),
    react(),
    tailwindcss(),
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: backend, changeOrigin: false } },
  },
  // `vite preview` serves the production build with the production security headers (SECURITY.md §10.1).
  preview: {
    port: 4173,
    strictPort: true,
    headers: securityHeaders,
    proxy: { '/api': { target: backend, changeOrigin: false } },
  },
  // Source maps are written for error reporting but not referenced from the served bundles.
  build: { sourcemap: 'hidden', target: 'es2022' },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'tests/**/*.test.ts'],
    css: false,
  },
});
