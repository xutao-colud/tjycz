import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { validateServiceBase } from './src/mobile/endpoints'
export default defineConfig(({ mode, command }) => {
  const mobile = mode === 'app' || mode === 'app-dev'
  const env = loadEnv(mode, process.cwd(), '')
  const base = mobile && command === 'build'
    ? validateServiceBase(env.VITE_API_BASE_URL || '', mode === 'app-dev')
    : '/api'
  return {
    plugins: [vue()],
    ...(mobile ? { base:'./', define:{ 'import.meta.env.VITE_APP_TARGET':JSON.stringify('native'), 'import.meta.env.VITE_API_BASE_URL':JSON.stringify(base) }, build:{outDir:'dist-app'} } : {}),
    server: { host:'0.0.0.0', port:mobile ? 15175 : 15173, strictPort:true, proxy:{'/api':{target:env.APP_DEV_PROXY || 'http://127.0.0.1:18080',changeOrigin:true}} }
  }
})
