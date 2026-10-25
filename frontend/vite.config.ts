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
    // 이 출력이 jar에 그대로 들어가 공개 경로(/app/)로 서빙된다. 소스맵을 넣으면
    // 원본 TypeScript가 함께 배포된다. 디버깅이 필요하면 `vite build --sourcemap`.
    sourcemap: false
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
});
