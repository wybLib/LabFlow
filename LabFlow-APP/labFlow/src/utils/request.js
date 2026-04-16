import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/modules/user'

const service = axios.create({
  baseURL: '/api/v1', // 配合 vite.config.js 的代理
  timeout: 10000 
})

// 1. 请求拦截器：自动注入 JWT Token
service.interceptors.request.use(
  config => {
    // const token = localStorage.getItem('token')
    // 修改后 ✅
       const token = sessionStorage.getItem('token')
    if (token) {
      // 一旦你在请求头（Headers）里加入了自定义的字段（比如 Authorization），
      // 或者你的请求体是 application/json，这个跨域请求就会立刻被浏览器升级为“复杂请求”。
      //对于复杂请求  浏览器会先发送一个不带token的OPTIONS 请求给后端  所以后端应该对此放行
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  error => Promise.reject(error)
)

// 2. 响应拦截器：精准匹配后端的 Result 类
service.interceptors.response.use(
  response => {
    // 拿到后端的 Result 对象 JSON
    const res = response.data

    // 【核心修改 1】：你的后端 Result 定义 code === 1 才是成功
    if (res.code === 1) {
      // 业务正常，放行并直接返回内部的 data 数据
      return res.data 
    } 
    // 【核心修改 2】：处理 code !== 1 的业务失败情况 (比如 code === 0，用户已存在)
    else {
      // 你的后端提示信息字段名叫 msg，不是 message
      const errorMsg = res.msg || '系统繁忙，请稍后再试'
      
      // 直接把后端传过来的 "用户已存在" 弹出来
      ElMessage.error(errorMsg)
      
      // 抛出 Promise 异常，打断后续的 .then() 执行，确保不会跳转页面
      return Promise.reject(new Error(errorMsg))
    }
  },
  error => {
    // 处理 HTTP 状态码异常 (比如 401 权限不足, 500 服务器崩溃)
    if (error.response && error.response.status === 401) {
      const userStore = useUserStore()
      userStore.logout()
      userStore.openLogin() // 唤起登录弹窗
      ElMessage.error('登录状态已失效，请重新登录')
    } else {
      ElMessage.error(error.message || '网络异常，请检查后端服务是否启动')
    }
    return Promise.reject(error)
  }
)

export default service