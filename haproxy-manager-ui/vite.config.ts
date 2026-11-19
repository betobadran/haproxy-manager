import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Em desenvolvimento: `npm run dev` faz proxy de /api para o manager em :8080
export default defineConfig({
  plugins: [react()],
  build: { outDir: 'dist', emptyOutDir: true, sourcemap: false },
  server: { proxy: { '/api': 'http://localhost:8080', '/metrics': 'http://localhost:8080' } },
});
