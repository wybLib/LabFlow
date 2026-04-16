<template>
  <div class="profile-container">
    <el-page-header @back="$router.push('/')" title="返回上一页" class="page-header">
      <template #content><span class="text-large font-600"> 个人中心 </span></template>
    </el-page-header>

    <el-card class="user-card mt-20" shadow="never">
      <div class="user-info-wrap" v-loading="loadingProfile">
        <div class="avatar-box" @click="openEditDialog">
          <el-avatar :size="90" :src="userInfo.avatar" />
          <div class="edit-overlay"><el-icon><Camera /></el-icon> 修改</div>
        </div>
        <div class="user-desc">
          <h2 class="username">{{ userInfo.name || '未命名极客' }}</h2>
          <p class="bio">{{ userInfo.bio || '这个人很懒，什么都没写...' }}</p>
        </div>
        <div class="action-btn">
          <el-button plain round @click="openEditDialog">编辑资料</el-button>
          <el-button plain round type="warning" @click="openPwdDialog">修改密码</el-button>
        </div>
      </div>
    </el-card>

    <el-card class="notes-card mt-20" shadow="never">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        
        <el-tab-pane label="我发布的" name="published">
          <div v-loading="loadingList">
            <el-empty v-if="publishedNotes.length === 0" description="还没发布过笔记呢" />
            <div v-for="note in publishedNotes" :key="note.id" class="rich-note-item" @click="$router.push(`/note/${note.id}`)">
              <div class="item-main">
                <div class="title">{{ note.title }}</div>
                <div class="summary">{{ note.summary || '暂无摘要...' }}</div>
                <div class="meta">
                  <span>{{ note.createTime?.replace('T', ' ') }}</span>
                  <el-divider direction="vertical" />
                  <span>👀 {{ note.views || 0 }} &nbsp; 👍 {{ note.likes || 0 }}</span>
                </div>
              </div>
              <div class="item-cover" v-if="note.cover">
                <img :src="note.cover" alt="封面" />
              </div>
            </div>
            
            <div class="pagination-wrap" v-if="publishPage.total > 0">
              <el-pagination 
                background 
                layout="prev, pager, next, total" 
                :total="publishPage.total"
                :page-size="publishPage.size"
                v-model:current-page="publishPage.page"
                @current-change="fetchPublishedNotes" 
              />
            </div>
          </div>
        </el-tab-pane>
        
        <el-tab-pane label="我的点赞" name="liked">
          <div v-loading="loadingList">
             <el-empty v-if="likedNotes.length === 0" description="暂无点赞记录" />
            <div v-for="note in likedNotes" :key="note.id" class="rich-note-item" @click="$router.push(`/note/${note.id}`)">
              <div class="item-main">
                <div class="title">{{ note.title }}</div>
                <div class="summary">{{ note.summary || '暂无摘要...' }}</div>
                <div class="meta">
                  <el-avatar :size="18" :src="note.avatar" style="margin-right: 6px;" />
                  <span>{{ note.author || '匿名' }}</span>
                  <el-divider direction="vertical" />
                  <span>{{ note.createTime?.replace('T', ' ') }}</span>
                  <el-divider direction="vertical" />
                  <span>👍 {{ note.likes || 0 }}</span>
                </div>
              </div>
              <div class="item-cover" v-if="note.cover">
                <img :src="note.cover" alt="封面" />
              </div>
            </div>
            
            <div class="pagination-wrap" v-if="likedPage.total > 0">
              <el-pagination 
                background 
                layout="prev, pager, next, total" 
                :total="likedPage.total"
                :page-size="likedPage.size"
                v-model:current-page="likedPage.page"
                @current-change="fetchLikedNotes" 
              />
            </div>
          </div>
        </el-tab-pane>
        
      </el-tabs>
    </el-card>

    <el-dialog v-model="dialogVisible" title="编辑个人资料" width="400px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="更换头像">
          <el-upload
            v-if="!editForm.avatar"
            class="avatar-uploader"
            :show-file-list="false"
            :http-request="customUploadAvatar"
          >
            <el-icon class="avatar-uploader-icon" :class="{'is-loading': uploadingAvatar}">
              <Loading v-if="uploadingAvatar" />
              <Plus v-else />
            </el-icon>
          </el-upload>
          
          <div v-else class="image-preview-box" style="border-radius: 50%;">
            <img :src="editForm.avatar" style="width: 100%; height: 100%; object-fit: cover;" />
            <div class="image-actions" @click="editForm.avatar = ''">
              <el-icon><Delete /></el-icon>
            </div>
          </div>
          <div class="upload-tip">点击图片更换头像</div>
        </el-form-item>

        <el-form-item label="昵称">
          <el-input v-model="editForm.name" placeholder="请输入昵称" maxlength="20" show-word-limit />
        </el-form-item>
        <el-form-item label="个人简介">
          <el-input v-model="editForm.bio" type="textarea" :rows="3" placeholder="介绍一下自己吧" maxlength="100" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSaveProfile" :loading="saving">保存修改</el-button>
        </span>
      </template>
    </el-dialog>
    <el-dialog v-model="pwdDialogVisible" title="修改密码" width="400px" @close="resetPwdForm">
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="90px">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入原密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="5到16位非空字符" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="pwdDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="submitPwdForm" :loading="savingPwd">确认修改</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Camera, Plus, Loading, Delete } from '@element-plus/icons-vue'
import { getUserProfile, updateUserProfile, getMyPublishedNotes, getMyLikedNotes, updatePasswordAPI } from '../../api/user'
import { uploadImage } from '../../api/common' 
import { useUserStore } from '../../store/modules/user'

