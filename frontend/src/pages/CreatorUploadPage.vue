<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ApiError, describeError } from '../api/http'
import { uploadPost, uploadVideo } from '../api/upload'
import {
  POST_DESCRIPTION_MAX,
  SubmitKeyHolder,
  TITLE_MAX,
  VIDEO_DESCRIPTION_MAX,
  createPostDraft,
  createVideoDraft,
  draftSignature,
  formatBytes,
  progressPercent,
  validatePostDraft,
  validateVideoDraft,
} from '../api/uploadDraft'
import type { PostDraft, UploadFieldError, VideoDraft } from '../api/uploadDraft'
import { contentHref, spaceHref } from '../api/adapters/links'
import { requireLogin } from '../auth/loginPrompt'
import { sessionState, signOut } from '../auth/session'
import { showToast } from '../common/toast'
import { UPLOAD_CATEGORIES } from '../constants/categories'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'

/**
 * 投稿页（`/creator`，第 9 个入口）。
 *
 * <h2>只渲染本仓真有的字段</h2>
 * 对照 B 站投稿页，下列字段**本仓无域，刻意不渲染**：标签 / 创作声明 / 活动 /
 * 定时发布 / 加入合集 / 商业推广 / 存草稿 / AI 封面 / 一键填写 / 批量操作。
 * 同理，顶部只保留「视频投稿」「图文投稿」两个 tab —— 短剧 / 专栏 / UP 动画 /
 * 互动视频在本仓没有投稿域。
 *
 * <h2>页面级身份态</h2>
 * 整页内容都属于"我"（两条投稿端点都是 `@RequiresLogin`）⇒ 匿名渲染「登录后投稿」，
 * 按钮走 `requireLogin()`；登录成功后同一份草稿还在（表单用 `v-show` 保持挂载）。
 *
 * <h2>幂等键</h2>
 * 键在**按下提交**时取、失败重试复用、草稿一改就换（`SubmitKeyHolder`）——
 * 见 `architecture/frontend/前端.md` §八。
 */
type Kind = 'video' | 'post'

const kind = ref<Kind>('video')
const videoDraft = reactive<VideoDraft>(createVideoDraft())
const postDraft = reactive<PostDraft>(createPostDraft())

const errors = ref<UploadFieldError[]>([])
const pageError = ref('')
const submitting = ref(false)
const progress = ref(0)
const stage = ref<'idle' | 'uploading' | 'processing'>('idle')
const success = ref<{ id: number; href: string; kind: Kind } | null>(null)

const videoKeys = new SubmitKeyHolder()
const postKeys = new SubmitKeyHolder()

const videoCoverPreview = ref<string | null>(null)
const postCoverPreview = ref<string | null>(null)
const postImagePreviews = ref<string[]>([])

const videoInput = ref<HTMLInputElement | null>(null)
const videoCoverInput = ref<HTMLInputElement | null>(null)
const postCoverInput = ref<HTMLInputElement | null>(null)
const postImagesInput = ref<HTMLInputElement | null>(null)

const authenticated = computed(() => sessionState.status === 'authenticated')
const userId = computed(() => sessionState.user?.id ?? null)
const mySpaceHref = computed(() => spaceHref(userId.value))

const postImagesTotal = computed(() =>
  postDraft.images.reduce((sum, image) => sum + image.size, 0),
)

const progressText = computed(() => {
  if (stage.value === 'uploading') return `上传中 ${progress.value}%`
  if (stage.value === 'processing') return '上传完成，正在发布…'
  return ''
})

const submitLabel = computed(() => {
  if (!submitting.value) return '立即投稿'
  return kind.value === 'video' ? '视频发布中…' : '图文发布中…'
})

function errorOf(field: string): string | null {
  return errors.value.find((error) => error.field === field)?.message ?? null
}

function clearError(field: string): void {
  errors.value = errors.value.filter((error) => error.field !== field)
}

function login(): void {
  void requireLogin()
}

function switchKind(next: Kind): void {
  if (submitting.value || kind.value === next) return
  kind.value = next
  errors.value = []
  pageError.value = ''
  success.value = null
}

// ==================== 文件选择与预览 ====================

function pickVideo(): void {
  videoInput.value?.click()
}

