<template>
  <div class="app-container">
    <el-config-provider>
      <header v-if="!$route.meta.fullScreen" class="navbar">
        <div class="logo" @click="router.push('/')">LabFlow <span>/ 研思</span></div>
        
        <div class="search-box">
          <el-input 
            v-model="searchQuery" 
            placeholder="搜索论文、算法或笔记..." 
            class="search-input" 
            clearable 
            @keyup.enter="handleSearch"
            @clear="handleClear"
            @input="handleInput"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>

        <div class="user-action">
        <el-button color="#10a37f" plain round class="ai-btn" @click="handleAction('/ai-chat')">
            <el-icon><MagicStick /></el-icon> LabFlow AI
          </el-button>
          <el-button type="primary" round class="publish-btn" @click="handleAction('/publish')">
            <el-icon><Plus /></el-icon> 发布笔记
          </el-button>
          
          <div v-if="userStore.isLoggedIn" class="user-profile-wrap">
            <el-dropdown trigger="click" @command="handleCommand">
              <div class="el-dropdown-link">
                <el-avatar :size="32" :src="userStore.userInfo?.avatar" />
                <span class="nickname">{{ userStore.userInfo?.name }}</span>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile">
                    <el-icon><User /></el-icon>个人中心
                  </el-dropdown-item>
                  <el-dropdown-item command="logout" divided>
                    <el-icon><SwitchButton /></el-icon>退出登录
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <el-button v-else type="info" plain round @click="userStore.openLogin">登录 / 注册</el-button>
        </div>
      </header>
      
      <main :class="['main-content', { 'full-screen-mode': $route.meta.fullScreen }]">
        <router-view />
      </main>

      <LoginModal />
    </el-config-provider>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue' 
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from './store/modules/user' 
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, User, SwitchButton, MagicStick } from '@element-plus/icons-vue'
import LoginModal from './components/LoginModal.vue' 
import { getUserProfile, logoutAPI } from './api/user' 

const router = useRouter()
const route = useRoute() 
const userStore = useUserStore()
const searchQuery = ref('')

// 全局兜底
onMounted(async () => {
  if (userStore.token && !userStore.userInfo?.id) {
    try {
      const res = await getUserProfile()
      userStore.updateUserInfo(res.data || res) 
      console.log('✅ 全局用户状态已恢复，当前用户ID:', userStore.userInfo?.id)
    } catch (error) {
      console.error('全局拉取用户信息失败，可能 Token 已过期或被篡改', error)
    }
  }
})

// 搜索处理方法
const handleSearch = () => {
  const keyword = searchQuery.value.trim() 
  if (!keyword) {
    router.push({ path: '/' })
    return
  }
  ElMessage.success(`正在为您检索: ${keyword}`)
  router.push({
    path: '/', 
    query: { keyword: keyword } 
  })
}

// 监听 URL 同步搜索框
watch(
  () => route.query.keyword,
  (newKeyword) => {
    searchQuery.value = newKeyword || ''
  },
  { immediate: true } 
)

// 处理清除动作
const handleClear = () => {
  const query = { ...route.query }
  delete query.keyword
  router.push({ path: route.path, query })
}

// 监听键盘输入
const handleInput = (value) => {
  if (!value.trim() && route.query.keyword) {
    handleClear() 
  }
}

// 统一动作拦截
const handleAction = (path) => {
  if (!userStore.isLoggedIn) {
    userStore.openLogin() 
  } else {
    router.push(path)
  }
}

// 处理下拉菜单
const handleCommand = (command) => {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    }).then(async () => {
      try {
        // 1. 🚀 核心：先调用后端真实的退出接口，销毁 Redis 中的 Token
        await logoutAPI()
      } catch (error) {
        console.error('后端退出异常', error)
        // 就算后端异常(比如网断了)，前端也要强制退出，所以不 return
      } finally {
        // 2. 无论后端响应如何，前端必须彻底清空本地缓存的 Token 和 UserInfo
        userStore.logout()
        ElMessage.success('已安全退出')
        
        // 3. 强刷回首页
        setTimeout(() => {
          window.location.href = '/'
        }, 300)
      }
    }).catch(() => {})
  }
}
</script>

<style>
/* 基础样式复用之前的 */
body {
  margin: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', sans-serif;
  background-color: #f8f9fa;
}
.app-container { min-height: 100vh; }
.navbar {
  height: 64px;
  background: #ffffff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 40px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.05);
  position: sticky;
  top: 0;
  z-index: 100;
}
.logo { font-size: 22px; font-weight: 800; cursor: pointer; color: #1e293b; }
.logo span { color: #409EFF; font-weight: 400; }
.search-box { flex: 1; max-width: 400px; margin: 0 40px; }
.search-input :deep(.el-input__wrapper) { border-radius: 20px; background-color: #f0f2f5; box-shadow: none; }
.user-action { display: flex; align-items: center; gap: 20px; }
.user-profile-wrap { display: flex; align-items: center; }
.nickname { margin-left: 8px; font-size: 14px; color: #4b5563; font-weight: 500; }
.el-dropdown-link { cursor: pointer; display: flex; align-items: center; outline: none; }
.main-content { padding: 24px 40px; max-width: 1400px; margin: 0 auto; transition: all 0.3s; }
.main-content.full-screen-mode { padding: 0 !important; max-width: none !important; margin: 0 !important; }
</style>