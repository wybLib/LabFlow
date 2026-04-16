import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => {
    // 增加安全防御：防止 sessionStorage 里存了 "undefined" 导致 JSON.parse 报错白屏
    const userInfoStr = sessionStorage.getItem('userInfo')
    let parsedUserInfo = null
    try {
      parsedUserInfo = userInfoStr && userInfoStr !== 'undefined' ? JSON.parse(userInfoStr) : null
    } catch (e) {
      console.error('解析用户信息失败', e)
    }

    return {
      // 🚀 修复 1：初始化必须从 sessionStorage 读取
      token: sessionStorage.getItem('token') || '',
      userInfo: parsedUserInfo,
      isLoginDialogVisible: false
    }
  },
  getters: {
    isLoggedIn: (state) => !!state.token
  },
  actions: {
    openLogin() {
      this.isLoginDialogVisible = true
    },
    closeLogin() {
      this.isLoginDialogVisible = false
    },
    loginSuccess(data) {
      this.token = data.token
      this.userInfo = data.user
      // ✅ 这里你改对了
      sessionStorage.setItem('token', data.token)
      sessionStorage.setItem('userInfo', JSON.stringify(data.user))
      this.isLoginDialogVisible = false
    },
    updateUserInfo(newInfo) {
      this.userInfo = { ...this.userInfo, ...newInfo }
      // 🚀 修复 2：同步资料也必须写进 sessionStorage
      sessionStorage.setItem('userInfo', JSON.stringify(this.userInfo))
    },
    logout() {
      this.token = ''
      this.userInfo = null
      // 🚀 修复 3：退出登录必须删除 sessionStorage 里的数据！
      sessionStorage.removeItem('token')
      sessionStorage.removeItem('userInfo')
    }
  }
})