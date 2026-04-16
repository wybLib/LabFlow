// src/api/topic.js (你需要新建这个文件)
import request from '../utils/request'
export const getAllTopics = () => request({ url: '/topics', method: 'get' })
export const getMySelectedTopics = () => request({ url: '/users/me/topics', method: 'get' })
export const saveMySelectedTopics = (data) => request({ url: '/users/me/topics', method: 'post', data }) // data 形如 [1, 3, 5]