function pickVideoCover(): void {
  videoCoverInput.value?.click()
}

function pickPostCover(): void {
  postCoverInput.value?.click()
}

function pickPostImages(): void {
  postImagesInput.value?.click()
}

function onVideoPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  videoDraft.video = input.files?.[0] ?? null
  clearError('video')
  input.value = ''
}

function onVideoCoverPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  videoDraft.cover = file
  replaceVideoCoverPreview(file)
  clearError('cover')
  input.value = ''
}

function onPostCoverPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  postDraft.cover = file
  replacePostCoverPreview(file)
  clearError('cover')
  input.value = ''
}

function onPostImagesPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (files.length) {
    postDraft.images.push(...files)
    syncPostImagePreviews()
    clearError('image')
  }
  input.value = ''
}

function removePostImage(index: number): void {
  postDraft.images.splice(index, 1)
  syncPostImagePreviews()
}

function removePostCover(): void {
  postDraft.cover = null
  replacePostCoverPreview(null)
  clearError('cover')
}

function replaceVideoCoverPreview(file: File | null): void {
  if (videoCoverPreview.value) URL.revokeObjectURL(videoCoverPreview.value)
  videoCoverPreview.value = file ? URL.createObjectURL(file) : null
}

function replacePostCoverPreview(file: File | null): void {
  if (postCoverPreview.value) URL.revokeObjectURL(postCoverPreview.value)
  postCoverPreview.value = file ? URL.createObjectURL(file) : null
}

function syncPostImagePreviews(): void {
  postImagePreviews.value.forEach((url) => URL.revokeObjectURL(url))
  postImagePreviews.value = postDraft.images.map((file) => URL.createObjectURL(file))
}

onBeforeUnmount(() => {
  if (videoCoverPreview.value) URL.revokeObjectURL(videoCoverPreview.value)
  if (postCoverPreview.value) URL.revokeObjectURL(postCoverPreview.value)
  postImagePreviews.value.forEach((url) => URL.revokeObjectURL(url))
})

// ==================== 提交 ====================

function resetVideoDraft(): void {
  Object.assign(videoDraft, createVideoDraft())
  replaceVideoCoverPreview(null)
}

function resetPostDraft(): void {
  Object.assign(postDraft, createPostDraft())
  replacePostCoverPreview(null)
  syncPostImagePreviews()
}

function handleUploadError(error: unknown): void {
  if (error instanceof ApiError) {
    if (error.isAuthError) {
      // token 失效：清本地 token，页面回到「登录后投稿」；草稿还在（v-show 保持挂载）
      signOut()
      showToast('登录已过期，请重新登录', 'error')
      return
    }
    const fieldErrors = error.fields
      .filter((field) => field.field && field.message)
      .map((field) => ({ field: field.field as string, message: field.message as string }))
    if (fieldErrors.length) {
      errors.value = fieldErrors
      return
    }
    if (error.code === 'UPLOAD_TIMEOUT' || error.code === 'UPLOAD_ABORTED') {
      pageError.value = error.message
      return
    }
    pageError.value = describeError(error)
    return
  }
  pageError.value = '投稿失败，请稍后重试'
}

async function submitVideo(): Promise<void> {
  if (submitting.value) return
  pageError.value = ''
  errors.value = validateVideoDraft(videoDraft)
  if (errors.value.length) return
  if (!(await requireLogin())) return

  submitting.value = true
  progress.value = 0
  stage.value = 'uploading'
  const key = videoKeys.keyFor(draftSignature(videoDraft))
  try {
    const id = await uploadVideo(videoDraft, key, (loaded, total) => {
      progress.value = progressPercent(loaded, total)
      if (progress.value >= 100) stage.value = 'processing'
    })
    videoKeys.reset()
    resetVideoDraft()
    success.value = { id, href: contentHref(id, false), kind: 'video' }
    showToast('投稿成功')
  } catch (error) {
    handleUploadError(error)
  } finally {
    submitting.value = false
    stage.value = 'idle'
    progress.value = 0
  }
}

