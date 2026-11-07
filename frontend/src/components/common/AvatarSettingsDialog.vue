<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  DEFAULT_AVATAR_IDS,
  defaultAvatarForUser,
  defaultAvatarIdOf,
  defaultAvatarUrl,
} from '../../constants/avatars'
import { selectDefaultAvatar, uploadAvatar } from '../../api/avatars'
import { ApiError, describeError } from '../../api/http'
import { showToast } from '../../common/toast'
import AppIcon from './AppIcon.vue'
import AvatarCropper from './AvatarCropper.vue'

/**
 * 更换头像弹层（`R-27` / `T82`）。
 *
 * <h2>交互（按 B 站「我的头像」页）</h2>
 * 当前头像圆形预览 + 六宫格默认头像 + 上传自定义（选图即进 1:1 裁剪 + 圆形实时预览）。
 * 「更新」才提交：选默认 ⇒ `PUT /users/me/avatar/default`；裁剪产物 ⇒
 * `PUT /users/me/avatar`（multipart）。成功后由父组件就地更新 header 与 session。
 *
 * <h2>状态口径</h2>
 * `pendingFile` / `selectedDefaultId` 是**本次选择的候选**，未提交前不改任何服务端状态；
 * 关闭弹层 = 放弃。默认头像的"当前"由 `currentAvatarUrl` 反解（`defaultAvatarIdOf`），
 * 自定义头像时六宫格无高亮。
 *
 * <h2>限制</h2>
 * 原图 ≤10MB、仅 JPG/PNG；产物 180×180 JPEG。客户端裁剪是**体验**，不是安全边界
 * （服务端仍只校验类型 + ≤2MB，不重编码，见 `R-27` 已知代价）。
 */
const props = defineProps<{
  /** 父亲空间头部的生效头像 URL（可能是自定义 / 默认 / null） */
  currentAvatarUrl: string | null
  /** 自己的 userId（无 URL 时回落默认头像） */
  userId: number
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'updated', avatarUrl: string): void
}>()

const MAX_SOURCE_BYTES = 10 * 1024 * 1024

const mode = ref<'idle' | 'crop'>('idle')
const sourceUrl = ref<string | null>(null)
const originalName = ref('avatar.jpg')
const pendingFile = ref<File | null>(null)
const previewUrl = ref<string | null>(null)
const selectedDefaultId = ref<number | null>(null)
const saving = ref(false)
const fileError = ref('')

const fileInput = ref<HTMLInputElement | null>(null)
const dialog = ref<HTMLElement | null>(null)

const currentDefaultId = computed(() => defaultAvatarIdOf(props.currentAvatarUrl))
const activeDefaultId = computed(() => selectedDefaultId.value ?? currentDefaultId.value)

const previewSrc = computed(() => {
  if (previewUrl.value) return previewUrl.value
  if (selectedDefaultId.value !== null) return defaultAvatarUrl(selectedDefaultId.value)
  return props.currentAvatarUrl ?? defaultAvatarForUser(props.userId)
})

const hasChange = computed(() => {
  if (pendingFile.value) return true
  if (selectedDefaultId.value !== null) {
    return defaultAvatarUrl(selectedDefaultId.value) !== props.currentAvatarUrl
  }
  return false
})

function revokeSource(): void {
  if (sourceUrl.value) {
    URL.revokeObjectURL(sourceUrl.value)
    sourceUrl.value = null
  }
}

function revokePreview(): void {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = null
  }
}

function clearPending(): void {
  pendingFile.value = null
  revokePreview()
}

function openFilePicker(): void {
  if (saving.value) return
  fileError.value = ''
  fileInput.value?.click()
}

function onFileSelected(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 允许再次选择同一个文件（input 不复位时 change 不再触发）
  input.value = ''
  if (!file) return

  fileError.value = ''
  const supportedType = file.type === 'image/jpeg' || file.type === 'image/png'
  const supportedSuffix = /\.(jpe?g|png)$/i.test(file.name)
  if (!supportedType && !supportedSuffix) {
    fileError.value = '请选择 JPG 或 PNG 图片'
    return
  }
  if (file.size > MAX_SOURCE_BYTES) {
    fileError.value = '原图不能超过 10M'
    return
  }

  clearPending()
  selectedDefaultId.value = null
  originalName.value = file.name
  revokeSource()
  sourceUrl.value = URL.createObjectURL(file)
  mode.value = 'crop'
}

function onCropped(file: File): void {
  clearPending()
  pendingFile.value = file
  previewUrl.value = URL.createObjectURL(file)
  selectedDefaultId.value = null
  fileError.value = ''
  mode.value = 'idle'
  revokeSource()
}

function onCropClose(): void {
  mode.value = 'idle'
  revokeSource()
}

function selectDefault(avatarId: number): void {
  if (saving.value) return
  clearPending()
  selectedDefaultId.value = avatarId
  fileError.value = ''
  mode.value = 'idle'
  revokeSource()
}

async function submit(): Promise<void> {
  if (saving.value || !hasChange.value) return
  saving.value = true
  try {
    const result = pendingFile.value
      ? await uploadAvatar(pendingFile.value)
      : await selectDefaultAvatar(selectedDefaultId.value!)
    const avatarUrl = result?.avatarUrl
    if (typeof avatarUrl !== 'string' || !avatarUrl) {
      throw new ApiError(200, 'NO_AVATAR_URL', '更新成功但没有返回头像地址')
    }
    emit('updated', avatarUrl)
    showToast('头像已更新')
    cleanup()
    emit('close')
  } catch (error) {
    showToast(describeError(error, '头像更新失败，请稍后重试'), 'error')
  } finally {
    saving.value = false
  }
}

function close(): void {
  if (saving.value) return
  cleanup()
  emit('close')
}

