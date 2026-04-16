<template>
  <div class="note-detail-container" v-loading="loading">
    <el-page-header @back="$router.back()" title="返回上一页" class="page-header">
      <template #content>
        <span class="text-large font-600 text-gray"> 笔记详情 </span>
        <el-tag type="success" effect="light" round class="ml-15" v-if="onlineCount > 0">
           👀 当前 {{ onlineCount }} 人正在看
        </el-tag>
      </template>
    </el-page-header>
  
    <div v-if="note" class="detail-content">
      <el-card shadow="never" class="article-card mb-20">
        <div class="title-wrapper">
  <h1 class="note-title">{{ note.title }}</h1>
  
  <div class="action-buttons" v-if="userStore.userInfo?.id && (userStore.userInfo.id == note.userId || userStore.userInfo.id == note.user_id || userStore.userInfo.id == note.authorId)">
    <el-button type="primary" plain round icon="Edit" @click="goToEdit(note.id)">编辑内容</el-button>
    <el-button type="danger" plain round icon="Delete" @click="handleDelete(note.id)">删除笔记</el-button>
  </div>
</div>

        <div class="author-info">
           <!-- <el-avatar :size="45" :src="note.avatar || (userStore.userInfo?.id == (note.userId || note.user_id) ? userStore.userInfo?.avatar : '')" /> -->
            <el-avatar :size="45" :src="note.avatar || ''" />
           <div class="info-text">
             <div class="name">
                <!-- {{ note.author || (userStore.userInfo?.id == (note.userId || note.user_id) ? userStore.userInfo?.name : '匿名作者') }} -->
                  {{ note.name || '匿名作者' }}
              </div>
            <div class="meta-data">
              <span>发布于: {{ note.createTime?.replace('T', ' ') || '最近' }}</span>
              <el-divider direction="vertical" />
              <span>阅读量: {{ note.views || 0 }}</span>
            </div>
          </div>
        </div>
        <el-divider />
        <div class="note-body"><p>{{ note.content }}</p></div>
        
        <div class="actions mt-30">
          <el-button :type="note.isLiked ? 'primary' : 'default'" size="large" round class="like-btn" @click="handleLike">
            <el-icon><Star :class="{ 'is-active': note.isLiked }" /></el-icon> 
            {{ note.isLiked ? '已赞' : '点赞' }} ({{ formatNumber(note.likes) }})
          </el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="comments-card">
        <template #header>
          <div class="card-header"><span class="comment-title">全部评论 ({{ note.commentCount || 0 }})</span></div>
        </template>
        
        <div class="comment-input-box mb-20">
          <el-avatar :size="36" :src="userStore.isLoggedIn ? userStore.userInfo?.avatar : ''" class="mr-3" />
          <el-input v-model="newComment" placeholder="输入评论参与探讨..." @keyup.enter="handleSendComment" class="flex-1">
            <template #append><el-button @click="handleSendComment" type="primary" :loading="submitting">发送</el-button></template>
          </el-input>
        </div>
        
        <div class="comment-list">
          <div v-for="msg in comments" :key="msg.id" class="comment-item">
            <el-avatar :size="32" :src="msg.avatar" class="mr-3" />
            <div class="comment-content">
              <div class="comment-user">{{ msg.name || '热心网友' }}</div>
              <div class="comment-text">{{ msg.content }}</div>
              <div class="comment-time">
                <span>{{ msg.createTime?.replace('T', ' ') }}</span>
                <span class="ml-15">👍 {{ msg.likes || 0 }}</span>
              </div>
            </div>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router' 
import { ElMessage , ElMessageBox} from 'element-plus'
import { Star, Edit } from '@element-plus/icons-vue' 
import { useUserStore } from '../../store/modules/user'
import { getNoteDetail, getComments, addComment, likeNote, deleteNote} from '../../api/note'

const route = useRoute()
const router = useRouter() 
const userStore = useUserStore()
const noteId = route.params.id

const loading = ref(false)
const submitting = ref(false)
const note = ref(null)
const comments = ref([])
const newComment = ref('')

const onlineCount = ref(1) 
let ws = null 

const handleDelete = (id) => {
  ElMessageBox.confirm(
    '确定要永久删除这篇笔记吗？删除后无法恢复！',
    '危险操作',
    {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning',
    }
  ).then(async () => {
    loading.value = true
    try {
      await deleteNote(id)
      ElMessage.success('笔记已删除')
      // 删除成功后，踢回个人中心或首页
      router.replace('/profile') 
    } catch (error) {
      console.error(error)
    } finally {
      loading.value = false
    }
  }).catch(() => {
    // 取消删除，什么也不做
  })
}
const formatNumber = (num) => num >= 1000 ? (num / 1000).toFixed(1) + 'k' : (num || 0)

