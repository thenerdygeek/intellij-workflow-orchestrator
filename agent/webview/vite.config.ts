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
        'tool-docs': resolve(__dirname, 'tool-docs.html'),
        'api-docs': resolve(__dirname, 'api-docs.html'),
        'artifact-sandbox': resolve(__dirname, 'artifact-sandbox.html'),
        // Showcase is dev-only — excluded from production build
        ...(mode === 'development' ? { showcase: resolve(__dirname, 'showcase.html') } : {}),
        // Playwright harness — dev-only mock-bridge entry for UI smoke tests
        ...(mode === 'development' ? { playwright: resolve(__dirname, 'playwright.html') } : {}),
      },
      output: {
        manualChunks(id) {
          // Only force-merge libraries that are genuinely statically imported by
          // the main entry and whose Rollup default (many tiny per-package chunks)
          // would be worse than a single shared chunk.
          //
          // Libraries whose consumers use dynamic import() — shiki, mermaid/dagre,
          // d3, recharts, roughjs — are intentionally NOT listed here so that
          // Rollup's natural dynamic-import code-splitting keeps them as on-demand
          // chunks and they never appear in the main entry's modulepreload list.
          // sandbox-main.ts is a separate Rollup entry; without forced merging it
          // gets its own chunks and does NOT leak into the main entry's graph.
          if (id.includes('katex')) return 'katex'
          if (id.includes('chart.js')) return 'chartjs'
          if (id.includes('diff2html')) return 'diff2html'
          if (id.includes('cobe')) return 'cobe'
          if (id.includes('motion')) return 'motion'
          if (id.includes('react-simple-maps') || id.includes('topojson')) return 'maps'
          // New sandbox libs (Tier A expansion): split into their own chunks
          // so the sandbox iframe only pays for them on first artifact render.
          if (id.includes('@xyflow/react') || id.includes('@reactflow/')) return 'xyflow'
          if (id.includes('@tanstack/react-table')) return 'react-table'
          if (id.includes('node_modules/date-fns')) return 'date-fns'
          if (id.includes('node_modules/colord')) return 'colord'
          // Radix primitives — group all of them into a single chunk to avoid
          // dozens of tiny per-primitive chunks. Radix shares internal
          // dependencies (react-compose-refs, react-context, etc.) heavily.
          if (id.includes('@radix-ui/')) return 'radix'
        },
      },
    },
    assetsInlineLimit: 8192,
    minify: 'terser',
    terserOptions: {
      // drop_console stripped multimodal:attach diagnostic logs from production
      // builds, making attach-flow bugs invisible. Preserve console.* but keep
      // debugger-statement removal.
      compress: { drop_console: false, drop_debugger: true },
    },
  },
}))
