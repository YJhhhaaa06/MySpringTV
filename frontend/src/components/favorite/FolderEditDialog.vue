<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { FolderEditPatch, LibraryFolderVM } from '../../types/ui'
import { describeError } from '../../api/http'
import { resetMyFolderCover, updateMyFolder, uploadMyFolderCover } from '../../api/favorites'
import { showToast } from '../../common/toast'
import { lockBodyOverflow, unlockBodyOverflow } from '../../common/modalOverflow'
import { FOLDER_NAME_MAX, folderEditPatch, folderNameChangeError, hasFolderEditChanges } from './folderEdit'
import AppIcon from '../common/AppIcon.vue'
import FolderCover from './FolderCover.vue'
import CoverCropper from '../creator/CoverCropper.vue'

/**
 * 收藏夹信息弹窗（`R-35`）：封面 / 名称 / 公开 / 简介占位。
 *
 * <h2>封面为什么"选图即上传"</h2>
 * 对齐 B 站：点封面才换图，裁剪确认后立即 `PUT /favorite-folders/{id}/cover`；
 * 名称 / 公开走「保存」。所以**取消只放弃名称与公开，不撤销已上传的封面** ——
 * 弹窗里必须把这句话写给用户看，不能让他以为点取消就全回滚。
 *
 * <h2>为什么上传后要父级重拉 folders</h2>
 * 上传端点只回 200 无体、不返回新 URL（自定义文件是新的 UUID）⇒ 这里先用裁剪产物的
 * object URL 立即预览，同时通知父级 `GET /favorite-folders`；服务端 URL 回来后由
 * `watch(folder.coverUrl)` 覆盖本地预览。
 *
 * <h2>简介</h2>
 * `favorite_folder.description` 列 V2 就存在，但实体 / VO / DTO 未接；本窗按拍板
 * **只做只读占位**，不做"能输入但保存时丢弃"的假功能。
 */
const props = defineProps<{ folder: LibraryFolderVM }>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'updated', folderId: number, patch: FolderEditPatch): void
  (event: 'cover-changed', folderId: number, hasCustomCover: boolean): void
}>()

const name = ref(props.folder.name)
const isPrivate = ref(props.folder.isPrivate)
const saving = ref(false)
const uploading = ref(false)
const resetting = ref(false)
const error = ref('')

const dialog = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const coverButton = ref<HTMLButtonElement | null>(null)

const cropOpen = ref(false)
const cropSourceUrl = ref<string | null>(null)
const cropFileName = ref('cover.jpg')

const displayCoverUrl = ref<string | null>(props.folder.coverUrl)
const localPreviewUrl = ref<string | null>(null)
const customCover = ref(props.folder.hasCustomCover)

const nameError = computed(() => folderNameChangeError(props.folder.name, name.value))
const patch = computed(() => folderEditPatch(props.folder, name.value, isPrivate.value))
const dirty = computed(() => hasFolderEditChanges(patch.value))
const busy = computed(() => saving.value || uploading.value || resetting.value)
const canSave = computed(() => !busy.value && !nameError.value && dirty.value)
const coverLabel = computed(() => {
  if (uploading.value) return '上传中…'
  if (resetting.value) return '恢复中…'
  return '更换封面'
})

function revokeCropSource(): void {
  if (cropSourceUrl.value) {
    URL.revokeObjectURL(cropSourceUrl.value)
    cropSourceUrl.value = null
  }
}

function revokeLocalPreview(): void {
  if (localPreviewUrl.value) {
    URL.revokeObjectURL(localPreviewUrl.value)
    localPreviewUrl.value = null
  }
}

function cleanup(): void {
  revokeCropSource()
  revokeLocalPreview()
}

function close(): void {
  if (busy.value) return
  cleanup()
  emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  // 裁剪层自己处理 Esc；这里放行，否则两个 window 监听器会把编辑弹窗一起关掉
  if (cropOpen.value || busy.value) return
  event.preventDefault()
  close()
}

// ==================== 封面 ====================

const MAX_SOURCE_BYTES = 10 * 1024 * 1024

function openFilePicker(): void {
  if (busy.value) return
  error.value = ''
  fileInput.value?.click()
}

function onFileSelected(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 允许再次选择同一个文件（input 不复位时 change 不再触发）
  input.value = ''
  if (!file) return

  error.value = ''
  const supportedType = file.type === 'image/jpeg' || file.type === 'image/png'
  const supportedSuffix = /\.(jpe?g|png)$/i.test(file.name)
  if (!supportedType && !supportedSuffix) {
    error.value = '请选择 JPG 或 PNG 图片'
    return
  }
  if (file.size > MAX_SOURCE_BYTES) {
    error.value = '原图不能超过 10M'
    return
  }

  revokeCropSource()
  cropSourceUrl.value = URL.createObjectURL(file)
  cropFileName.value = file.name
  cropOpen.value = true
}

function onCropped(file: File): void {
  cropOpen.value = false
  revokeCropSource()
  void uploadCover(file)
}

