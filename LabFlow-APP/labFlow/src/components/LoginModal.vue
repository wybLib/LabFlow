<template>
  <el-dialog
    v-model="userStore.isLoginDialogVisible"
    width="420px"
    :show-close="false"
    align-center
    class="login-dialog"
  >
    <div class="login-modal-content">
      <div class="modal-header">
        <div class="logo">LabFlow <span>/ 研思</span></div>
        <p>开启你的科研协作之旅</p>
      </div>

      <el-tabs v-model="activeTab" stretch>
        <el-tab-pane label="账号登录" name="login">
          <el-form :model="form" class="mt-20">
            <el-form-item>
              <el-input v-model="form.username" placeholder="用户名/邮箱" />
            </el-form-item>
            <el-form-item>
              <el-input v-model="form.password" type="password" placeholder="密码" show-password @keyup.enter="handleLogin" />
            </el-form-item>
            <el-button type="primary" class="w-100" @click="handleLogin" :loading="loading">立即登录</el-button>
          </el-form>
        </el-tab-pane>
        
        <el-tab-pane label="账号注册" name="register">
           <el-form :model="form" :rules="rules" ref="registerFormRef" class="mt-20">
  <el-form-item prop="username">
    <el-input v-model="form.username" placeholder="设置用户名 (5-16位非空字符)" />
  </el-form-item>
  <el-form-item prop="password">
    <el-input v-model="form.password" type="password" placeholder="设置密码 (5-16位非空字符)" show-password />
  </el-form-item>
  <el-button type="success" class="w-100" @click="handleRegister" :loading="loading">提交注册</el-button>
</el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="modal-footer">
        登录即代表同意 <el-link type="primary" :underline="false">用户协议</el-link>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useUserStore } from '../store/modules/user'
import { ElMessage } from 'element-plus'
import { login, register } from '../api/user' // 引入真实接口

const userStore = useUserStore()
const activeTab = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '' })
// 1. 获取表单的 ref
const registerFormRef = ref(null)

// 2. 定义 Element Plus 的校验规则
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^\S{5,16}$/, message: '用户名必须是5-16位非空字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { pattern: /^\S{5,16}$/, message: '密码必须是5-16位非空字符', trigger: 'blur' }
  ]
}

// 真实登录请求
const handleLogin = async () => {
  if (!form.username || !form.password) return ElMessage.warning('请输入账号和密码')
  loading.value = true
  try {
    const res = await login({ username: form.username, password: form.password })
    
    // 只要你的 res.user 里包含了 bio，这行代码就会把 bio 自动存入 Pinia 和 localStorage
    userStore.loginSuccess({
      token: res.token,
      user: res.user 
    })
    
    ElMessage.success('登录成功，欢迎回来')
  } catch (error) {
    // 错误由 axios 拦截器统一处理，这里静默即可
  } finally {
    loading.value = false
  }
}

// 真实注册请求
const handleRegister = async () => {
  if (!registerFormRef.value) return
  
  // 前端执行表单校验
  await registerFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        // 前端校验通过，发给后端（如果此时由于并发等原因后端校验失败，
        // 后端的 GlobalExceptionHandler 会捕获并返回 { code: 1, message: "..." }
        // axios 拦截器会直接把它以 ElMessage.error 的形式弹出来）
        await register({ username: form.username, password: form.password })
        ElMessage.success('注册成功，请登录')
        activeTab.value = 'login' 
        // 清空表单，方便下一次操作
        form.username = ''
        form.password = ''
      } catch (error) {
        // 请求报错时的处理，错误提示已被 axios 拦截器代理，此处无需赘述
      } finally {
        loading.value = false
      }
    }
  })
}
</script>

<style scoped>
.login-modal-content { padding: 10px 20px; }
.modal-header { text-align: center; margin-bottom: 25px; }
.modal-header .logo { font-size: 26px; font-weight: 800; margin-bottom: 8px; color: #1e293b; }
.modal-header span { color: #409EFF; }
.modal-header p { color: #909399; font-size: 14px; }
.mt-20 { margin-top: 20px; }
.w-100 { width: 100%; height: 42px; border-radius: 8px; margin-top: 10px; }
.modal-footer { text-align: center; margin-top: 25px; font-size: 12px; color: #bbb; }
:deep(.el-dialog) { border-radius: 16px; overflow: hidden; }
</style>