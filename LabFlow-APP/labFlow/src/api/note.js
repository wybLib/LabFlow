import request from '../utils/request'

/**
 * 1. 获取笔记列表 (支持分页、分类、关键字搜索)
 * @param {Object} params - 例如 { page: 1, size: 10, topic: 'fsl', keyword: 'LangChain' }
 */
export function getNoteList(params) {
  return request({ url: '/notes', method: 'get', params })
}

/**
 * 2. 获取笔记详情 (触发后端 Redisson 防击穿)
 */
export const getNoteDetail = (id, params) => {
  return request({ url: `/notes/${id}`, method: 'get', params })
}

/**
 * 3. 发布新笔记
 */
export function publishNote(data) {
  return request({ url: '/notes', method: 'post', data })
}

/**
 * 4. 笔记点赞/取消点赞 (触发后端 Redis Set + RabbitMQ)
 */
export function likeNote(id) {
  return request({ url: `/notes/${id}/like`, method: 'post' })
}

/**
 * 5. 获取笔记的评论列表 (支持分页)
 * @param {Object} params - 例如 { page: 1, size: 20 }
 */
export function getComments(noteId, params) {
  return request({ url: `/notes/${noteId}/comments`, method: 'get', params })
}

/**
 * 6. 发表 HTTP 评论 (后续通过 WS 推送)
 */
export function addComment(noteId, data) {
  return request({ url: `/notes/${noteId}/comments`, method: 'post', data })
}

// src/api/note.js (补充推荐接口)
export function getRecommendNotes(params) {
  return request({
    url: '/notes/recommend',
    method: 'get',
    params: params // 🚀 把 page, size, keyword 拼接到 URL 问号后面
  })
}

// 修改/更新笔记
export const updateNote = (id, data) => {
  return request({
    url: `/notes/${id}`, // 注意替换成你后端的真实路径
    method: 'put',
    data
  })
}

// 删除笔记
export const deleteNote = (id) => {
  return request({ url: `/notes/${id}`, method: 'delete' })
}
