import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    port: 5173,
    proxy: {
      '/admin': 'http://127.0.0.1:28080',
      '/api': 'http://127.0.0.1:28080'
    }
  },
  // Vitest picks this up at runtime; Vite's UserConfig type does not declare it.
  // @ts-expect-error vitest config
  test: { environment: 'jsdom' }
})
