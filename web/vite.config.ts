import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development the control plane runs on :8088 and the app proxies to it, so there is no CORS to configure.
// A deployed build talks to VITE_API_URL instead (baked in at build time).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5199, // not 5173: it is commonly taken by other projects
    proxy: {
      '/api': 'http://localhost:8088',
      '/paypal': 'http://localhost:8088',
    },
  },
})
