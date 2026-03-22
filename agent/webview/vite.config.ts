import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  build: {
    outDir: '../src/main/resources/webview/dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          // Viz libraries get predictable chunk names for debugging
          // These are installed in later tasks (15-17)
          if (id.includes('mermaid')) return 'mermaid'
          if (id.includes('katex')) return 'katex'
          if (id.includes('chart.js')) return 'chartjs'
          if (id.includes('dagre')) return 'dagre'
          if (id.includes('diff2html')) return 'diff2html'
        },
      },
    },
    assetsInlineLimit: 8192,
    minify: 'terser',
    terserOptions: {
      compress: { drop_console: true, drop_debugger: true },
    },
  },
})