function onCropClose(): void {
  cropOpen.value = false
  revokeCropSource()
  nextTick(() => coverButton.value?.focus())
}

async function uploadCover(file: File): Promise<void> {
  if (uploading.value) return
  uploading.value = true
  error.value = ''
  try {
    await uploadMyFolderCover(props.folder.id, file)
    // 先本地预览：服务端不返回 URL，父级重拉 folders 期间不能让用户看着旧封面
    revokeLocalPreview()
    localPreviewUrl.value = URL.createObjectURL(file)
    displayCoverUrl.value = localPreviewUrl.value
    customCover.value = true
    emit('cover-changed', props.folder.id, true)
    showToast('封面已更新')
  } catch (exception) {
    error.value = describeError(exception, '封面上传失败，请稍后重试')
  } finally {
    uploading.value = false
  }
}

async function resetCover(): Promise<void> {
  if (busy.value) return
  resetting.value = true
  error.value = ''
  try {
    await resetMyFolderCover(props.folder.id)
    // 只在**请求成功后**落 customCover=false：失败时按钮必须留在原地让用户重试
    revokeLocalPreview()
    // 自动封面要等父级重拉后才知道；先露出占位，别拿旧自定义图假装还在
    displayCoverUrl.value = null
    customCover.value = false
    emit('cover-changed', props.folder.id, false)
    showToast('已恢复自动封面')
  } catch (exception) {
    error.value = describeError(exception, '恢复自动封面失败，请稍后重试')
  } finally {
    resetting.value = false
  }
}

// ==================== 名称 / 公开 ====================

function onPublicChange(event: Event): void {
  const input = event.target as HTMLInputElement
  isPrivate.value = !input.checked
  error.value = ''
}

async function save(): Promise<void> {
  if (!canSave.value) return
  saving.value = true
  error.value = ''
  const currentPatch = patch.value
  try {
    await updateMyFolder(props.folder.id, currentPatch)
    emit('updated', props.folder.id, currentPatch)
    showToast('收藏夹信息已更新')
    cleanup()
    emit('close')
  } catch (exception) {
    error.value = describeError(exception, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// ==================== 生命周期 ====================

// 父级重拉回来后，用服务端生效封面取代本地 object URL
watch(
  () => props.folder.coverUrl,
  (next) => {
    if (uploading.value || resetting.value) return
    if (localPreviewUrl.value) revokeLocalPreview()
    displayCoverUrl.value = next
  },
)

watch(
  () => props.folder.hasCustomCover,
  (next) => {
    if (!uploading.value && !resetting.value) customCover.value = next
  },
)

onMounted(() => {
  lockBodyOverflow()
  window.addEventListener('keydown', onKeydown)
  dialog.value?.focus()
})

onBeforeUnmount(() => {
  unlockBodyOverflow()
  window.removeEventListener('keydown', onKeydown)
  cleanup()
})
</script>

<template>
  <Teleport to="body">
    <div class="dialog-mask" @click.self="close">
      <div
        ref="dialog"
        class="folder-edit"
        role="dialog"
        aria-modal="true"
        aria-labelledby="folder-edit-title"
        tabindex="-1"
      >
        <header class="folder-edit__head">
          <h2 id="folder-edit-title" class="folder-edit__title">收藏夹信息</h2>
          <button class="folder-edit__close" type="button" aria-label="关闭" @click="close">
            <AppIcon name="close" :size="18" />
          </button>
        </header>

        <form class="folder-edit__form" @submit.prevent="save">
          <section class="folder-edit__cover-row">
            <button
              ref="coverButton"
              class="folder-edit__cover"
              type="button"
              :disabled="busy"
              title="点击更换封面"
              @click="openFilePicker"
            >
              <FolderCover :cover-url="displayCoverUrl" :alt="folder.name" :icon-size="26" />
              <span class="folder-edit__cover-mask">{{ coverLabel }}</span>
            </button>
            <div class="folder-edit__cover-side">
              <p class="folder-edit__cover-title">封面</p>
              <p class="folder-edit__hint">
                点击封面换图；支持 JPG / PNG，原图 ≤10M，按 16:9 裁剪后上传。
              </p>
              <button
                v-if="customCover"
                class="link-btn"
                type="button"
                :disabled="busy"
                @click="resetCover"
              >
                {{ resetting ? '恢复中…' : '恢复自动封面' }}
              </button>
            </div>
          </section>

          <p class="folder-edit__note">封面会立即生效；取消只放弃名称 / 公开的修改，不会撤销已上传的封面。</p>

          <label class="folder-edit__field">
            <span class="folder-edit__label">名称 <i>*</i></span>
            <input
              v-model="name"
              class="folder-edit__input"
              type="text"
              :maxlength="FOLDER_NAME_MAX"
              :disabled="busy"
              aria-label="收藏夹名称"
            />
            <span class="folder-edit__count">{{ name.length }} / {{ FOLDER_NAME_MAX }}</span>
          </label>
          <p v-if="nameError" class="folder-edit__field-error" role="alert">{{ nameError }}</p>

          <div class="folder-edit__field folder-edit__field--row">
            <div>
              <span class="folder-edit__label">公开</span>
              <p class="folder-edit__hint">关闭后仅自己可见（私密收藏夹）。</p>
            </div>
            <input
              class="folder-edit__switch"
              type="checkbox"
              role="switch"
              :checked="!isPrivate"
              :disabled="busy"
              aria-label="公开收藏夹"
              @change="onPublicChange"
            />
          </div>

          <label class="folder-edit__field">
            <span class="folder-edit__label">简介</span>
            <textarea
              class="folder-edit__intro"
              rows="3"
              disabled
              placeholder="暂不支持收藏夹简介（本仓未接入）"
            ></textarea>
          </label>

          <p v-if="error" class="folder-edit__error" role="alert">{{ error }}</p>

          <footer class="folder-edit__foot">
            <button class="btn btn-ghost" type="button" :disabled="busy" @click="close">取消</button>
            <button class="btn btn-blue" type="submit" :disabled="!canSave">
              {{ saving ? '保存中…' : '保存' }}
            </button>
          </footer>
        </form>

        <input
          ref="fileInput"
          class="folder-edit__file"
          type="file"
          accept="image/jpeg,image/png,.jpg,.jpeg,.png"
          @change="onFileSelected"
        />
      </div>
    </div>
  </Teleport>

  <CoverCropper
    v-if="cropOpen && cropSourceUrl"
    :source-url="cropSourceUrl"
    :file-name="cropFileName"
    heading="裁剪收藏夹封面"
    note="按 16:9 裁切：框里看到的就是收藏夹头部与主页夹卡的封面"
    @cropped="onCropped"
    @close="onCropClose"
  />
</template>

<style scoped>
.folder-edit {
  width: 100%;
  max-width: 460px;
  padding: 20px 22px 18px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  outline: none;
}

.folder-edit__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.folder-edit__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.folder-edit__close {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-3);
}

.folder-edit__close:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

.folder-edit__form {
  margin-top: 16px;
}

.folder-edit__cover-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.folder-edit__cover {
  position: relative;
  width: 180px;
  flex: none;
  padding: 0;
  overflow: hidden;
  border-radius: var(--radius-sm);
  background: var(--bg-2);
}

.folder-edit__cover:hover:not(:disabled) .folder-edit__cover-mask {
  opacity: 1;
}

.folder-edit__cover-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.46);
  opacity: 0;
  transition: opacity 0.16s ease;
}