function cleanup(): void {
  revokeSource()
  revokePreview()
  pendingFile.value = null
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  }
}

onMounted(() => {
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKeydown)
  dialog.value?.focus()
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
  cleanup()
})
</script>

<template>
  <Teleport to="body">
    <div class="dialog-mask" @click.self="close">
      <div
        ref="dialog"
        class="avatar-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="avatar-dialog-title"
        tabindex="-1"
      >
        <header class="avatar-dialog__head">
          <div>
            <h2 id="avatar-dialog-title" class="avatar-dialog__title">更换头像</h2>
            <p class="avatar-dialog__note">我的头像 &gt; 更换头像</p>
          </div>
          <button class="avatar-dialog__close" type="button" aria-label="关闭" @click="close">
            <AppIcon name="close" :size="18" />
          </button>
        </header>

        <AvatarCropper
          v-if="mode === 'crop' && sourceUrl"
          :source-url="sourceUrl"
          :file-name="originalName"
          @close="onCropClose"
          @cropped="onCropped"
        />

        <template v-else>
          <div class="avatar-dialog__body">
            <section class="avatar-dialog__current">
              <div class="avatar-dialog__preview">
                <img v-if="previewSrc" :src="previewSrc" alt="" draggable="false" />
              </div>
              <p class="avatar-dialog__preview-label">预览头像</p>
            </section>

            <section class="avatar-dialog__defaults">
              <h3 class="avatar-dialog__section-title">选择默认头像</h3>
              <div class="avatar-dialog__grid">
                <button
                  v-for="avatarId in DEFAULT_AVATAR_IDS"
                  :key="avatarId"
                  class="avatar-dialog__option"
                  :class="{ 'is-active': activeDefaultId === avatarId }"
                  type="button"
                  :aria-label="`默认头像 ${avatarId}`"
                  :aria-pressed="activeDefaultId === avatarId"
                  :disabled="saving"
                  @click="selectDefault(avatarId)"
                >
                  <img :src="defaultAvatarUrl(avatarId)" alt="" draggable="false" />
                  <span v-if="activeDefaultId === avatarId" class="avatar-dialog__check">
                    <AppIcon name="check" :size="13" />
                  </span>
                </button>
              </div>

              <div class="avatar-dialog__upload">
                <button class="btn btn-ghost" type="button" :disabled="saving" @click="openFilePicker">
                  上传自定义头像
                </button>
                <input
                  ref="fileInput"
                  class="avatar-dialog__file"
                  type="file"
                  accept="image/jpeg,image/png,.jpg,.jpeg,.png"
                  @change="onFileSelected"
                />
              </div>
              <p class="avatar-dialog__hint">支持 JPG / PNG，原图 ≤10M；裁剪后保存为 180×180 JPG</p>
              <p v-if="fileError" class="avatar-dialog__error" role="alert">{{ fileError }}</p>
            </section>
          </div>

          <footer class="avatar-dialog__foot">
            <button class="btn btn-ghost" type="button" :disabled="saving" @click="close">取消</button>
            <button
              class="btn btn-blue"
              type="button"
              :disabled="saving || !hasChange"
              @click="submit"
            >
              {{ saving ? '更新中…' : '更新' }}
            </button>
          </footer>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.avatar-dialog {
  width: 100%;
  max-width: 620px;
  padding: 18px 20px 16px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  outline: none;
}

.avatar-dialog__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.avatar-dialog__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.avatar-dialog__note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.avatar-dialog__close {
  display: flex;
  width: 28px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-3);
}

.avatar-dialog__close:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

.avatar-dialog__body {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  gap: 28px;
  padding: 8px 0 4px;
}

.avatar-dialog__current {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding-top: 8px;
}

.avatar-dialog__preview {
  width: 120px;
  height: 120px;
  overflow: hidden;
  border-radius: 50%;
  background: var(--bg-2);
  box-shadow: 0 0 0 1px var(--line-1);
}

.avatar-dialog__preview img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-dialog__preview-label {
  font-size: 12px;
  color: var(--text-3);
}

.avatar-dialog__defaults {
  flex: 1;
  min-width: 0;
}

.avatar-dialog__section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}

.avatar-dialog__grid {
  display: grid;
  grid-template-columns: repeat(3, 56px);
  gap: 12px;
  margin-top: 10px;
}

.avatar-dialog__option {
  position: relative;
  width: 56px;
  height: 56px;
  padding: 0;
  overflow: hidden;
  border-radius: 50%;
  background: var(--bg-2);
  box-shadow: 0 0 0 1px var(--line-1);
  line-height: 0;
}

.avatar-dialog__option img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-dialog__option:hover:not(:disabled) {
  box-shadow: 0 0 0 2px var(--brand-blue);
}

.avatar-dialog__option.is-active {
  box-shadow: 0 0 0 2px var(--brand-blue);
}

.avatar-dialog__option:disabled {
  opacity: 0.65;
}

.avatar-dialog__check {
  position: absolute;
  right: -1px;
  bottom: -1px;
  display: flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--bg-0);
  border-radius: 50%;
  color: #fff;
  background: var(--brand-blue);
}

.avatar-dialog__upload {
  margin-top: 14px;
}

.avatar-dialog__file {
  display: none;
}

.avatar-dialog__hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
}

.avatar-dialog__error {
  margin-top: 6px;
  font-size: 12px;
  color: var(--brand-pink);
}

.avatar-dialog__foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line-2);
}

@media (max-width: 640px) {
  .avatar-dialog__body {
    flex-direction: column;
    align-items: center;
    gap: 16px;
  }

  .avatar-dialog__grid {
    grid-template-columns: repeat(6, 44px);
    gap: 8px;
  }

  .avatar-dialog__option {
    width: 44px;
    height: 44px;
  }
}
</style>