const router = useRouter()
const userStore = useUserStore() 
const activeTab = ref('published')
const dialogVisible = ref(false)

const loadingProfile = ref(false)
const loadingList = ref(false)
const saving = ref(false)
const uploadingAvatar = ref(false)

const userInfo = ref({
  name: userStore.userInfo?.name || '',
  avatar: userStore.userInfo?.avatar || '',
  bio: userStore.userInfo?.bio || ''
})
const editForm = reactive({ name: '', bio: '', avatar: '' })

const publishedNotes = ref([])
const likedNotes = ref([])

// 🚀 核心新增：独立的分页状态管理（每页展示 5 条，方便你测试分页效果）
const publishPage = reactive({ page: 1, size: 5, total: 0 })
const likedPage = reactive({ page: 1, size: 5, total: 0 })

// 🚀 新增：修改密码相关的状态和逻辑
const pwdDialogVisible = ref(false)
const savingPwd = ref(false)
const pwdFormRef = ref(null)

const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})
// 自定义校验：确认密码必须和新密码一致
const validateConfirmPwd = (rule, value, callback) => {
  if (value !== pwdForm.newPassword) {
    callback(new Error('两次输入的新密码不一致!'))
  } else {
    callback()
  }
}

// 自定义校验：新密码不能和旧密码一样
const validateNewPwd = (rule, value, callback) => {
  if (value === pwdForm.oldPassword) {
    callback(new Error('新密码不能与原密码相同!'))
  } else {
    callback()
  }
}