const goToEdit = (id) => {
  router.push(`/publish?id=${id}`)
}
const initWebSocket = () => {
  ws = new WebSocket(`ws://localhost:8080/ws/note/online/${noteId}`)
  ws.onopen = () => { console.log('✅ WebSocket 连接成功') }
  
  ws.onmessage = (event) => {
    const res = JSON.parse(event.data)
    
    if (res.type === 'ONLINE_COUNT') { 
        onlineCount.value = res.count 
    } 
    // 🚀 核心补充：处理新评论推送
    else if (res.type === 'NEW_COMMENT') {
        const newMsg = res.comment // 拿到后端传过来的 newComment 对象
        // 加个容错防线
        if (!newMsg) return
        
        // 为了防止自己发的评论出现两次（双重渲染），加个简单的防重判断
        // 真实业务中可以用 id 判断，这里暂时用内容和名字判断
        const isExist = comments.value.some(c => c.content === newMsg.content && c.name === newMsg.name)
        
        if (!isExist) {
            // 将新评论推到数组最前面，Vue 会自动渲染出来！
            comments.value.unshift(newMsg)
            
            // 顺手给个小提示
            ElMessage({
              message: `收到 ${newMsg.name} 的新评论`,
              type: 'success',
              plain: true
            })
        }
    }
  }
  
  ws.onclose = () => { console.log('❌ WebSocket 连接已断开') }
  ws.onerror = (error) => { console.error('WebSocket 发生异常', error) }
}

const fetchDetailAndComments = async () => {
  loading.value = true
  try {
    const [detailRes, commentsRes] = await Promise.all([
      getNoteDetail(noteId),
      getComments(noteId, { page: 1, size: 20 })
    ])
    note.value = detailRes.data || detailRes 
    comments.value = commentsRes.data?.list || commentsRes.list || commentsRes

    // 🚀🚀🚀 前端超级排雷器 🚀🚀🚀
    console.log('--- 权限排雷日志 ---')
    console.log('1. 当前登录用户的 ID 是:', userStore.userInfo?.id)
    console.log('2. 后端返回的笔记详情里，作者的 ID 是:', note.value.userId || note.value.user_id || note.value.authorId || '🚨警告：后端根本没传作者ID！')
    console.log('-------------------')

  } catch (error) {
    console.error('拉取详情失败', error)
  } finally {
    loading.value = false
  }
}

const handleLike = async () => {
  if (!userStore.isLoggedIn) return userStore.openLogin()
  note.value.isLiked = !note.value.isLiked
  note.value.isLiked ? note.value.likes++ : note.value.likes--
  try { await likeNote(noteId) } 
  catch (e) { note.value.isLiked = !note.value.isLiked; note.value.isLiked ? note.value.likes++ : note.value.likes-- }
}

const handleSendComment = async () => {
  if (!userStore.isLoggedIn) return userStore.openLogin()
  if (!newComment.value.trim()) return ElMessage.warning('评论内容不能为空')
  
  submitting.value = true
  try {
    // 1. 发送给后端
    await addComment(noteId, { content: newComment.value })
    ElMessage.success('评论已发送')
    
    // 2. 清空输入框，完事！
    // 🚀 删除那一大堆手动 unshift 的代码！
    // 接下来就坐等 WebSocket 把刚刚发的评论推回给你，自动渲染！
    newComment.value = ''
    
  } catch (error) {
    console.error(error)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  fetchDetailAndComments()
  initWebSocket() 
})

onUnmounted(() => { if (ws) { ws.close() } })
</script>

<style scoped>
.note-detail-container { max-width: 850px; margin: 0 auto; padding-bottom: 60px; }
.page-header { margin-bottom: 20px; }
.text-gray { color: #606266; }
.mb-20 { margin-bottom: 20px; }
.mt-30 { margin-top: 30px; }
.mr-3 { margin-right: 12px; }
.ml-15 { margin-left: 15px; } 
.flex-1 { flex: 1; }
.article-card { border-radius: 12px; padding: 10px 20px; }
.title-wrapper { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; margin-bottom: 24px; }
.note-title { font-size: 28px; color: #1f2937; margin: 0; line-height: 1.4; flex: 1; padding-right: 20px; }
.author-info { display: flex; align-items: center; gap: 16px; margin-bottom: 10px; }
.info-text .name { font-weight: 600; color: #303133; font-size: 16px; }
.info-text .meta-data { font-size: 13px; color: #909399; margin-top: 6px; display: flex; align-items: center; }
.note-body { font-size: 16px; line-height: 1.8; color: #374151; min-height: 150px; white-space: pre-wrap; }
.actions { display: flex; justify-content: center; }
.like-btn { padding: 12px 30px; font-size: 16px; transition: all 0.3s; }
.is-active { fill: #ffffff; }
.comments-card { border-radius: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.comment-title { font-size: 18px; font-weight: 600; color: #1f2937; }
.comment-input-box { display: flex; align-items: flex-start; }
.comment-list { display: flex; flex-direction: column; }
.comment-item { display: flex; padding: 20px 0; border-bottom: 1px solid #f3f4f6; }
.comment-content { flex: 1; }
.comment-user { font-weight: 500; color: #4b5563; margin-bottom: 6px; font-size: 14px; }
.comment-text { color: #1f2937; line-height: 1.6; font-size: 15px; margin-bottom: 8px; }
.comment-time { font-size: 12px; color: #9ca3af; display: flex; align-items: center;}
</style>