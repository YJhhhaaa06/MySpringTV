<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loadEditableContent, saveContentEdit } from '../api/creator'
import { isDirty, swapImages, toEditableContent, toUpdatePayload, validateEdit } from '../api/creatorEdit'
import type { EditableContent } from '../api/creatorEdit'
import { ApiError, describeError } from '../api/http'
import { POST_DESCRIPTION_MAX, TITLE_MAX, VIDEO_DESCRIPTION_MAX } from '../api/uploadDraft'
import type { UploadFieldError } from '../api/uploadDraft'
import { uploadAsset } from '../api/upload'
import { contentHref } from '../api/adapters/links'
import { requireLogin } from '../auth/loginPrompt'
import { sessionState, signOut } from '../auth/session'
import { showToast } from '../common/toast'
import { UPLOAD_CATEGORIES } from '../constants/categories'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import ImageReorderGrid from '../components/creator/ImageReorderGrid.vue'

/**
 * 编辑页（`/creator/edit/:id`）：**取回 → 编辑 → 提交覆盖**。
 *
 * <h2>体感为什么是"从云端取回"</h2>
 * 进页面先 `GET /contents/{id}` 把标题 / 简介 / 分区 / 封面 / 视频 / 图片顺序整份填进来；
 * 之后除了"换源"要先传文件拿 URL，其余全在本地改。**保存之前观众看到的还是旧版本**——
 * 新上传的文件还没被任何内容引用（upload-only 端点不写库），这是"编辑中不可见"的实现基础。
 *
 * <h2>★ 两个入口的媒体语义</h2>
 * - 换源：点「换源 / 更换」→ 立刻 `POST /uploads/{type}` 拿新 URL，**写进草稿**；
 * - 保存：`PUT /contents/{id}` 带**整份**清单 ⇒ 未改的 URL 不动、换掉的旧文件由服务端删除。
 * 所以"取消"只是不保存：已经上传的新文件会变成孤儿（后端已知代价，离线脚本回收），
 * 而**不会**污染线上内容。
 *
 * <h2>权限</h2>
 * 详情的作者 id 与当前登录用户不一致 ⇒ 直接渲染「不是你的稿件」（连表单都不给看）。
 * 这只是体验层：真正的拦截在保存时的 403（详情端点是公开的，判不了所有权）。
 */
type Phase = 'restoring' | 'anonymous' | 'loading' | 'ready' | 'denied' | 'missing' | 'error'

const route = useRoute()
const router = useRouter()

const contentId = Number(route.params.id)

const phase = ref<Phase>('loading')
const draft = ref<EditableContent | null>(null)
const original = ref<EditableContent | null>(null)
const errors = ref<UploadFieldError[]>([])
const pageError = ref('')
const saving = ref(false)
/** 正在上传的槽位（`video` / `cover` / `image`）—— 同一时刻只允许一个上传 */
const busy = ref<string | null>(null)

const videoInput = ref<HTMLInputElement | null>(null)
const coverInput = ref<HTMLInputElement | null>(null)
const imageInput = ref<HTMLInputElement | null>(null)
const addImagesInput = ref<HTMLInputElement | null>(null)
/** 正在"换源"的那张图的序号（-1 = 不是换源，是追加） */
const replaceIndex = ref(-1)

const authenticated = computed(() => sessionState.status === 'authenticated')
const dirty = computed(() =>
  draft.value && original.value ? isDirty(original.value, draft.value) : false,
)
const descriptionMax = computed(() =>
  draft.value?.isPost ? POST_DESCRIPTION_MAX : VIDEO_DESCRIPTION_MAX,
)
const viewHref = computed(() =>
  draft.value ? contentHref(contentId, draft.value.isPost) : '/',
)
const saveLabel = computed(() => {
  if (saving.value) return '保存中…'
  return dirty.value ? '保存修改' : '没有改动'
})

function cloneDraft(source: EditableContent): EditableContent {
  return { ...source, images: source.images.map((image) => ({ ...image })) }
}

function errorOf(field: string): string | null {
  return errors.value.find((error) => error.field === field)?.message ?? null
}

function clearError(field: string): void {
  errors.value = errors.value.filter((error) => error.field !== field)
}

function login(): void {
  void requireLogin()
}

function handleError(error: unknown, fallback: string): void {
  if (error instanceof ApiError) {
    if (error.isAuthError) {
      signOut()
      showToast('登录已过期，请重新登录', 'error')
      return
    }
    if (error.code === 'UPLOAD_TIMEOUT' || error.code === 'UPLOAD_ABORTED') {
      pageError.value = error.message
      return
    }
    pageError.value = describeError(error, fallback)
    return
  }
  pageError.value = fallback
}

