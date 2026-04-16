<template>
  <div class="home-container">
    <div class="topic-header-bar">
      <el-tabs v-model="activeTab" class="custom-tabs">
        <el-tab-pane label="🌐 首页(全部)" name="all" />
        <el-tab-pane label="🔥 推荐(Top50)" name="recommend" />
        
        <el-tab-pane 
          v-for="topic in myTopics" 
          :key="topic.id" 
          :label="topic.name" 
          :name="String(topic.id)" 
        />
      </el-tabs>

      <div class="manage-action" v-if="userStore.isLoggedIn">
        <el-button type="primary" link @click="openManageModal">
          <el-icon><Setting /></el-icon> 管理话题
        </el-button>
      </div>
    </div>

    <el-row :gutter="24">
      <el-col :span="18">
        <el-skeleton :rows="5" animated v-if="loading && notesList.length === 0" />
        
        <div v-else>
          <div class="grid-container">
            <div v-for="note in notesList" :key="note.id" class="note-card" @click="goToDetail(note.id)">
              <div class="cover-wrapper">
                <img v-if="note.cover" :src="note.cover" class="note-cover" />
                <div v-else class="default-cover" :class="getGradient(note.id)">
                  <span>{{ note.title?.substring(0, 1) || '📝' }}</span>
                </div>
              </div>
              
              <div class="card-content">
                <h3 class="note-title" :title="note.title">{{ note.title }}</h3>
                <p class="note-summary">{{ note.summary }}</p>

                <div class="card-bottom">
                  <div class="author">
                    <el-avatar :size="24" :src="note.avatar" class="author-avatar" />
                    <span class="name">{{ note.author }}</span>
                  </div>
                  
                  <div class="stats">
                    <span class="stat-item hover-effect" @click.stop="handleLike(note)" :style="{ color: note.isLiked ? '#3b82f6' : '' }">
                      <el-icon><Star :class="{ 'is-active': note.isLiked }" /></el-icon> 
                      {{ formatNumber(note.likes) }}
                    </span>
                    <span class="stat-item hover-effect">
                      <el-icon><ChatDotRound /></el-icon> {{ formatNumber(note.commentCount || note.comments) }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
          
          <div class="pagination-container" v-if="total > 0">
            <el-pagination
              v-model:current-page="currentPage"
              :page-size="pageSize"
              :total="total"
              background
              layout="prev, pager, next"
              @current-change="handlePageChange"
            />
          </div>
        </div>
        
        <div v-if="notesList.length === 0 && !loading" style="text-align: center; padding: 40px; color: #999;">
          {{ activeTab === 'recommend' ? '暂无推荐数据' : '暂无相关笔记数据' }}
        </div>
        
        <div v-if="loading && notesList.length > 0" style="text-align: center; padding: 20px;">
          <el-icon class="is-loading"><Loading /></el-icon> 加载中...
        </div>
      </el-col>

      <el-col :span="6">
        <el-card class="api-panel" shadow="hover">
          <template #header><div class="panel-header"><el-icon color="#409eff"><Promotion /></el-icon> <span>后端架构亮点</span></div></template>
          <div class="api-item"><el-tag type="danger" size="small" effect="dark">高可用</el-tag> <span class="route">Redis Sentinel 部署</span></div>
          <div class="api-item"><el-tag type="success" size="small" effect="dark">防击穿</el-tag> <span class="route">Redisson DCL 双重检查</span></div>
          <div class="api-item"><el-tag type="warning" size="small" effect="dark">异步写</el-tag> <span class="route">RabbitMQ 批量落库削峰</span></div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog 
      v-model="showTopicModal" 
      :title="isFirstLogin ? '✨ 定制你的专属首页' : '✏️ 管理我的话题'" 
      width="500px" 
      :close-on-click-modal="false"
      :show-close="!isFirstLogin" 
      align-center
    >
      <div class="topic-select-container">
        <p class="subtitle mb-20 text-gray">
          {{ isFirstLogin ? '选择你感兴趣的话题，获取更精准的推荐内容' : '选择你想在首页看到的话题' }}
        </p>
        
        <el-checkbox-group v-model="selectedTopicIds" class="checkbox-group">
          <el-checkbox-button 
            v-for="topic in allTopics" 
            :key="topic.id" 
            :label="topic.id"
            class="topic-checkbox"
          >
            {{ topic.name }}
          </el-checkbox-button>
        </el-checkbox-group>
        <p class="select-tip">已选择: <span class="highlight">{{ selectedTopicIds.length }}</span> 个话题</p>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button v-if="isFirstLogin" @click="handleSkipTopics" class="flex-1">跳过</el-button>
          <el-button v-else @click="showTopicModal = false" class="flex-1">取消</el-button>
          
          <el-button type="primary" @click="handleSaveTopics" :loading="savingTopics" class="flex-1">
            {{ isFirstLogin ? '开启探索' : '保存修改' }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Star, ChatDotRound, Promotion, Setting, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../../store/modules/user'
import { getNoteList, likeNote, getRecommendNotes } from '../../api/note' 
import { getAllTopics, getMySelectedTopics, saveMySelectedTopics } from '../../api/topic'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

// 响应式数据
const activeTab = ref('all')
const notesList = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = 12 // 根据你的后端 NoteQuery 默认 12
const total = ref(0) // 新增：记录后端返回的总条数

// 话题相关状态
const myTopics = ref([])
const allTopics = ref([])
const showTopicModal = ref(false)
const selectedTopicIds = ref([])
const savingTopics = ref(false)
const isFirstLogin = ref(false)

const formatNumber = (num) => num >= 1000 ? (num / 1000).toFixed(1) + 'k' : num
const getGradient = (id) => ['gradient-1', 'gradient-2', 'gradient-3'][(id || 0) % 3]

// ========== 监听 activeTab 变化 ==========
// ========== 监听 activeTab 变化 ==========
watch(activeTab, (newVal, oldVal) => {
  console.log('activeTab 变化:', oldVal, '->', newVal)
  
  // ⚡【核心逻辑】：如果切换 Tab 时，发现地址栏里还有 keyword
  if (route.query.keyword) {
    // 直接修改 URL，清空所有查询参数。这会自动触发另一个监听器去拉取数据
    router.push({ path: route.path, query: {} })
  } else {
    // 如果地址栏本来就没有 keyword，那就正常重置状态并加载数据
    currentPage.value = 1
    notesList.value = []
    // 🗑️ 【删除点】：不再需要 noMoreData.value = false 了，因为分页器只看 total
    
    loadData(true)
  }
})
// 监听路由关键词变化
watch(
  () => route.query.keyword,
  (newKeyword) => {
    currentPage.value = 1
    loadData(true)
  }
)

// 监听用户登录状态变化
watch(
  () => userStore.isLoggedIn,
  (newStatus) => {
    if (newStatus) {
      initTopics()
    } else {
      myTopics.value = []
      activeTab.value = 'all'
    }
  }
)



const initTopics = async () => {
  if (!userStore.isLoggedIn) {
    loadData(true)
    return
  }
  try {
    if (userStore.userInfo?.isTopicInitialized === 0) {
      isFirstLogin.value = true
      showTopicModal.value = true
      allTopics.value = await getAllTopics()
    } else {
      myTopics.value = await getMySelectedTopics()
      loadData(true)
    }
  } catch (error) {
    console.error('初始化话题失败', error)
    loadData(true)
  }
}

const openManageModal = async () => {
  isFirstLogin.value = false
  showTopicModal.value = true
  try {
    const [allRes, myRes] = await Promise.all([
      getAllTopics(),
      getMySelectedTopics()
    ])
    allTopics.value = allRes || []
    selectedTopicIds.value = (myRes || []).map(topic => topic.id)
  } catch (error) {
    ElMessage.error('获取话题失败')
  }
}

const handleSkipTopics = async () => {
  selectedTopicIds.value = []
  await handleSaveTopics()
}

const handleSaveTopics = async () => {
  savingTopics.value = true
  try {
    await saveMySelectedTopics(selectedTopicIds.value)
    if (userStore.userInfo) {
      userStore.userInfo.isTopicInitialized = 1
      localStorage.setItem('userInfo', JSON.stringify(userStore.userInfo))
    }
    ElMessage.success(isFirstLogin.value ? '设置成功，开始冲浪！' : '话题修改成功！')
    showTopicModal.value = false
    myTopics.value = await getMySelectedTopics()
    activeTab.value = 'all'
  } catch (error) {
    ElMessage.error('保存失败，请重试')
  } finally {
    savingTopics.value = false
  }
}

// 核心数据加载 (替换逻辑)
const loadData = async (isReset = false) => {
  if (isReset) {
    currentPage.value = 1
    notesList.value = []
    total.value = 0
  }
  
  loading.value = true

  try {
    let res = null
    
    // 💡 1. 统一提取公共参数（页码、每页条数、搜索关键字）
    const params = {
      page: currentPage.value,
      size: pageSize
    }
    const keyword = route.query.keyword
    if (keyword && keyword.trim()) {
      params.keyword = keyword.trim()
    }
    
    // 💡 2. 分支处理：推荐页 vs 话题/首页
    if (activeTab.value === 'recommend') {
      // 🚀 核心修改：把 params 传给推荐接口！
      res = await getRecommendNotes(params) 
    } else {
      // 处理其他话题的 topicId
      if (activeTab.value !== 'all') {
        const topicIdNum = Number(activeTab.value)
        if (!isNaN(topicIdNum) && topicIdNum > 0) {
          params.topicId = topicIdNum
        }
      }
      res = await getNoteList(params)
    }
    
    // 💡 3. 统一解析后端返回的 PageResult
    // 因为后端现在推荐页也返回 { total: xx, result: [...] } 格式了，所以解析逻辑完全通用！
    const list = res.result || res.list || res.records || res || []
    const totalCount = res.total || 0
    
    // 覆盖数组，更新总数
    notesList.value = list
    total.value = totalCount
    
  } catch (error) {
    console.error('数据加载失败', error)
    ElMessage.error('数据加载失败')
  } finally {
    loading.value = false
  }
}

// 点击翻页触发的方法
const handlePageChange = (val) => {
  currentPage.value = val
  loadData(false)
  // 翻页后平滑滚动回顶部，体验更好
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const goToDetail = (id) => router.push(`/note/${id}`)

const handleLike = async (note) => {
  if (!userStore.isLoggedIn) {
    userStore.openLogin()
    return
  }

  const isCurrentlyLiked = note.isLiked
  note.isLiked = !note.isLiked
  note.isLiked ? note.likes++ : note.likes--

  try {
    await likeNote(note.id)
  } catch (error) {
    note.isLiked = isCurrentlyLiked
    note.isLiked ? note.likes++ : note.likes--
    ElMessage.error('点赞失败')
  }
}

onMounted(() => {
  initTopics()
})
</script>

<style scoped>
/* 原有样式保持不变 */
.topic-tabs { margin-bottom: 24px; }
.custom-tabs :deep(.el-tabs__item) { font-size: 16px; font-weight: 500; color: #64748b; }
.custom-tabs :deep(.el-tabs__item.is-active) { color: #3b82f6; font-size: 17px; font-weight: bold; }
.custom-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background-color: #e2e8f0; }

.topic-header-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.topic-header-bar .custom-tabs { margin-bottom: 0; flex: 1; }
.manage-action { padding-bottom: 5px; margin-left: 20px; }

.grid-container { display: grid; grid-template-columns: repeat(3, 1fr); gap: 24px; width: 100%; }
.note-card { display: flex; flex-direction: column; height: 360px; background: #ffffff; border-radius: 12px; cursor: pointer; box-shadow: 0 4px 12px rgba(0, 0, 0, 0.03); transition: all 0.3s ease; overflow: hidden; border: 1px solid #f1f5f9; }
.note-card:hover { transform: translateY(-5px); box-shadow: 0 16px 32px rgba(0, 0, 0, 0.08); border-color: #e2e8f0; }
.cover-wrapper { height: 150px; width: 100%; flex-shrink: 0; overflow: hidden; }
.note-cover { width: 100%; height: 100%; object-fit: cover; transition: transform 0.3s; }
.note-card:hover .note-cover { transform: scale(1.05); }
.default-cover { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: white; font-size: 48px; font-weight: bold; opacity: 0.9; }
.gradient-1 { background: linear-gradient(135deg, #a8edea 0%, #fed6e3 100%); }
.gradient-2 { background: linear-gradient(135deg, #e0c3fc 0%, #8ec5fc 100%); }
.gradient-3 { background: linear-gradient(135deg, #fbc2eb 0%, #a6c1ee 100%); }
.card-content { padding: 16px; display: flex; flex-direction: column; flex: 1; }
.note-title { font-size: 16px; color: #1e293b; margin: 0 0 10px 0; font-weight: 600; line-height: 1.4; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
.note-summary { font-size: 13px; color: #64748b; line-height: 1.6; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; margin: 0; }
.card-bottom { display: flex; justify-content: space-between; align-items: center; margin-top: auto; padding-top: 14px; }
.author { display: flex; align-items: center; font-size: 13px; color: #475569; font-weight: 500; }
.author-avatar { margin-right: 8px; border: 1px solid #f1f5f9; }
.name { display: inline-block; max-width: 80px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.stats { display: flex; gap: 14px; color: #94a3b8; font-size: 13px; font-weight: 500; }
.stat-item { display: flex; align-items: center; gap: 4px; transition: color 0.2s; }
.hover-effect:hover { color: #3b82f6; }
.is-active { fill: #3b82f6; }
.api-panel { border-radius: 12px; border: none; box-shadow: 0 4px 12px rgba(0,0,0,0.03); }
.panel-header { font-weight: 600; color: #1e293b; display: flex; align-items: center; gap: 8px; }
.api-item { padding: 12px 0; border-bottom: 1px solid #f1f5f9; display: flex; align-items: center; gap: 10px; }
.api-item:last-child { border-bottom: none; padding-bottom: 0; }
.route { font-family: 'Courier New', Courier, monospace; font-size: 13px; color: #475569; }

.topic-select-container { display: flex; flex-direction: column; align-items: center; padding: 10px 0; }
.checkbox-group { display: flex; flex-wrap: wrap; gap: 15px; justify-content: center; margin-bottom: 20px; }
.topic-checkbox :deep(.el-checkbox-button__inner) { border-radius: 20px !important; border: 1px solid #dcdfe6; padding: 10px 20px; box-shadow: none !important; background: #f8f9fa; }
.topic-checkbox.is-checked :deep(.el-checkbox-button__inner) { background-color: #ecf5ff; border-color: #a0cfff; color: #409EFF; }
.select-tip { font-size: 14px; color: #606266; margin-bottom: 10px; }
.select-tip .highlight { color: #409EFF; font-weight: bold; font-size: 16px; }
.dialog-footer { display: flex; gap: 15px; width: 100%; }
.flex-1 { flex: 1; height: 40px; font-size: 15px; }
.text-gray { color: #909399; font-size: 14px; text-align: center; }
.mb-20 { margin-bottom: 20px; }

/* ======== 新增：分页器居中美化 ======== */
.pagination-container {
  margin-top: 30px;
  display: flex;
  justify-content: center;
  padding: 20px 0;
}
/* 微调 Element Plus 分页器自带的颜色，更贴合社区风格 */
.pagination-container :deep(.el-pagination.is-background .el-pager li:not(.is-disabled).is-active) {
  background-color: #3b82f6;
}
.pagination-container :deep(.el-pagination.is-background .el-pager li) {
  border-radius: 6px;
}
</style>