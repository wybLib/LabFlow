// src/api/chat.js
import { ElMessage } from 'element-plus'
import request from '../utils/request' // 复用你的 request 拿 token

/**
 * 1. 获取指定会话的历史记录 (走 Axios，享受你封装的拦截器和 Token 注入)
 * @param {string} sessionId - 会话ID
 */
export function getChatHistory(sessionId) {
  return request({ 
    // 直接跨域请求 Python 端接口
    url: `http://localhost:8001/ai/chat/history?session_id=${sessionId}`, 
    method: 'get' 
  })
}

/**
 * 2. AI 流式对话接口 (绕过 Axios，使用原生 Fetch 实现流式打字机)
 * @param {string} question - 用户问题
 * @param {string} sessionId - 会话ID
 * @param {function} onMessage - 接收到普通文本的回调
 * @param {function} onDebug - 接收到调试信息的回调
 * @param {function} onError - 接收到错误信息的回调
 * @param {function} onDone - 结束时的回调
 */
export async function fetchChatStream(question, sessionId, onMessage, onDebug, onError, onDone) {
  // 从 sessionStorage 取 Token
  const token = sessionStorage.getItem('token') || ''

  if (!token) {
    ElMessage.error('请先登录后再使用 AI 助手')
    onDone()
    return
  }

  try {
    const response = await fetch('http://localhost:8001/ai/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({ question, session_id: sessionId })
    })

    if (!response.ok) {
      if (response.status === 401) {
        ElMessage.error('AI 助手权限已过期，请重新登录')
      } else {
        throw new Error(`HTTP Error: ${response.status}`)
      }
      onDone()
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    
    // 🚀🚀🚀 核心修改：增加缓冲池，防止网络分包截断了中文字符串
    let buffer = '' 

    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        // 如果流结束时，缓冲池里还有残留的最后一点数据，强制处理掉
        if (buffer.trim() && buffer.startsWith('data: ')) {
          const data = buffer.replace('data: ', '')
          if (!data.startsWith('[DEBUG:') && !data.startsWith('[ERROR]')) {
             onMessage(data.replace(/\\n/g, '\n'))
          }
        }
        break
      }

      // 🚀 将新拿到的二进制块解码，并拼接到缓冲池中
      buffer += decoder.decode(value, { stream: true })
      
      // 按换行符切分数据
      const lines = buffer.split('\n')
      
      // 🚀 极其关键：把最后一行（极有可能是不完整的半截数据）弹出来，重新塞回缓冲池，等下次一起拼！
      buffer = lines.pop()

      for (const line of lines) {
        if (!line.trim() || !line.startsWith('data: ')) continue
        
        // 剥离掉 'data: ' 前缀
        const data = line.replace('data: ', '')
        
        // 分发不同类型的事件
        if (data.startsWith('[DEBUG:')) {
          onDebug(data)
        } else if (data.startsWith('[ERROR]')) {
          onError(data)
        } else {
          // 将后端的转义换行符还原，触发前端文字追加
          const content = data.replace(/\\n/g, '\n')
          onMessage(content)
        }
      }
    }
  } catch (error) {
    console.error("AI 接口请求异常:", error)
    onError(`请求失败: ${error.message}`)
  } finally {
    // 无论成功失败，确保结束动画
    onDone()
  }
}

/**
 * 3. 删除指定会话历史 (同时清理 MySQL 和 Redis)
 * @param {string} sessionId - 会话ID
 */
export function deleteChatHistory(sessionId) {
  return request({ 
    url: `http://localhost:8001/ai/chat/history?session_id=${sessionId}`, 
    method: 'delete' 
  })
}