// 极其严格的表单校验规则
const pwdRules = reactive({
  oldPassword: [
    { required: true, message: '请输入原密码', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    // 这里的正则和你 Java 里的 @Pattern 保持绝对一致！
    { pattern: /^\S{5,16}$/, message: '密码必须是5到16位非空字符', trigger: 'blur' },
    { validator: validateNewPwd, trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirmPwd, trigger: 'blur' }
  ]
})

// 打开密码弹窗
const openPwdDialog = () => {
  pwdDialogVisible.value = true
}

// 关闭弹窗时清空表单，防止下次打开还有数据
const resetPwdForm = () => {
  if (pwdFormRef.value) {
    pwdFormRef.value.resetFields()
  }
}

// 提交修改密码
const submitPwdForm = async () => {
  if (!pwdFormRef.value) return
  
  // 1. 触发前端表单规则校验
  await pwdFormRef.value.validate(async (valid) => {
    if (valid) {
      savingPwd.value = true
      try {
        // 2. 发起真实后端请求
        await updatePasswordAPI({
          oldPassword: pwdForm.oldPassword,
          newPassword: pwdForm.newPassword
        })
        
        ElMessage.success('密码修改成功，请重新登录！')
        pwdDialogVisible.value = false
        
        // 3. 🚀 极其关键：密码修改成功后，后端 Token 已经失效了，前端也必须强制用户下线！
        userStore.logout()
        setTimeout(() => {
          window.location.href = '/' // 强刷回首页触发未登录状态
        }, 1000)
        
      } catch (error) {
        // 如果后端返回错误（比如原密码错误），Axios 的响应拦截器通常会自动 Toast，所以这里无需额外写 ElMessage
        console.error('密码修改失败', error)
      } finally {
        savingPwd.value = false
      }
    }
  })
}

// 获取用户基本信息
const fetchUserProfile = async () => {
  loadingProfile.value = true
  try {
    const userRes = await getUserProfile()
    userInfo.value = userRes
    userStore.updateUserInfo(userRes) 
  } catch (error) {
    console.error('获取用户信息失败', error)
  } finally {
    loadingProfile.value = false
  }
}

// 🚀 核心新增：专门获取【我发布的】列表数据（带分页）
const fetchPublishedNotes = async () => {
  loadingList.value = true
  try {
    const res = await getMyPublishedNotes({ page: publishPage.page, size: publishPage.size })
    // 兼容多种后端返回的数据结构格式
    publishedNotes.value = res.result || res.list || res.records || res || []
    publishPage.total = res.total || 0 
  } catch (error) {
    console.error('获取发布的笔记失败', error)
  } finally {
    loadingList.value = false
  }
}

// 🚀 核心新增：专门获取【我的点赞】列表数据（带分页）
const fetchLikedNotes = async () => {
  loadingList.value = true
  try {
    const res = await getMyLikedNotes({ page: likedPage.page, size: likedPage.size })
    likedNotes.value = res.result || res.list || res.records || res || []
    likedPage.total = res.total || 0 
  } catch (error) {
    console.error('获取点赞的笔记失败', error)
  } finally {
    loadingList.value = false
  }
}

// 切换 Tab 时，只有在没数据时才去拉取第一页
const handleTabChange = (tabName) => {
  if (tabName === 'published' && publishedNotes.value.length === 0) {
    fetchPublishedNotes()
  } else if (tabName === 'liked' && likedNotes.value.length === 0) {
    fetchLikedNotes()
  }
}

// 初始化加载
onMounted(() => {
  fetchUserProfile()
  fetchPublishedNotes() // 默认一进来只拉取“我发布的”第一页
})

// === 下方的弹窗与头像逻辑保持不变 ===
const openEditDialog = () => {
  editForm.name = userInfo.value.name
  editForm.bio = userInfo.value.bio
  editForm.avatar = userInfo.value.avatar 
  dialogVisible.value = true
}

const customUploadAvatar = async (options) => {
  uploadingAvatar.value = true
  try {
    const res = await uploadImage(options.file)
    editForm.avatar = res.url || res.data?.url || res
    ElMessage.success('头像上传成功')
  } catch (error) {
    ElMessage.error('图片上传失败，请检查网络或后端接口')
  } finally {
    uploadingAvatar.value = false
  }
}

const handleSaveProfile = async () => {
  if (!editForm.name.trim()) { return ElMessage.warning('昵称不能为空') }

  saving.value = true
  try {
    await updateUserProfile(editForm)
    
    userInfo.value.name = editForm.name
    userInfo.value.bio = editForm.bio
    userInfo.value.avatar = editForm.avatar
    
    userStore.updateUserInfo({
      name: editForm.name,
      bio: editForm.bio,
      avatar: editForm.avatar
    })
    
    dialogVisible.value = false
    ElMessage.success('资料修改成功')
  } catch (error) {
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.profile-container { max-width: 900px; margin: 0 auto; padding-bottom: 40px;}
.page-header { margin-bottom: 10px; }
.mt-20 { margin-top: 20px; }
.user-card { border-radius: 12px; }
.user-info-wrap { display: flex; align-items: center; gap: 30px; }
.avatar-box { position: relative; cursor: pointer; border-radius: 50%; overflow: hidden; width: 90px; height: 90px; }
.edit-overlay { position: absolute; bottom: 0; left: 0; right: 0; background: rgba(0,0,0,0.5); color: white; font-size: 12px; text-align: center; padding: 4px 0; transform: translateY(100%); transition: all 0.3s; }
.avatar-box:hover .edit-overlay { transform: translateY(0); }
.user-desc { flex: 1; }
.username { margin: 0 0 10px 0; font-size: 24px; color: #303133; }
.bio { color: #606266; font-size: 14px; margin-bottom: 12px; }
.action-btn { align-self: flex-start; margin-top: 10px; }
.notes-card { border-radius: 12px; min-height: 400px; }

/* 列表样式 */
.rich-note-item { 
  display: flex; justify-content: space-between; padding: 18px 10px; 
  border-bottom: 1px solid #ebeef5; cursor: pointer; transition: all 0.3s; 
  border-radius: 8px;
}
.rich-note-item:hover { background-color: #f9fafc; transform: translateX(5px); }
.item-main { flex: 1; padding-right: 20px; display: flex; flex-direction: column; justify-content: center; }
.item-main .title { font-size: 16px; font-weight: 600; color: #303133; margin-bottom: 8px; }
.item-main .summary { font-size: 14px; color: #606266; margin-bottom: 12px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; line-height: 1.5; }
.item-main .meta { display: flex; align-items: center; font-size: 13px; color: #909399; }
.item-cover { flex-shrink: 0; }
.item-cover img { width: 110px; height: 80px; border-radius: 6px; object-fit: cover; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }

/* 🚀 新增分页栏样式 */
.pagination-wrap { display: flex; justify-content: center; margin-top: 30px; padding-bottom: 20px; }

/* 头像上传样式 */
.avatar-uploader :deep(.el-upload) { border: 1px dashed #d9d9d9; border-radius: 50%; cursor: pointer; position: relative; overflow: hidden; width: 60px; height: 60px; background-color: #f8f9fa; }
.avatar-uploader :deep(.el-upload:hover) { border-color: #409EFF; }
.avatar-uploader-icon { font-size: 20px; color: #8c939d; width: 60px; height: 60px; line-height: 60px; text-align: center; }
.upload-tip { font-size: 12px; color: #909399; margin-top: 8px; line-height: 1; }
.is-loading { animation: rotating 2s linear infinite; }
@keyframes rotating { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
</style>