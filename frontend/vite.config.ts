import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/app/',
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts']
  },
  build: {
    outDir: '../src/main/resources/static/app',
    emptyOutDir: true,
    sourcemap: true
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
});
