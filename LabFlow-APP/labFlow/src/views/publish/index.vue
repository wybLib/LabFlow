<template>
  <div class="publish-container" v-loading="pageLoading">
    <!-- <el-page-header @back="$router.push('/')" title="返回" class="page-header"> -->
    <el-page-header @back="isEdit ? $router.back() : $router.push('/')" title="返回" class="page-header">
      <template #content><span class="text-large font-600"> {{ isEdit ? '修改你的笔记' : '撰写新笔记' }} </span></template>
    </el-page-header>

    <el-card class="publish-card mt-20" shadow="never">
      <template #header>
        <div class="card-header">
          <span style="font-size: 16px; font-weight: bold; color: #303133;">
            <el-icon><EditPen style="vertical-align: middle;"/></el-icon> {{ isEdit ? '重新编辑内容' : '请填写实验记录' }}
          </span>
          <el-button type="primary" @click="submitNote" :loading="loading">{{ isEdit ? '保存修改' : '发布笔记' }}</el-button>
        </div>
      </template>

      <el-form :model="postForm" label-position="top">
        <el-form-item label="笔记标题">
          <el-input v-model="postForm.title" placeholder="填写标题..." maxlength="50" show-word-limit size="large" />
        </el-form-item>

        <el-form-item label="笔记摘要">
          <el-input v-model="postForm.summary" type="textarea" :rows="2" placeholder="用简短的几句话概括这篇笔记的核心内容..." maxlength="150" show-word-limit />
        </el-form-item>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="所属话题">
              <el-select v-model="postForm.topicId" placeholder="选择分类" style="width: 100%" clearable filterable>
                <el-option v-for="item in topicList" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="封面图片 (选填)">
              <div class="upload-wrapper">
                <el-upload
                  v-if="!postForm.cover"
                  class="avatar-uploader"
                  :show-file-list="false"
                  :http-request="customUploadRequest"
                >
                  <el-icon class="avatar-uploader-icon" :class="{'is-loading': uploadingCover}">
                    <Loading v-if="uploadingCover"/><Plus v-else/>
                  </el-icon>
                </el-upload>
                
                <div v-else class="image-preview-box">
                  <img :src="postForm.cover" class="cover-preview" />
                  <el-button
                    type="danger"
                    icon="Close"
                    circle
                    size="small"
                    class="absolute-delete-btn"
                    @click.stop="postForm.cover = ''"
                  />
                </div>
              </div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="正文内容">
          <el-input v-model="postForm.content" type="textarea" :rows="15" placeholder="记录你的实验过程、代码细节或心得体会..." />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted, computed, watch } from 'vue' // 必须引入 watch 和 computed
import { useRouter, useRoute } from 'vue-router' 
import { ElMessage } from 'element-plus'
import { EditPen, Plus, Loading, Close } from '@element-plus/icons-vue' // 引入 Close
import { publishNote, updateNote, getNoteDetail } from '../../api/note' 
import { uploadImage } from '../../api/common'
import { getAllTopics } from '../../api/topic'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const pageLoading = ref(false) 
const uploadingCover = ref(false)

const noteId = computed(() => route.query.id)
const isEdit = computed(() => !!noteId.value)

const postForm = reactive({ title: '', summary: '', topicId: '', cover: '', content: '' })
const topicList = ref([])

const fetchTopics = async () => {
  try {
    const res = await getAllTopics()
    topicList.value = res.data || res
  } catch (error) { console.error('获取话题失败', error) }
}

// 🚀 核心修复 1：把 fetchOldNote 的定义必须放在 watch 的前面！
const fetchOldNote = async () => {
  if (!isEdit.value) return
  pageLoading.value = true
  try {
    // const res = await getNoteDetail(noteId.value)
    const res = await getNoteDetail(noteId.value, { isEdit: true })
    const data = res.data || res
    
    postForm.title = data.title || ''
    postForm.summary = data.summary || ''
    postForm.topicId = data.topicId || '' 
    postForm.cover = data.cover || ''
    postForm.content = data.content || ''
  } catch (error) {
    ElMessage.error('获取笔记数据失败，可能是文章不存在')
  } finally {
    pageLoading.value = false
  }
}

// 🚀 核心修复 2：watch 放在这，完美监听且不会报错
watch(() => route.query.id, (newId) => {
  if (newId) {
    fetchOldNote()
  } else {
    // 变成发布模式时，清空表单
    postForm.title = ''
    postForm.summary = ''
    postForm.topicId = ''
    postForm.cover = ''
    postForm.content = ''
  }
}, { immediate: true })

onMounted(() => {
  fetchTopics()
})

const customUploadRequest = async (options) => {
  uploadingCover.value = true
  try {
    const res = await uploadImage(options.file)
    postForm.cover = res.url || res.data?.url || res
    ElMessage.success('封面上传成功')
  } catch (error) { ElMessage.error('图片上传失败') } 
  finally { uploadingCover.value = false }
}

const submitNote = async () => {
  if (!postForm.title) return ElMessage.warning('标题不能为空！')
  if (!postForm.content) return ElMessage.warning('正文不能为空！')
  if (!postForm.topicId) return ElMessage.warning('请选择所属话题！')
  
  loading.value = true
  try {
    if (isEdit.value) {
      await updateNote(noteId.value, postForm)
      ElMessage.success('笔记修改成功！')
      // 🚀 核心修复：不要再 push 压栈了，直接退回详情页，Vue会自动重新拉取最新详情！
      router.back() 
    } else {
      await publishNote(postForm)
      ElMessage.success('笔记发布成功！')
      router.push('/')
    }
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.publish-container { max-width: 900px; margin: 0 auto; padding-bottom: 40px; }
.page-header { margin-bottom: 10px; }
.mt-20 { margin-top: 20px; }
.publish-card { border-radius: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }

/* 🚀 终极防干扰图片和按钮样式 */
.upload-wrapper { display: inline-block; }
.image-preview-box { position: relative; width: 120px; height: 120px; display: inline-block; }
.cover-preview { width: 120px; height: 120px; border-radius: 6px; object-fit: cover; border: 1px solid #dcdfe6; display: block; }
.absolute-delete-btn { position: absolute !important; top: -10px !important; right: -10px !important; z-index: 99 !important; }

.avatar-uploader :deep(.el-upload) { border: 1px dashed #d9d9d9; border-radius: 6px; cursor: pointer; position: relative; overflow: hidden; width: 120px; height: 120px; background: #fafafa; }
.avatar-uploader :deep(.el-upload:hover) { border-color: #409EFF; }
.avatar-uploader-icon { font-size: 28px; color: #8c939d; width: 120px; height: 120px; line-height: 120px; text-align: center; }
.is-loading { animation: rotating 2s linear infinite; }
@keyframes rotating { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
</style>