async function submitPost(): Promise<void> {
  if (submitting.value) return
  pageError.value = ''
  errors.value = validatePostDraft(postDraft)
  if (errors.value.length) return
  if (!(await requireLogin())) return

  submitting.value = true
  progress.value = 0
  stage.value = 'uploading'
  const key = postKeys.keyFor(draftSignature(postDraft))
  try {
    const id = await uploadPost(postDraft, key, (loaded, total) => {
      progress.value = progressPercent(loaded, total)
      if (progress.value >= 100) stage.value = 'processing'
    })
    postKeys.reset()
    resetPostDraft()
    success.value = { id, href: contentHref(id, true), kind: 'post' }
    showToast('投稿成功')
  } catch (error) {
    handleUploadError(error)
  } finally {
    submitting.value = false
    stage.value = 'idle'
    progress.value = 0
  }
}
</script>

<template>
  <div class="upload-page">
    <div class="upload-tabs" role="tablist" aria-label="投稿类型">
      <button
        class="upload-tab"
        :class="{ 'is-active': kind === 'video' }"
        type="button"
        role="tab"
        :aria-selected="kind === 'video'"
        :disabled="submitting"
        @click="switchKind('video')"
      >
        视频投稿
      </button>
      <button
        class="upload-tab"
        :class="{ 'is-active': kind === 'post' }"
        type="button"
        role="tab"
        :aria-selected="kind === 'post'"
        :disabled="submitting"
        @click="switchKind('post')"
      >
        图文投稿
      </button>
    </div>

    <!-- 页面级身份态：整页都属于"我" -->
    <section v-if="!authenticated" class="upload-gate">
      <p v-if="sessionState.status === 'restoring'" class="upload-gate__restoring">正在恢复登录状态…</p>
      <EmptyState
        v-else
        icon="user"
        title="登录后才能投稿"
        description="投稿会以你的账号发布，并出现在你的空间和关注者的动态里。"
      >
        <button class="btn btn-blue" type="button" @click="login">立即登录</button>
      </EmptyState>
    </section>

    <div v-show="authenticated">
      <section v-if="success" class="upload-success">
        <span class="upload-success__icon"><AppIcon name="tv" :size="44" /></span>
        <h1 class="upload-success__title">投稿成功</h1>
        <p class="upload-success__note">
          稿件已发布（内容 id {{ success.id }}），空间页会立刻出现；关注者的动态由后端投递。
        </p>
        <div class="upload-success__actions">
          <a class="btn btn-blue" :href="success.href" target="_blank" rel="noopener">
            查看{{ success.kind === 'video' ? '视频' : '图文' }}
          </a>
          <a v-if="userId" class="btn btn-ghost" :href="mySpaceHref">去我的空间</a>
          <button class="btn btn-ghost" type="button" @click="success = null">再投一条</button>
        </div>
      </section>

      <template v-else>
        <h1 class="upload-title">{{ kind === 'video' ? '发布视频' : '发布图文' }}</h1>

        <!-- ==================== 视频投稿 ==================== -->
        <form v-if="kind === 'video'" class="upload-form" @submit.prevent="submitVideo">
          <section class="upload-area">
            <div v-if="!videoDraft.video" class="upload-empty">
              <button class="upload-empty__btn" type="button" :disabled="submitting" @click="pickVideo">
                <AppIcon name="upload" :size="22" />
                <span>上传视频</span>
              </button>
              <p class="upload-empty__hint">仅支持 MP4，单文件不超过 50MB</p>
            </div>
            <template v-else>
              <div class="upload-file">
                <span class="upload-file__icon"><AppIcon name="upload" :size="20" /></span>
                <div class="upload-file__text">
                  <p class="upload-file__name">{{ videoDraft.video.name }}</p>
                  <p class="upload-file__meta">{{ formatBytes(videoDraft.video.size) }}</p>
                </div>
                <button class="upload-file__change" type="button" :disabled="submitting" @click="pickVideo">
                  更换视频
                </button>
              </div>
              <div v-if="submitting" class="upload-progress">
                <div class="upload-progress__track">
                  <div class="upload-progress__bar" :style="{ width: `${progress}%` }"></div>
                </div>
                <span class="upload-progress__text">{{ progressText }}</span>
              </div>
            </template>
            <p v-if="errorOf('video')" class="field-error">{{ errorOf('video') }}</p>
            <input ref="videoInput" class="visually-hidden" type="file" accept="video/mp4,.mp4" @change="onVideoPicked" />
          </section>

          <section class="upload-section">
            <h2 class="upload-section__title">基本设置</h2>

            <div class="field">
              <span class="field__label"><i class="required">*</i>封面</span>
              <div class="cover-row">
                <button class="cover-box" type="button" :disabled="submitting" @click="pickVideoCover">
                  <img v-if="videoCoverPreview" :src="videoCoverPreview" alt="封面预览" />
                  <template v-else>
                    <AppIcon name="upload" :size="20" />
                    <span>添加封面</span>
                  </template>
                </button>
                <p class="cover-hint">建议 16:9；JPG / PNG，≤50MB</p>
              </div>
              <p v-if="errorOf('cover')" class="field-error">{{ errorOf('cover') }}</p>
              <input
                ref="videoCoverInput"
                class="visually-hidden"
                type="file"
                accept="image/png,image/jpeg,.png,.jpg"
                @change="onVideoCoverPicked"
              />
            </div>

            <div class="field">
              <label class="field__label" for="video-title"><i class="required">*</i>标题</label>
              <div class="input-wrap">
                <input
                  id="video-title"
                  v-model="videoDraft.title"
                  class="input"
                  type="text"
                  maxlength="50"
                  placeholder="请输入稿件标题"
                  @input="clearError('title')"
                />
                <span class="counter">{{ videoDraft.title.length }}/{{ TITLE_MAX }}</span>
              </div>
              <p v-if="errorOf('title')" class="field-error">{{ errorOf('title') }}</p>
            </div>

            <div class="field">
              <label class="field__label" for="video-category"><i class="required">*</i>分区</label>
              <select
                id="video-category"
                v-model.number="videoDraft.categoryId"
                class="input select"
                @change="clearError('categoryId')"
              >
                <option v-for="item in UPLOAD_CATEGORIES" :key="item.id" :value="item.id">
                  {{ item.label }}
                </option>
              </select>
              <p v-if="errorOf('categoryId')" class="field-error">{{ errorOf('categoryId') }}</p>
            </div>

            <div class="field">
              <label class="field__label" for="video-desc">简介</label>
              <div class="input-wrap">
                <textarea
                  id="video-desc"
                  v-model="videoDraft.description"
                  class="input textarea"
                  maxlength="1000"
                  placeholder="填写更全面的相关信息，让更多人能找到你的稿件"
                  @input="clearError('description')"
                ></textarea>
                <span class="counter">{{ videoDraft.description.length }}/{{ VIDEO_DESCRIPTION_MAX }}</span>
              </div>
              <p v-if="errorOf('description')" class="field-error">{{ errorOf('description') }}</p>
            </div>
          </section>

          <footer class="upload-submit">
            <p v-if="pageError" class="page-error">{{ pageError }}</p>
            <button class="btn btn-blue" type="submit" :disabled="submitting">{{ submitLabel }}</button>
          </footer>
        </form>

        <!-- ==================== 图文投稿 ==================== -->
        <form v-else class="upload-form" @submit.prevent="submitPost">
          <section class="upload-section">
            <h2 class="upload-section__title">图片</h2>
            <p class="section-hint">
              可选；JPG / PNG / JPEG，单张 ≤50MB。已选 {{ postDraft.images.length }} 张，合计
              {{ formatBytes(postImagesTotal) }}（九宫格预览只截取，详情页按原顺序展示）
            </p>
            <div class="image-grid">
              <div v-for="(url, index) in postImagePreviews" :key="index" class="image-tile">
                <img :src="url" alt="" />
                <button
                  class="image-tile__remove"
                  type="button"
                  :disabled="submitting"
                  :aria-label="`移除图片 ${index + 1}`"
                  @click="removePostImage(index)"
                >
                  ×
                </button>
              </div>
              <button class="image-tile image-tile--add" type="button" :disabled="submitting" @click="pickPostImages">
                <AppIcon name="upload" :size="20" />
                <span>添加图片</span>
              </button>
            </div>
            <p v-if="errorOf('image')" class="field-error">{{ errorOf('image') }}</p>
            <input
              ref="postImagesInput"
              class="visually-hidden"
              type="file"
              accept="image/png,image/jpeg,.png,.jpg,.jpeg"
              multiple
              @change="onPostImagesPicked"
            />
          </section>

          <section class="upload-section">
            <h2 class="upload-section__title">基本设置</h2>

            <div class="field">
              <span class="field__label">封面 <span class="optional">（可选）</span></span>
              <div class="cover-row">
                <button class="cover-box" type="button" :disabled="submitting" @click="pickPostCover">
                  <img v-if="postCoverPreview" :src="postCoverPreview" alt="封面预览" />
                  <template v-else>
                    <AppIcon name="upload" :size="20" />
                    <span>添加封面</span>
                  </template>
                </button>
                <div class="cover-hint">
                  <p>JPG / PNG，≤50MB</p>
                  <button v-if="postDraft.cover" class="link-btn" type="button" :disabled="submitting" @click="removePostCover">
                    移除封面
                  </button>
                </div>
              </div>
              <p v-if="errorOf('cover')" class="field-error">{{ errorOf('cover') }}</p>
              <input
                ref="postCoverInput"
                class="visually-hidden"
                type="file"
                accept="image/png,image/jpeg,.png,.jpg"
                @change="onPostCoverPicked"
              />
            </div>

            <div class="field">
              <label class="field__label" for="post-title"><i class="required">*</i>标题</label>
              <div class="input-wrap">
                <input
                  id="post-title"
                  v-model="postDraft.title"
                  class="input"
                  type="text"
                  maxlength="50"
                  placeholder="请输入标题"
                  @input="clearError('title')"
                />
                <span class="counter">{{ postDraft.title.length }}/{{ TITLE_MAX }}</span>
              </div>
              <p v-if="errorOf('title')" class="field-error">{{ errorOf('title') }}</p>
            </div>

            <div class="field">
              <label class="field__label" for="post-category"><i class="required">*</i>分区</label>
              <select
                id="post-category"
                v-model.number="postDraft.categoryId"
                class="input select"
                @change="clearError('categoryId')"
              >
                <option v-for="item in UPLOAD_CATEGORIES" :key="item.id" :value="item.id">
                  {{ item.label }}
                </option>
              </select>
              <p v-if="errorOf('categoryId')" class="field-error">{{ errorOf('categoryId') }}</p>
            </div>

            <div class="field">
              <label class="field__label" for="post-desc">简介</label>
              <div class="input-wrap">
                <textarea
                  id="post-desc"
                  v-model="postDraft.description"
                  class="input textarea textarea--tall"
                  maxlength="10000"
                  placeholder="填写正文或简介；纯文字图文也可以直接发布"
                  @input="clearError('description')"
                ></textarea>
                <span class="counter">{{ postDraft.description.length }}/{{ POST_DESCRIPTION_MAX }}</span>
              </div>
              <p v-if="errorOf('description')" class="field-error">{{ errorOf('description') }}</p>
            </div>
          </section>

          <footer class="upload-submit">
            <p v-if="pageError" class="page-error">{{ pageError }}</p>
            <button class="btn btn-blue" type="submit" :disabled="submitting">{{ submitLabel }}</button>
          </footer>
        </form>
      </template>
    </div>
  </div>