.folder-edit__cover-side {
  min-width: 0;
}

.folder-edit__cover-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}

.folder-edit__hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}

.folder-edit__note {
  margin-top: 12px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}

.folder-edit__field {
  display: block;
  margin-top: 14px;
}

.folder-edit__field--row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.folder-edit__label {
  font-size: 13px;
  color: var(--text-2);
}

.folder-edit__label i {
  color: var(--brand-pink);
  font-style: normal;
}

.folder-edit__input {
  width: 100%;
  height: 36px;
  margin-top: 6px;
  padding: 0 10px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-1);
  background: var(--bg-0);
  outline: none;
}

.folder-edit__input:focus {
  border-color: var(--brand-blue);
}

.folder-edit__count {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-3);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.folder-edit__field-error {
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
}

.folder-edit__switch {
  width: 38px;
  height: 20px;
  flex: none;
  appearance: none;
  border-radius: 10px;
  background: var(--bg-2);
  outline: none;
  cursor: pointer;
  transition: background 0.16s ease;
}

.folder-edit__switch::after {
  display: block;
  width: 16px;
  height: 16px;
  margin: 2px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.18);
  content: '';
  transition: transform 0.16s ease;
}

.folder-edit__switch:checked {
  background: var(--brand-blue);
}

.folder-edit__switch:checked::after {
  transform: translateX(18px);
}

.folder-edit__switch:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.folder-edit__intro {
  width: 100%;
  margin-top: 6px;
  padding: 8px 10px;
  border: 1px solid var(--line-2);
  border-radius: var(--radius);
  font-size: 13px;
  line-height: 20px;
  color: var(--text-3);
  background: var(--bg-1);
  resize: none;
}

.folder-edit__error {
  margin-top: 12px;
  font-size: 13px;
  color: #f56c6c;
}

.folder-edit__foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line-2);
}

.folder-edit__file {
  display: none;
}

.link-btn {
  margin-top: 6px;
  padding: 0;
  font-size: 12px;
  color: var(--brand-blue);
}

.link-btn:hover:not(:disabled) {
  color: var(--brand-blue-dark);
}

@media (prefers-reduced-motion: reduce) {
  .folder-edit__cover-mask,
  .folder-edit__switch,
  .folder-edit__switch::after {
    transition: none;
  }
}

@media (max-width: 520px) {
  .folder-edit__cover {
    width: 140px;
  }
}
</style>
