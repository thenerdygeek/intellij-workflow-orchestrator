import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    // Allow sandboxed iframes (origin "null") to load scripts during development.
    // Needed because artifact-sandbox.html runs in sandbox="allow-scripts" which
    // sets origin to null, and module scripts always use CORS mode.
    cors: true,
  },
  build: {
    outDir: '../src/main/resources/webview/dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        'plan-editor': resolve(__dirname, 'plan-editor.html'),
        'artifact-sandbox': resolve(__dirname, 'artifact-sandbox.html'),
        // Showcase is dev-only — excluded from production build
        ...(mode === 'development' ? { showcase: resolve(__dirname, 'showcase.html') } : {}),
      },
      output: {
        manualChunks(id) {
          // Viz libraries get predictable chunk names for debugging
          // These are installed in later tasks (15-17)
          if (id.includes('mermaid') || id.includes('dagre')) return 'mermaid'
          if (id.includes('katex')) return 'katex'
          if (id.includes('chart.js')) return 'chartjs'
          if (id.includes('diff2html')) return 'diff2html'
          if (id.includes('recharts')) return 'recharts'
        },
      },
    },
    assetsInlineLimit: 8192,
    minify: 'terser',
    terserOptions: {
      compress: { drop_console: true, drop_debugger: true },
    },
  },
}))