</template>

<style scoped>
.upload-page {
  min-height: 420px;
}

.upload-tabs {
  display: flex;
  gap: 26px;
  border-bottom: 1px solid var(--line-2);
}

.upload-tab {
  position: relative;
  padding: 0 0 12px;
  font-size: 15px;
  color: var(--text-2);
}

.upload-tab:hover:not(:disabled) {
  color: var(--text-1);
}

.upload-tab.is-active {
  color: var(--brand-blue);
}

.upload-tab.is-active::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  content: '';
  background: var(--brand-blue);
}

.upload-tab:disabled {
  opacity: 0.6;
}

.upload-title {
  margin-top: 22px;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-1);
}

.upload-gate {
  padding: 24px 0;
}

.upload-gate__restoring {
  padding: 60px 0;
  text-align: center;
  color: var(--text-3);
}

/* ---------- 上传区 ---------- */
.upload-area {
  margin-top: 16px;
  padding: 12px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.upload-empty {
  display: flex;
  min-height: 150px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.upload-empty__btn {
  display: flex;
  height: 40px;
  align-items: center;
  gap: 8px;
  padding: 0 22px;
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.upload-empty__btn:hover:not(:disabled) {
  background: var(--brand-blue-dark);
}

.upload-empty__hint {
  font-size: 12px;
  color: var(--text-3);
}

.upload-file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-radius: var(--radius);
  background: var(--bg-0);
}

.upload-file__icon {
  display: flex;
  width: 34px;
  height: 34px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

.upload-file__text {
  min-width: 0;
}

.upload-file__name {
  overflow: hidden;
  font-size: 14px;
  color: var(--text-1);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upload-file__meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-3);
}

.upload-file__change {
  flex: none;
  margin-left: auto;
  font-size: 13px;
  color: var(--brand-blue);
}

.upload-file__change:hover:not(:disabled) {
  color: var(--brand-blue-dark);
}

.upload-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px 2px;
}

.upload-progress__track {
  flex: 1 1 auto;
  height: 4px;
  overflow: hidden;
  border-radius: var(--radius-pill);
  background: var(--line-1);
}

.upload-progress__bar {
  height: 100%;
  background: #00b42a;
  transition: width 0.2s ease;
}

.upload-progress__text {
  flex: none;
  font-size: 12px;
  color: var(--text-2);
}

/* ---------- 表单 ---------- */
.upload-section {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--line-2);
}

