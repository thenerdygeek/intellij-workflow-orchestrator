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
          // Viz libraries get predictable chunk names for debugging AND to
          // deduplicate between the main entry and the sandbox entry — without
          // these rules, large libraries like d3 would be duplicated into
          // both bundles since Vite's default shared-chunk heuristic is
          // conservative with dynamic-lookup scoped imports.
          if (id.includes('mermaid') || id.includes('dagre')) return 'mermaid'
          if (id.includes('katex')) return 'katex'
          if (id.includes('chart.js')) return 'chartjs'
          if (id.includes('diff2html')) return 'diff2html'
          if (id.includes('recharts')) return 'recharts'
          if (id.includes('lucide-react')) return 'lucide'
          if (id.includes('node_modules/d3')) return 'd3'
          if (id.includes('cobe')) return 'cobe'
          if (id.includes('motion')) return 'motion'
          if (id.includes('roughjs')) return 'roughjs'
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
      compress: { drop_console: true, drop_debugger: true },
    },
  },
}))
