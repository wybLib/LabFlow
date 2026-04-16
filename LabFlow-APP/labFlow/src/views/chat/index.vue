<template>
  <div class="ai-layout">
    <aside class="sidebar" :class="{ 'sidebar-collapsed': isCollapsed }">
      <div class="sidebar-header">
        <button class="new-chat-btn" @click="startNewChat">
          <span class="icon">+</span> 新的对话
        </button>
        <button class="toggle-btn" @click="isCollapsed = !isCollapsed">
          <svg viewBox="0 0 24 24" width="20" height="20" stroke="currentColor" fill="none"><line x1="3" y1="12" x2="21" y2="12"></line><line x1="3" y1="6" x2="21" y2="6"></line><line x1="3" y1="18" x2="21" y2="18"></line></svg>
        </button>
      </div>

      <div class="session-list">
        <div class="list-title">最近的对话</div>
        <div 
          v-for="session in sessionList" 
          :key="session.id"
          class="session-item"
          :class="{ active: currentSessionId === session.id }"
          @click="switchSession(session.id)"
        >
          <span class="session-title">{{ session.title }}</span>
          <button class="delete-btn" @click.stop="deleteSession(session.id)">×</button>
        </div>
      </div>
    </aside>

    <main class="chat-main">
      <header class="mobile-header" v-if="isCollapsed">
        <button @click="isCollapsed = false">☰ 展开历史</button>
        <span>LabFlow AI</span>
      </header>

      <div class="chat-container" ref="chatContainerRef">
        <div v-if="isHistoryLoading" class="loading-history">
          正在读取记忆...
        </div>

        <div v-else-if="messages.length === 0" class="welcome-box">
          <div class="ai-logo">✨</div>
          <h3>LabFlow 研思大模型</h3>
          <p>已接入全站知识库，随时为你答疑解惑。</p>
        </div>

        <div 
          v-for="(msg, index) in messages" 
          :key="index" 
          :class="['message-row', msg.role]"
        >
          <div class="avatar">{{ msg.role === 'ai' ? '🤖' : '👤' }}</div>
          <div class="message-content">
            <div v-if="msg.debugInfo && msg.debugInfo.length > 0" class="debug-panel">
              <span class="loading-icon">⚙️</span>
              <span class="debug-text">
                {{ formatDebugInfo(msg.debugInfo[msg.debugInfo.length - 1]) }}
              </span>
            </div>

            <div class="text-bubble">
              <div 
                v-if="msg.content" 
                class="markdown-body" 
                v-html="renderMarkdown(msg.content)"
              ></div>
              
              <span v-if="msg.isTyping" class="typing-cursor"></span>
            </div>
          </div>
        </div>
      </div>

      <footer class="chat-footer">
        <div class="input-container">
          <textarea 
            ref="textareaRef"
            v-model="userInput" 
            placeholder="搜论文、搜笔记、问技术..." 
            rows="1"
            @keydown.enter.prevent="sendMessage"
            @input="autoResize"
          ></textarea>
          <button class="send-btn" :class="{ active: userInput.trim() && !isLoading }" :disabled="!userInput.trim() || isLoading" @click="sendMessage">🚀</button>
        </div>
      </footer>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus' // 别忘了在顶部按需引入一下，如果有的话
import { fetchChatStream, getChatHistory, deleteChatHistory } from '../../api/chat'
import MarkdownIt from 'markdown-it'
// 🚀 1. 新增引入 userStore，用最稳妥的方式拿用户状态
import { useUserStore } from '../../store/modules/user'

const router = useRouter()
const route = useRoute()
const md = new MarkdownIt({ linkify: true })
// 🚀 2. 初始化 store
const userStore = useUserStore()

const renderMarkdown = (text) => {
  if (!text) return ''
  return md.render(text)
}

const formatDebugInfo = (rawTag) => {
  if (rawTag.includes('search_notes')) return '在全站笔记中检索内容...'
  if (rawTag.includes('get_current_user_info')) return '验证当前用户身份...'
  if (rawTag.includes('get_all_titles')) return '获取全站笔记目录...'
  if (rawTag.includes('get_topic_stats')) return '获取全站话题宏观统计...'
  if (rawTag.includes('get_comments_analysis')) return '分析真实用户评论数据...'
  if (rawTag.includes('get_note_stats')) return '计算平台大盘数据...'
  if (rawTag.includes('llm_call')) return '智能体深度思考中...'
  return '调度内部工具...'
}

// 状态管理
const userInput = ref('')
const messages = ref([])
const isLoading = ref(false)
const isHistoryLoading = ref(false)
const isCollapsed = ref(false)

// 🚀 修改点 3：更新绑定的变量名
const chatContainerRef = ref(null)
const textareaRef = ref(null)

// 🚀 修改动态 Key 获取逻辑：直接从 store 拿，拿不到就用 guest
const getStorageKey = () => {
  const userId = userStore.userInfo?.id || 'guest'
  return `chat_session_list_${userId}`
}