.upload-section__title {
  margin-bottom: 14px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.section-hint {
  margin: -6px 0 12px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}

.field {
  margin-bottom: 18px;
}

.field__label {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--text-2);
}

.required {
  margin-right: 3px;
  font-style: normal;
  color: var(--brand-pink);
}

.optional {
  color: var(--text-3);
}

.input-wrap {
  position: relative;
}

.input {
  width: 100%;
  min-height: 36px;
  padding: 7px 64px 7px 10px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius-sm);
  font-family: inherit;
  font-size: 13px;
  color: var(--text-1);
  background: var(--bg-0);
  outline: none;
}

.input:focus {
  border-color: var(--brand-blue);
}

.select {
  max-width: 240px;
  padding-right: 10px;
}

.textarea {
  min-height: 90px;
  padding: 9px 10px 24px;
  line-height: 20px;
  resize: vertical;
}

.textarea--tall {
  min-height: 160px;
}

.counter {
  position: absolute;
  right: 10px;
  bottom: 8px;
  font-size: 12px;
  color: var(--text-3);
  pointer-events: none;
}

.field-error {
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}

.cover-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.cover-box {
  display: flex;
  width: 160px;
  height: 90px;
  flex: none;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  overflow: hidden;
  border: 1px dashed var(--line-1);
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--text-3);
  background: var(--bg-1);
}

