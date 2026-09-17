import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { configDefaults, defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // 프로젝트 내부 모듈을 일관된 @ 경로로 불러온다.
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    // 허용 목록이다. 여기에 걸리지 않는 테스트 파일은 실패하지 않고 조용히 수집되지 않으므로,
    // 새 `.test.ts`를 만들 때마다 이 목록을 함께 넓힌다.
    // `src/diagnosis/testCollection.guard.test.tsx`가 빠진 파일을 찾아 실패시킨다.
    include: [
      'src/**/*.test.tsx',
      'src/api/*.test.ts',
      'src/auth/**/*.test.ts',
      'src/components/marketflow/*.test.ts',
      'src/diagnosis/**/*.test.ts',
      'src/marketflow/**/*.test.ts',
      'src/utils/analysisPeriod.test.ts',
      'src/utils/warmupRule.test.ts',
      'src/utils/validationRules.test.ts',
    ],
    // 이 파일은 node:test 전용이며 아래 package.json의 test:legacy에서 별도로 실행한다.
    exclude: [...configDefaults.exclude, 'src/api/market.test.ts'],
    setupFiles: ['./src/test/setup.ts'],
  },
  server: {
    port: 5173,
    proxy: {
      // 개발 중 백엔드 API 프록시 (CORS 회피)
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