// 会话管理
const currentSessionId = ref('')
const sessionList = ref([])

// 🚀 4. 把读取本地记录抽离成一个单独的方法
const loadLocalSessions = () => {
  const saved = localStorage.getItem(getStorageKey())
  if (saved) {
    sessionList.value = JSON.parse(saved)
  } else {
    sessionList.value = []
  }
}

// 5. 初始化
onMounted(() => {
  // 第一次挂载时尝试加载（如果是路由跳转进来的，此时 userId 已经有了）
  loadLocalSessions() 

  const urlSession = route.query.session
  if (urlSession) {
    currentSessionId.value = urlSession
    loadHistory(urlSession)
  } else {
    startNewChat()
  }
})

// 2. 切换会话 / 加载历史
const switchSession = (id) => {
  if (currentSessionId.value === id) return
  
  // 🚀 核心修复点 1：主动出击！先修改当前 ID，然后直接发请求拉取数据
  currentSessionId.value = id
  loadHistory(id)
  
  // 🚀 顺便把 URL 里的参数改掉，保持链接是可以分享和刷新的
  router.push({ query: { session: id } }) 
}

const loadHistory = async (id) => {
  isHistoryLoading.value = true
  messages.value = [] // 清空屏幕，准备加载新记录
  
  try {
    const res = await getChatHistory(id)
    
    // 🚀 核心防御：不管你的拦截器返回的是包了一层的 {code:1, data: [...]}，
    // 还是直接返回的数组 [...]，这行代码都能精准提取出真实的数组！
    const historyList = res.data || res
    
    if (Array.isArray(historyList) && historyList.length > 0) {
      messages.value = historyList.map(item => ({
        role: item.role,
        content: item.content,
        debugInfo: [], // 历史记录不显示当时的 debug 过程
        isTyping: false
      }))
      scrollToBottom()
    } else {
      // 如果后端查不到数据（比如 Redis 里清空了）
      console.warn("该会话在后端没有历史记录数据")
    }
    
  } catch (e) {
    console.error("历史加载失败", e)
    ElMessage.error("获取历史记录失败，请检查网络或后端日志")
  } finally {
    isHistoryLoading.value = false
  }
}

// 🚀 6. 核心防御：监听 userInfo 的变化！
// 如果用户是按 F5 强刷进来的，app.vue 还在后台异步拿用户信息。
// 等那几百毫秒后 userId 一回来，这里立刻重新读取正确的侧边栏！
watch(() => route.query.session, (newSession) => {
  // 🚀 核心修复点 2：加上判断，防止和我们上面的主动调用发生重复请求
  if (newSession && newSession !== currentSessionId.value) {
    currentSessionId.value = newSession
    loadHistory(newSession)
  }
})

// 3. 开启新对话
const startNewChat = () => {
  const newId = 'sess_' + Date.now().toString(36)
  router.push({ query: { session: newId } })
  currentSessionId.value = newId
  messages.value = []
}

// 🚀 将普通的同步函数改为 async 异步函数
const deleteSession = async (id) => {
  try {
    // 1. 先调用后端接口，把 MySQL 和 Redis 里的数据彻底删掉
    await deleteChatHistory(id)
    
    // 2. 后端删除成功后，再清理前端侧边栏的列表
    sessionList.value = sessionList.value.filter(s => s.id !== id)
    saveSessionList() // 存入 LocalStorage
    
    // 3. 如果删除的刚好是当前正在聊天的会话，就开一个新对话
    if (currentSessionId.value === id) {
      startNewChat()
    }
    
    // 4. 给个温柔的提示
    ElMessage.success('会话已彻底删除')
  } catch (error) {
    ElMessage.error('删除会话失败，请稍后重试')
    console.error(error)
  }
}

// 5. 保存列表到 LocalStorage
const saveSessionList = () => {
  // 🚀 确保保存时也实时获取专属 Key
  localStorage.setItem(getStorageKey(), JSON.stringify(sessionList.value))
}

// 工具函数
const autoResize = () => {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 150) + 'px'
}

// 🚀 修改点 6：修复滚动失效的 Bug，使用 setTimeout 等待 DOM 撑开
const scrollToBottom = () => {
  nextTick(() => {
    setTimeout(() => {
      if (chatContainerRef.value) {
        chatContainerRef.value.scrollTop = chatContainerRef.value.scrollHeight
      }
    }, 20)
  })
}

// 6. 发送消息
const sendMessage = async () => {
  const question = userInput.value.trim()
  if (!question || isLoading.value) return

  if (messages.value.length === 0) {
    sessionList.value.unshift({
      id: currentSessionId.value,
      title: question.length > 12 ? question.substring(0, 12) + '...' : question
    })
    saveSessionList()
  }

  messages.value.push({ role: 'user', content: question })
  messages.value.push({ role: 'ai', content: '', debugInfo: [], isTyping: true })

  const currentAiMsg = messages.value[messages.value.length - 1]

  userInput.value = ''
  isLoading.value = true
  autoResize()
  scrollToBottom()

  await fetchChatStream(
    question,
    currentSessionId.value, 
    (text) => { 
      currentAiMsg.content += text 
      scrollToBottom() 
    },
    (debug) => { 
      currentAiMsg.debugInfo.push(debug) 
      scrollToBottom() 
    },
    (error) => { 
      currentAiMsg.content += `\n[系统异常] ${error}` 
    },
    () => { 
      currentAiMsg.isTyping = false 
      isLoading.value = false 
    }
  )
}
</script>

