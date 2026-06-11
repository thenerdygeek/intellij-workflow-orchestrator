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
          // Force-merge only libraries whose Rollup default (many tiny per-package
          // chunks) would be worse than one shared chunk, AND whose chunk can never
          // land in the MAIN entry's modulepreload list. Two safe categories:
          //  - main-entry statics that are preloaded anyway (radix);
          //  - sandbox-entry statics (cobe/maps/xyflow/react-table/date-fns/colord/
          //    motion) and dynamic-only subgraphs (katex/chartjs/diff2html):
          //    a manualChunks name on an async-only or sandbox-only subgraph stays
          //    out of index.html's preloads.
          //
          // NEVER list a library whose payload is dynamically imported from the
          // MAIN entry (shiki grammars/themes/wasm, mermaid/dagre, lucide): merging
          // its static core with its lazy payload turns the whole thing into a
          // startup-critical static chunk — the audit P0-2 bug (~8MB eager parse).
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
