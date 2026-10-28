import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
  build: {
    target: 'es2023',
    sourcemap: false,
    rolldownOptions: {
      output: {
        // Vendor chunks change less often than the application code and are cached separately.
        codeSplitting: {
          groups: [
            { name: 'fluent', test: /node_modules[\\/](@fluentui|@griffel|tabster|keyborg|@floating-ui)/ },
            { name: 'msal', test: /node_modules[\\/]@azure[\\/]msal/ },
            { name: 'vendor', test: /node_modules/ },
          ],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    // Each test renders the whole portal; leave headroom on loaded machines.
    testTimeout: 15_000,
  },
})