<style scoped>
.ai-layout {
  display: flex;
  height: calc(100vh - 64px); 
  background: #ffffff;
  overflow: hidden;
}

.sidebar {
  width: 260px;
  background-color: #171717; 
  color: #ececec;
  display: flex;
  flex-direction: column;
  transition: width 0.3s ease;
  flex-shrink: 0;
}
.sidebar-collapsed {
  width: 0;
  overflow: hidden;
}

.sidebar-header {
  padding: 16px;
  display: flex;
  gap: 8px;
}
.new-chat-btn {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  background: #212121;
  color: #fff;
  border: 1px solid #333;
  padding: 10px 14px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
  font-size: 14px;
}
.new-chat-btn:hover { background: #2f2f2f; }
.toggle-btn {
  background: transparent;
  border: none;
  color: #ccc;
  cursor: pointer;
  padding: 8px;
}
.toggle-btn:hover { color: #fff; }

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 12px;
}
.list-title {
  font-size: 12px;
  color: #666;
  padding: 12px;
  margin-top: 10px;
}
.session-item {
  padding: 12px;
  margin-bottom: 4px;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  transition: background 0.2s;
}
.session-item:hover { background: #2f2f2f; }
.session-item.active { background: #2f2f2f; color: #10a37f; }

.delete-btn {
  background: transparent;
  border: none;
  color: #666;
  cursor: pointer;
  opacity: 0;
}
.session-item:hover .delete-btn { opacity: 1; }
.delete-btn:hover { color: #ff4d4f; }

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
}

.mobile-header {
  padding: 12px 20px;
  border-bottom: 1px solid #eee;
  display: flex;
  gap: 16px;
}

.chat-container {
  flex: 1;
  overflow-y: auto;
  padding: 40px 20px;
  max-width: 800px;
  margin: 0 auto;
  width: 100%;
}

.loading-history {
  text-align: center;
  color: #999;
  margin-top: 20px;
}

.message-row { display: flex; gap: 16px; margin-bottom: 30px; }
.message-row.user { flex-direction: row-reverse; }
.avatar { width: 36px; height: 36px; border-radius: 50%; background: #f0f0f0; display: flex; align-items: center; justify-content: center; font-size: 20px;}
.message-content { max-width: 85%; display: flex; flex-direction: column; }
.message-row.user .message-content { align-items: flex-end; }
.text-bubble { padding: 12px 18px; border-radius: 12px; line-height: 1.6; white-space: pre-wrap;}
.message-row.user .text-bubble { background: #f3f4f6; }
.message-row.ai .text-bubble { background: #fff; }

.chat-footer { padding: 20px; background: #fff; }
.input-container { max-width: 800px; margin: 0 auto; display: flex; border: 1px solid #e5e5e5; border-radius: 16px; padding: 8px; background: #f9f9f9; align-items: flex-end;}
textarea { flex: 1; border: none; background: transparent; outline: none; resize: none; padding: 8px; font-size: 15px; }
.send-btn { background: transparent; border: none; font-size: 20px; cursor: pointer; opacity: 0.5; }
.send-btn.active { opacity: 1; }

.markdown-body {
  font-size: 15px;
  line-height: 1.6;
  color: #2c3e50;
  word-wrap: break-word;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  margin-top: 1em;
  margin-bottom: 0.5em;
  font-weight: 600;
  line-height: 1.25;
}
.markdown-body :deep(h2) { font-size: 1.25em; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }
.markdown-body :deep(h3) { font-size: 1.1em; }
.markdown-body :deep(p) { margin-top: 0; margin-bottom: 10px; }
.markdown-body :deep(ul),
.markdown-body :deep(ol) { margin-top: 0; margin-bottom: 10px; padding-left: 20px; }
.markdown-body :deep(li) { margin-bottom: 4px; }
.markdown-body :deep(strong) { font-weight: 600; color: #1a1a1a; }
.markdown-body :deep(code) {
  background-color: rgba(27,31,35,0.05);
  padding: 0.2em 0.4em;
  border-radius: 3px;
  font-family: monospace;
  font-size: 0.9em;
}
.markdown-body :deep(pre) {
  background-color: #f6f8fa;
  padding: 16px;
  border-radius: 6px;
  overflow: auto;
  font-family: monospace;
}
.markdown-body :deep(pre code) {
  background-color: transparent;
  padding: 0;
}
.markdown-body :deep(p:empty) { display: none; }
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin-top: 4px;
  margin-bottom: 8px;
}
</style>