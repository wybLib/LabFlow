import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'Home', component: () => import('../views/home/index.vue'), meta: { title: 'LabFlow - 首页' } },
  { path: '/note/:id', name: 'NoteDetail', component: () => import('../views/note/detail.vue'), meta: { title: '笔记详情' } },
  // 新增的三个核心页面
  { path: '/login', name: 'Login', component: () => import('../views/login/index.vue'), meta: { title: '登录 / 注册' ,fullScreen: true} },
  { path: '/publish', name: 'Publish', component: () => import('../views/publish/index.vue'), meta: { title: '发布笔记' } },
  { path: '/profile', name: 'Profile', component: () => import('../views/profile/index.vue'), meta: { title: '个人中心' } },
  {
  path: '/ai-chat',
  name: 'AiChat',
  component: () => import('../views/chat/index.vue'),
  meta: { title: 'AI 智能体' }
}
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = to.meta.title || 'LabFlow'
  // 这里未来可以加上前端鉴权逻辑：如果访问 /publish 且没 token，则跳转 /login
  next()
})


export default router