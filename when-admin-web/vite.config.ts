import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true },
  // Vitest picks this up at runtime; Vite's UserConfig type does not declare it.
  // @ts-expect-error vitest config
  test: { environment: 'jsdom' }
})
