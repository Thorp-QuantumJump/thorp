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
      // 011 사례 연결. 금지 표현 정적 검사가 이 폴더의 `.test.ts`로 들어온다(011 T081).
      'src/components/regimelink/*.test.ts',
      'src/components/regime/*.test.ts',
      'src/components/result/*.test.ts',
      'src/hooks/*.test.ts',
      'src/components/records/*.test.ts',
      'src/test/resultFixtures.test.ts',
      'src/diagnosis/**/*.test.ts',
      'src/marketflow/**/*.test.ts',
      'src/utils/analysisPeriod.test.ts',
      'src/utils/evaluationWindow.test.ts',
      'src/utils/warmupRule.test.ts',
      'src/utils/validationRules.test.ts',
    ],
    // 이 파일은 node:test 전용이며 아래 package.json의 test:legacy에서 별도로 실행한다.
    exclude: [...configDefaults.exclude, 'src/api/market.test.ts'],
    setupFiles: ['./src/test/setup.ts'],
    // 파일마다 jsdom을 새로 만들어 병렬로 돌기 때문에, 입력을 한 글자씩 흘려 넣는 무거운 화면
    // 테스트는 기본 한계 5초를 넘길 때가 있다. 코드가 멀쩡한데도 실행할 때마다 다른 파일이 실패하면
    // 결과를 믿을 수 없으므로 한계를 늘린다. 단언은 그대로이니 진짜 멈춘 테스트는 여전히 실패한다.
    testTimeout: 20_000,
    hookTimeout: 20_000,
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