// ==================== 取回 ====================

async function load(): Promise<void> {
  if (!Number.isInteger(contentId) || contentId <= 0) {
    phase.value = 'missing'
    return
  }
  if (sessionState.status === 'restoring') {
    phase.value = 'restoring'
    return
  }
  if (!authenticated.value) {
    phase.value = 'anonymous'
    return
  }

  phase.value = 'loading'
  pageError.value = ''
  try {
    const detail = await loadEditableContent(contentId)
    if (detail.authorId !== sessionState.user?.id) {
      // 不是自己的稿件：表单不给看（保存侧另有 403 兜底）
      phase.value = 'denied'
      return
    }
    const editable = toEditableContent(detail)
    draft.value = editable
    original.value = cloneDraft(editable)
    phase.value = 'ready'
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 404) {
        phase.value = 'missing'
        return
      }
      if (error.isAuthError) {
        signOut()
        phase.value = 'anonymous'
        return
      }
    }
    handleError(error, '稿件加载失败，请稍后重试')
    phase.value = 'error'
  }
}

watch(() => sessionState.status, () => void load(), { immediate: true })

// ==================== 换源 / 加图 / 删除 / 换序 ====================

async function uploadInto(slot: 'video' | 'cover' | 'image', file: File, apply: (url: string) => void): Promise<void> {
  if (busy.value) return
  busy.value = slot
  pageError.value = ''
  try {
    const asset = await uploadAsset(file, slot)
    apply(asset.url)
  } catch (error) {
    handleError(error, '上传失败，请稍后重试')
  } finally {
    busy.value = null
  }
}

function pickVideo(): void {
  videoInput.value?.click()
}

function pickCover(): void {
  coverInput.value?.click()
}

function pickReplaceImage(index: number): void {
  replaceIndex.value = index
  imageInput.value?.click()
}

function pickAddImages(): void {
  addImagesInput.value?.click()
}

function onVideoPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file || !draft.value) return
  void uploadInto('video', file, (url) => {
    if (!draft.value) return
    draft.value.videoUrl = url
    // 换了源 ⇒ 旧时长不再代表这份文件（新时长由服务端保存时重新解析），别把它留在界面上
    draft.value.durationText = null
    clearError('videoUrl')
  })
}

function onCoverPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file || !draft.value) return
  void uploadInto('cover', file, (url) => {
    if (!draft.value) return
    draft.value.coverUrl = url
    clearError('coverUrl')
  })
}

function onReplaceImagePicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  const index = replaceIndex.value
  input.value = ''
  replaceIndex.value = -1
  if (!file || !draft.value || index < 0) return
  void uploadInto('image', file, (url) => {
    if (!draft.value) return
    draft.value.images[index] = { url, isNew: true }
    clearError('image')
  })
}

function onAddImagesPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length || !draft.value) return
  // 逐张上传：并发会让"哪张先回来"决定顺序，而顺序就是 sort
  void (async () => {
    for (const file of files) {
      const target = draft.value
      if (!target) return
      await uploadInto('image', file, (url) => {
        target.images.push({ url, isNew: true })
        clearError('image')
      })
    }
  })()
}

function removeImage(index: number): void {
  if (!draft.value || busy.value) return
  draft.value.images.splice(index, 1)
}

/** 移除封面（图文允许无封面；视频会因此校验不通过，保存按钮点不动） */
function removeCover(): void {
  if (!draft.value || busy.value) return
  draft.value.coverUrl = null
  clearError('coverUrl')
}

function onSwapImages(from: number, to: number): void {
  if (!draft.value) return
  draft.value.images = swapImages(draft.value.images, from, to)
}

// ==================== 保存 ====================