.cover-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-box:hover:not(:disabled) {
  border-color: var(--brand-blue);
  color: var(--brand-blue);
}

.cover-hint {
  padding-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}

.link-btn {
  margin-top: 2px;
  padding: 0;
  font-size: 12px;
  color: var(--brand-blue);
}

.link-btn:hover:not(:disabled) {
  color: var(--brand-blue-dark);
}

/* ---------- 图片九宫格 ---------- */
.image-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.image-tile {
  position: relative;
  width: 104px;
  height: 104px;
  overflow: hidden;
  border-radius: var(--radius-sm);
  background: var(--bg-1);
}

.image-tile img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.image-tile__remove {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  font-size: 14px;
  line-height: 18px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.55);
}

.image-tile__remove:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.75);
}

.image-tile--add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px dashed var(--line-1);
  font-size: 12px;
  color: var(--text-3);
}

.image-tile--add:hover:not(:disabled) {
  border-color: var(--brand-blue);
  color: var(--brand-blue);
}

/* ---------- 提交 ---------- */
.upload-submit {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--line-2);
}

.page-error {
  margin-right: auto;
  font-size: 13px;
  color: #f56c6c;
}

/* ---------- 成功态 ---------- */
.upload-success {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 64px 16px;
  text-align: center;
}

.upload-success__icon {
  display: flex;
  width: 88px;
  height: 88px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

.upload-success__title {
  margin-top: 18px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.upload-success__note {
  margin-top: 8px;
  max-width: 520px;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-3);
}

.upload-success__actions {
  display: flex;
  gap: 12px;
  margin-top: 22px;
}
</style>
