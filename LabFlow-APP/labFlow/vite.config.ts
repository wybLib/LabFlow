import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 当你前端请求 /api/v1/... 时，Vite 会自动帮你代理到后端的 8080 端口
      '/api/v1': {
        target: 'http://localhost:8080', // 你未来 SpringBoot 服务的地址
        changeOrigin: true,
        // 如果后端接口没有 /api/v1 前缀，可以开启下面这行重写路径
        // rewrite: (path) => path.replace(/^\/api\/v1/, '') 
      },
      // WebSocket 代理配置
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  }
})