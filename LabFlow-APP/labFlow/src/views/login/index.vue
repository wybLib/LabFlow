<template>
  <div class="login-page">
    <div class="login-left">
      <div class="brand-content">
        <h1>LabFlow</h1>
        <p>让每一行代码和实验都有迹可循</p>
        <div class="feature-list">
          <div class="f-item"><span>✔</span> 高性能缓存架构</div>
          <div class="f-item"><span>✔</span> 实时学术协作</div>
          <div class="f-item"><span>✔</span> 异步数据一致性保障</div>
        </div>
      </div>
    </div>

    <div class="login-right">
      <el-card class="login-card">
        <div class="form-header">
          <h2>{{ activeTab === 'login' ? '欢迎回来' : '加入我们' }}</h2>
          <p>请登录您的科研账号</p>
        </div>

        <el-tabs v-model="activeTab" stretch class="custom-tabs">
          <el-tab-pane label="账号登录" name="login">
            <el-form :model="loginForm" :rules="rules" ref="loginRef">
              <el-form-item prop="username">
                <el-input v-model="loginForm.username" placeholder="用户名/邮箱" prefix-icon="User" />
              </el-form-item>
              <el-form-item prop="password">
                <el-input v-model="loginForm.password" type="password" placeholder="密码" prefix-icon="Lock" show-password />
              </el-form-item>
              <el-button type="primary" class="w-100" @click="handleLogin">进入实验室</el-button>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="快速注册" name="register">
             <el-form :model="regForm" :rules="rules" ref="regRef">
              <el-form-item prop="username">
                <el-input v-model="regForm.username" placeholder="设置用户名" prefix-icon="User" />
              </el-form-item>
              <el-form-item prop="password">
                <el-input v-model="regForm.password" type="password" placeholder="设置 6 位以上密码" prefix-icon="Lock" show-password />
              </el-form-item>
              <el-button type="success" class="w-100" @click="handleRegister">提交申请</el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>

        <div class="form-footer">
          注册即代表同意 <el-link type="primary">服务协议</el-link>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  height: 100vh;
  width: 100vw;
  overflow: hidden;
  /* 使用深色科技渐变背景，统一感极强 */
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
}

.login-left {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px;
  color: white;
}

.brand-content h1 {
  font-size: 64px;
  margin: 0;
  letter-spacing: -2px;
  background: linear-gradient(to right, #60a5fa, #3b82f6);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.brand-content p {
  font-size: 20px;
  color: #94a3b8;
  margin: 10px 0 40px 0;
}

.feature-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.f-item {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #cbd5e1;
}

.f-item span {
  color: #3b82f6;
  font-weight: bold;
}

.login-right {
  width: 550px;
  background: rgba(255, 255, 255, 0.02);
  backdrop-filter: blur(20px);
  display: flex;
  align-items: center;
  justify-content: center;
  border-left: 1px solid rgba(255, 255, 255, 0.1);
}

.login-card {
  width: 380px;
  background: #ffffff !important;
  border-radius: 16px;
  padding: 10px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
}

.form-header {
  text-align: center;
  margin-bottom: 30px;
}

.form-header h2 {
  margin: 0;
  color: #1e293b;
  font-size: 24px;
}

.form-header p {
  color: #64748b;
  font-size: 14px;
  margin-top: 8px;
}

.w-100 {
  width: 100%;
  height: 45px;
  font-size: 16px;
  margin-top: 20px;
  border-radius: 8px;
}

.custom-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.custom-tabs :deep(.el-tabs__item) {
  font-size: 16px;
  color: #94a3b8;
}

.custom-tabs :deep(.el-tabs__item.is-active) {
  color: #3b82f6;
  font-weight: bold;
}

.form-footer {
  text-align: center;
  margin-top: 25px;
  font-size: 12px;
  color: #94a3b8;
}

/* 适配移动端 */
@media (max-width: 900px) {
  .login-left { display: none; }
  .login-right { width: 100%; border-left: none; }
}
</style>

<script setup>
// ... 逻辑保持不变 (handleLogin, handleRegister, rules等) ...
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'

const router = useRouter()
const activeTab = ref('login')
const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', password: '' })

const handleLogin = () => {
  localStorage.setItem('token', 'real_token_demo')
  ElMessage.success('登录成功')
  router.push('/')
}
</script>