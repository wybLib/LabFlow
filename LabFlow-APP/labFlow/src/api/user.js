import request from '../utils/request'

export function login(data) {
  return request({ url: '/auth/login', method: 'post', data })
}

export function register(data) {
  return request({ url: '/auth/register', method: 'post', data })
}

export function getUserProfile() {
  return request({ url: '/users/me', method: 'get' })
}

export function updateUserProfile(data) {
  return request({ url: '/users/me', method: 'put', data })
}

// 个人中心的我的笔记 (支持分页)
export function getMyPublishedNotes(params) {
  return request({ url: '/users/me/notes', method: 'get', params })
}

// 个人中心的我的收藏/点赞 (支持分页)
export function getMyLikedNotes(params) {
  return request({ url: '/users/me/likes', method: 'get', params })
}

// 真实退出登录接口
export const logoutAPI = () => {
  return request.post('/auth/logout')
}

// 修改密码接口
export const updatePasswordAPI = (data) => {
  return request.patch('/users/me/password', data)
}