async function save(): Promise<void> {
  const current = draft.value
  if (!current || saving.value || busy.value) return
  pageError.value = ''
  errors.value = validateEdit(current)
  if (errors.value.length) return

  saving.value = true
  try {
    await saveContentEdit(contentId, toUpdatePayload(current))
    // 保存成功 = 线上已是这份内容：把"新上传"角标摘掉，并把脏基线挪到当前草稿
    current.images = current.images.map((image) => ({ ...image, isNew: false }))
    original.value = cloneDraft(current)
    showToast('修改已生效')
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      pageError.value = '这条稿件不属于你，无法修改'
      return
    }
    handleError(error, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

function backToList(): void {
  void router.push('/creator/manage')
}
</script>

<template>
  <div class="edit-page">
    <header class="edit-head">
      <button class="link-btn" type="button" @click="backToList">← 稿件管理</button>
      <h1 v-if="phase === 'ready' && draft" class="edit-title">
        编辑{{ draft.isPost ? '图文' : '视频' }}
        <span class="edit-id">id {{ contentId }}</span>
      </h1>
    </header>

    <p v-if="phase === 'restoring'" class="edit-hint">正在恢复登录状态…</p>

    <EmptyState
      v-else-if="phase === 'anonymous'"
      icon="user"
      title="登录后才能编辑稿件"
      description="编辑会直接覆盖线上内容，因此需要确认是作者本人。"
    >
      <button class="btn btn-blue" type="button" @click="login">立即登录</button>
    </EmptyState>

    <p v-else-if="phase === 'loading'" class="edit-hint">正在取回稿件…</p>

    <EmptyState
      v-else-if="phase === 'denied'"
      icon="user"
      title="这不是你的稿件"
      description="只能编辑自己发布的内容。"
    >
      <RouterLink class="btn btn-ghost" to="/creator/manage">返回稿件管理</RouterLink>
    </EmptyState>

    <EmptyState
      v-else-if="phase === 'missing'"
      icon="tv"
      title="找不到这条稿件"
      description="它可能已被删除。"
    >
      <RouterLink class="btn btn-ghost" to="/creator/manage">返回稿件管理</RouterLink>
    </EmptyState>

    <EmptyState v-else-if="phase === 'error'" icon="tv" :title="pageError || '稿件加载失败'">
      <button class="btn btn-ghost" type="button" @click="load">重试</button>
    </EmptyState>

    <form v-else-if="phase === 'ready' && draft" class="edit-form" @submit.prevent="save">
      <!-- ==================== 媒体 ==================== -->
      <section class="edit-section">
        <h2 class="edit-section__title">{{ draft.isPost ? '图片' : '视频与封面' }}</h2>

        <template v-if="draft.isPost">
          <ImageReorderGrid
            :images="draft.images"
            :disabled="saving || busy !== null"
            @swap="onSwapImages"
            @remove="removeImage"
            @replace="pickReplaceImage"
            @add="pickAddImages"
          />
          <p v-if="errorOf('image')" class="field-error">{{ errorOf('image') }}</p>
          <p v-if="busy === 'image'" class="edit-hint">正在上传图片…</p>

          <div class="field field--top">
            <span class="field__label">封面 <span class="optional">（可选）</span></span>
            <div class="cover-row">
              <button class="cover-box" type="button" :disabled="saving || busy !== null" @click="pickCover">
                <img v-if="draft.coverUrl" :src="draft.coverUrl" alt="封面预览" />
                <template v-else>
                  <AppIcon name="upload" :size="20" />
                  <span>添加封面</span>
                </template>
              </button>
              <p class="cover-hint">
                JPG / PNG，≤50MB
                <button
                  v-if="draft.coverUrl"
                  class="link-btn"
                  type="button"
                  :disabled="saving || busy !== null"
                  @click="removeCover"
                >
                  移除封面
                </button>
              </p>
            </div>
            <p v-if="errorOf('coverUrl')" class="field-error">{{ errorOf('coverUrl') }}</p>
            <p v-if="busy === 'cover'" class="edit-hint">正在上传封面…</p>
          </div>
        </template>

        <template v-else>
          <div class="video-row">
            <video
              v-if="draft.videoUrl"
              class="video-preview"
              :src="draft.videoUrl"
              controls
              preload="metadata"
            ></video>
            <div v-else class="video-missing">当前没有视频文件</div>
            <div class="video-side">
              <button class="btn btn-ghost" type="button" :disabled="saving || busy !== null" @click="pickVideo">
                更换视频
              </button>
              <p class="cover-hint">
                仅 MP4，≤50MB{{ draft.durationText ? `（当前 ${draft.durationText}）` : '' }}
                <br />
                更换后时长由服务端重新解析
              </p>
              <p v-if="busy === 'video'" class="edit-hint">正在上传视频…</p>
            </div>
          </div>
          <p v-if="errorOf('videoUrl')" class="field-error">{{ errorOf('videoUrl') }}</p>

          <div class="field field--top">
            <span class="field__label"><i class="required">*</i>封面</span>
            <div class="cover-row">
              <button class="cover-box" type="button" :disabled="saving || busy !== null" @click="pickCover">
                <img v-if="draft.coverUrl" :src="draft.coverUrl" alt="封面预览" />
                <template v-else>
                  <AppIcon name="upload" :size="20" />
                  <span>添加封面</span>
                </template>
              </button>
              <p class="cover-hint">建议 16:9；JPG / PNG，≤50MB</p>
            </div>
            <p v-if="errorOf('coverUrl')" class="field-error">{{ errorOf('coverUrl') }}</p>
            <p v-if="busy === 'cover'" class="edit-hint">正在上传封面…</p>
          </div>
        </template>

        <input ref="videoInput" class="visually-hidden" type="file" accept="video/mp4,.mp4" @change="onVideoPicked" />
        <input
          ref="coverInput"
          class="visually-hidden"
          type="file"
          accept="image/png,image/jpeg,.png,.jpg"
          @change="onCoverPicked"
        />
        <input
          ref="imageInput"
          class="visually-hidden"
          type="file"
          accept="image/png,image/jpeg,.png,.jpg,.jpeg"
          @change="onReplaceImagePicked"
        />
        <input
          ref="addImagesInput"
          class="visually-hidden"
          type="file"
          accept="image/png,image/jpeg,.png,.jpg,.jpeg"
          multiple
          @change="onAddImagesPicked"
        />
      </section>

      <!-- ==================== 基本信息 ==================== -->
      <section class="edit-section">
        <h2 class="edit-section__title">基本设置</h2>

        <div class="field">
          <label class="field__label" for="edit-title"><i class="required">*</i>标题</label>
          <div class="input-wrap">
            <input
              id="edit-title"
              v-model="draft.title"
              class="input"
              type="text"
              maxlength="50"
              placeholder="请输入稿件标题"
              @input="clearError('title')"
            />
            <span class="counter">{{ draft.title.length }}/{{ TITLE_MAX }}</span>
          </div>
          <p v-if="errorOf('title')" class="field-error">{{ errorOf('title') }}</p>
        </div>

        <div class="field">
          <label class="field__label" for="edit-category"><i class="required">*</i>分区</label>
          <select
            id="edit-category"
            v-model.number="draft.categoryId"
            class="input select"
            @change="clearError('categoryId')"
          >
            <option v-for="item in UPLOAD_CATEGORIES" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
          <p v-if="errorOf('categoryId')" class="field-error">{{ errorOf('categoryId') }}</p>
        </div>

        <div class="field">
          <label class="field__label" for="edit-desc">简介</label>
          <div class="input-wrap">
            <textarea
              id="edit-desc"
              v-model="draft.description"
              class="input textarea"
              :maxlength="descriptionMax"
              placeholder="填写更全面的相关信息，让更多人能找到你的稿件"
              @input="clearError('description')"
            ></textarea>
            <span class="counter">{{ draft.description.length }}/{{ descriptionMax }}</span>
          </div>
          <p v-if="errorOf('description')" class="field-error">{{ errorOf('description') }}</p>
        </div>
      </section>

      <footer class="edit-submit">
        <p v-if="pageError" class="page-error">{{ pageError }}</p>
        <a class="link-btn" :href="viewHref" target="_blank" rel="noopener">查看线上页面</a>
        <button class="btn btn-ghost" type="button" :disabled="saving" @click="backToList">返回</button>
        <button class="btn btn-blue" type="submit" :disabled="saving || busy !== null || !dirty">
          {{ saveLabel }}
        </button>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.edit-page {
  min-height: 420px;
}

.edit-head {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line-2);
}

.link-btn {
  padding: 0;
  font-size: 13px;
  color: var(--brand-blue);
}

.link-btn:hover {
  color: var(--brand-blue-dark);
}

.edit-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.edit-id {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-4);
}

.edit-hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-3);
}

.edit-form {
  padding-top: 6px;
}

.edit-section {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--line-2);
}

.edit-section:first-of-type {
  border-top: 0;
}

.edit-section__title {
  margin-bottom: 14px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.video-row {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.video-preview {
  width: 320px;
  max-width: 100%;
  border-radius: var(--radius-sm);
  background: #000;
}

.video-missing {
  display: flex;
  width: 320px;
  height: 180px;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--line-1);
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--text-3);
}

.video-side {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field {
  margin-bottom: 18px;
}

.field--top {
  margin-top: 18px;
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

.cover-hint .link-btn {
  margin-left: 8px;
}

.edit-submit {
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

@media (max-width: 860px) {
  .video-row {
    flex-direction: column;
  }

  .video-preview,
  .video-missing {
    width: 100%;
  }
}
</style>
