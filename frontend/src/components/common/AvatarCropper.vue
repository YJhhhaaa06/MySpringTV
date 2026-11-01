<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  AVATAR_MAX_ZOOM,
  AVATAR_MIN_ZOOM,
  AVATAR_OUTPUT_SIZE,
  avatarCropRect,
  avatarDragBy,
  avatarFileName,
  avatarStageLayout,
  avatarZoomAround,
  fitCropState,
} from '../../api/avatarCrop'
import type { CoverCropState, Size } from '../../api/coverCrop'

/**
 * 头像裁剪（`R-27`）：1:1 舞台 + 右侧圆形实时预览。
 *
 * <h2>几何全在 `api/avatarCrop.ts`</h2>
 * 本组件只做三件事：量舞台、把指针位移 / 缩放倍数喂给纯函数、导出 canvas。
 * 拖动 / 缩放 / 不越界的物理含义由单测复算，组件里没有第二套数学。
 *
 * <h2>为什么预览用 CSS 而不是第二个 canvas</h2>
 * 预览只是"同一份裁剪状态在小舞台上的渲染"：`avatarStageLayout(image, previewSize, state)`
 * 给出的 scale / offset 直接作用于 `<img>`，与主舞台逐像素同源。再开一个 canvas
 * 会在拖动时多一条渲染路径（两边还可能差一帧）。
 *
 * <h2>导出</h2>
 * 180×180 JPEG（白底；圆形展示会盖住四角）。产物名 `photo.png → photo-avatar.jpg`
 * 与封面裁剪同一套"可区分原图 / 可重复裁剪"的规则。
 */
const props = defineProps<{
  /** 源图地址（本组件只收本地 object URL；编辑页的已上传头像不适用） */
  sourceUrl: string
  /** 源文件名（决定裁剪产物名） */
  fileName: string
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'cropped', file: File): void
}>()

const PREVIEW_SIZE = 120

const stage = ref<HTMLDivElement | null>(null)
const image = ref<HTMLImageElement | null>(null)
const natural = ref<Size>({ width: 0, height: 0 })
const stageSize = ref<Size>({ width: 0, height: 0 })
const state = ref<CoverCropState>(fitCropState())
const loaded = ref(false)
const loadError = ref('')
const exporting = ref(false)
const dragging = ref(false)

let observer: ResizeObserver | null = null
let pointer: { id: number; x: number; y: number } | null = null

const imageSize = computed<Size>(() => natural.value)
const layout = computed(() => avatarStageLayout(imageSize.value, stageSize.value, state.value))
const previewLayout = computed(() =>
  avatarStageLayout(imageSize.value, { width: PREVIEW_SIZE, height: PREVIEW_SIZE }, state.value),
)
const zoomPercent = computed(() => Math.round(state.value.zoom * 100))
const exportingLabel = computed(() => (exporting.value ? '处理中…' : '使用这张头像'))
const imageStyle = computed(() => ({
  width: `${imageSize.value.width * layout.value.scale}px`,
  height: `${imageSize.value.height * layout.value.scale}px`,
  transform: `translate(${layout.value.offsetX}px, ${layout.value.offsetY}px)`,
}))
const previewImageStyle = computed(() => ({
  width: `${imageSize.value.width * previewLayout.value.scale}px`,
  height: `${imageSize.value.height * previewLayout.value.scale}px`,
  transform: `translate(${previewLayout.value.offsetX}px, ${previewLayout.value.offsetY}px)`,
}))

function measure(): void {
  const element = stage.value
  if (!element) return
  stageSize.value = { width: element.clientWidth, height: element.clientHeight }
}

function reset(): void {
  state.value = fitCropState()
}

function close(): void {
  if (exporting.value) return
  emit('close')
}

// ==================== 指针：拖动 ====================

function onPointerDown(event: PointerEvent): void {
  if (!loaded.value || exporting.value) return
  pointer = { id: event.pointerId, x: event.clientX, y: event.clientY }
  dragging.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onPointerMove(event: PointerEvent): void {
  if (!pointer || pointer.id !== event.pointerId) return
  const dx = event.clientX - pointer.x
  const dy = event.clientY - pointer.y
  pointer.x = event.clientX
  pointer.y = event.clientY
  state.value = avatarDragBy(imageSize.value, stageSize.value, state.value, dx, dy)
}

function onPointerUp(event: PointerEvent): void {
  if (!pointer || pointer.id !== event.pointerId) return
  pointer = null
  dragging.value = false
  const target = event.currentTarget as HTMLElement
  if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId)
}

// ==================== 滚轮 / 滑杆：以锚点缩放 ====================

function onWheel(event: WheelEvent): void {
  if (!loaded.value || !stage.value) return
  const box = stage.value.getBoundingClientRect()
  const next = state.value.zoom * (event.deltaY < 0 ? 1.08 : 1 / 1.08)
  state.value = avatarZoomAround(
    imageSize.value,
    stageSize.value,
    state.value,
    next,
    { x: event.clientX - box.left, y: event.clientY - box.top },
  )
}

function onZoomInput(event: Event): void {
  const value = Number((event.target as HTMLInputElement).value)
  state.value = avatarZoomAround(
    imageSize.value,
    stageSize.value,
    state.value,
    value,
    { x: stageSize.value.width / 2, y: stageSize.value.height / 2 },
  )
}

// ==================== 导出 ====================

async function confirm(): Promise<void> {
  const source = image.value
  if (!source || !loaded.value) {
    loadError.value = '图片还没准备好，请稍等再试'
    return
  }
  if (exporting.value) return
  exporting.value = true
  try {
    const rect = avatarCropRect(imageSize.value, state.value)
    const canvas = document.createElement('canvas')
    canvas.width = AVATAR_OUTPUT_SIZE
    canvas.height = AVATAR_OUTPUT_SIZE
    const context = canvas.getContext('2d')
    if (!context) throw new Error('canvas unavailable')
    // JPEG 无 alpha：不铺底会把 PNG 透明区落成黑块
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.imageSmoothingEnabled = true
    context.imageSmoothingQuality = 'high'
    context.drawImage(
      source,
      rect.x,
      rect.y,
      rect.width,
      rect.height,
      0,
      0,
      canvas.width,
      canvas.height,
    )
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', 0.92),
    )
    if (!blob) throw new Error('toBlob failed')
    emit(
      'cropped',
      new File([blob], avatarFileName(props.fileName), {
        type: 'image/jpeg',
        lastModified: Date.now(),
      }),
    )
  } catch {
    loadError.value = '这张图片无法裁切（跨域或格式不支持），请重新选择本地图片'
  } finally {
    exporting.value = false
  }
}

// ==================== 生命周期 ====================

function onImageLoaded(event: Event): void {
  const element = event.target as HTMLImageElement
  natural.value = { width: element.naturalWidth, height: element.naturalHeight }
  if (!natural.value.width || !natural.value.height) {
    loadError.value = '这张图片读不出尺寸，请换一张再试'
    return
  }
  loaded.value = true
  measure()
}

function onImageError(): void {
  loadError.value = '图片加载失败，请重新选择'
}

watch(
  () => props.sourceUrl,
  async () => {
    loaded.value = false
    loadError.value = ''
    natural.value = { width: 0, height: 0 }
    state.value = fitCropState()
    await nextTick()
    measure()
    observer?.disconnect()
    if (stage.value && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(() => measure())
      observer.observe(stage.value)
    }
  },
  { immediate: true },
)

onMounted(() => {
  measure()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <section class="avatar-crop" aria-label="裁剪头像">
    <header class="avatar-crop__head">
      <div>
        <h3 class="avatar-crop__title">裁剪头像</h3>
        <p class="avatar-crop__note">按 1:1 裁切：框里看到的就是圆形头像显示的区域</p>
      </div>
    </header>

    <div class="avatar-crop__body">
      <div
        ref="stage"
        class="avatar-crop__stage"
        :class="{ 'is-dragging': dragging }"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerUp"
        @wheel.prevent="onWheel"
      >
        <img
          v-if="sourceUrl && !loadError"
          ref="image"
          :src="sourceUrl"
          class="avatar-crop__image"
          :style="imageStyle"
          alt=""
          draggable="false"
          @load="onImageLoaded"
          @error="onImageError"
        />
        <!-- 三分线：帮用户把脸摆在交点附近（不是装饰） -->
        <div v-if="loaded" class="avatar-crop__grid" aria-hidden="true">
          <i class="avatar-crop__grid-v avatar-crop__grid-v--1"></i>
          <i class="avatar-crop__grid-v avatar-crop__grid-v--2"></i>
          <i class="avatar-crop__grid-h avatar-crop__grid-h--1"></i>
          <i class="avatar-crop__grid-h avatar-crop__grid-h--2"></i>
        </div>
        <p v-if="!loaded && !loadError" class="avatar-crop__loading">正在读取图片…</p>
        <p v-if="loadError" class="avatar-crop__error" role="alert">{{ loadError }}</p>
      </div>

      <aside class="avatar-crop__preview">
        <div class="avatar-crop__preview-circle">
          <img
            v-if="loaded && !loadError"
            :src="sourceUrl"
            class="avatar-crop__image"
            :style="previewImageStyle"
            alt=""
            draggable="false"
          />
        </div>
        <p class="avatar-crop__preview-label">预览头像</p>
      </aside>
    </div>

    <div class="avatar-crop__controls">
      <div class="avatar-crop__zoom">
        <span class="avatar-crop__zoom-icon" aria-hidden="true">−</span>
        <input
          class="avatar-crop__range"
          type="range"
          :min="AVATAR_MIN_ZOOM"
          :max="AVATAR_MAX_ZOOM"
          step="0.01"
          :value="state.zoom"
          :disabled="!loaded"
          aria-label="头像裁剪缩放"
          @input="onZoomInput"
        />
        <span class="avatar-crop__zoom-icon" aria-hidden="true">＋</span>
        <span class="avatar-crop__zoom-value">{{ zoomPercent }}%</span>
      </div>
      <div class="avatar-crop__tips">
        <span>拖动图片调整位置</span>
        <button class="link-btn" type="button" :disabled="!loaded" @click="reset">还原</button>
      </div>
    </div>

    <footer class="avatar-crop__foot">
      <p class="avatar-crop__output">
        将保存为 {{ AVATAR_OUTPUT_SIZE }}×{{ AVATAR_OUTPUT_SIZE }} JPG（{{ avatarFileName(fileName) }}）
      </p>
      <div class="avatar-crop__actions">
        <button class="btn btn-ghost" type="button" :disabled="exporting" @click="close">取消</button>
        <button class="btn btn-blue" type="button" :disabled="!loaded || exporting" @click="confirm">
          {{ exportingLabel }}
        </button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.avatar-crop {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.avatar-crop__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.avatar-crop__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.avatar-crop__note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.avatar-crop__body {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  gap: 28px;
}

/* 舞台恒 1:1：它就是圆形头像实际取景的区域 */
.avatar-crop__stage {
  position: relative;
  width: 280px;
  max-width: 100%;
  aspect-ratio: 1 / 1;
  overflow: hidden;
  border-radius: var(--radius);
  background: #0f0f10;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.avatar-crop__stage.is-dragging {
  cursor: grabbing;
}

.avatar-crop__image {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none;
  transform-origin: 0 0;
  will-change: transform;
}

.avatar-crop__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.28);
}

.avatar-crop__grid-v,
.avatar-crop__grid-h {
  position: absolute;
  background: rgba(255, 255, 255, 0.18);
}

.avatar-crop__grid-v {
  top: 0;
  bottom: 0;
  width: 1px;
}

.avatar-crop__grid-h {
  right: 0;
  left: 0;
  height: 1px;
}

.avatar-crop__grid-v--1 {
  left: 33.333%;
}

.avatar-crop__grid-v--2 {
  left: 66.666%;
}

.avatar-crop__grid-h--1 {
  top: 33.333%;
}

.avatar-crop__grid-h--2 {
  top: 66.666%;
}

.avatar-crop__loading,
.avatar-crop__error {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 24px;
  font-size: 13px;
  text-align: center;
}

.avatar-crop__loading {
  color: rgba(255, 255, 255, 0.7);
}

.avatar-crop__error {
  color: #ffb4b4;
}

.avatar-crop__preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
}

.avatar-crop__preview-circle {
  position: relative;
  width: 120px;
  height: 120px;
  overflow: hidden;
  border-radius: 50%;
  background: var(--bg-2);
  box-shadow: 0 0 0 1px var(--line-1);
}

.avatar-crop__preview-label {
  font-size: 12px;
  color: var(--text-3);
}

.avatar-crop__controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  font-size: 12px;
  color: var(--text-3);
}

.avatar-crop__zoom {
  display: flex;
  align-items: center;
  gap: 8px;
}

.avatar-crop__zoom-icon {
  width: 12px;
  text-align: center;
}

.avatar-crop__range {
  width: 150px;
  accent-color: var(--brand-blue);
}

.avatar-crop__zoom-value {
  min-width: 42px;
  font-variant-numeric: tabular-nums;
  color: var(--text-2);
}

.avatar-crop__tips {
  display: flex;
  align-items: center;
  gap: 10px;
}

.link-btn {
  padding: 0;
  font-size: 12px;
  color: var(--brand-blue);
}

.link-btn:hover:not(:disabled) {
  color: var(--brand-blue-dark);
}

.avatar-crop__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--line-2);
}

.avatar-crop__output {
  font-size: 12px;
  color: var(--text-3);
}

.avatar-crop__actions {
  display: flex;
  flex: none;
  gap: 10px;
}

@media (max-width: 640px) {
  .avatar-crop__body {
    gap: 16px;
  }

  .avatar-crop__stage {
    width: 220px;
  }

  .avatar-crop__preview-circle {
    width: 90px;
    height: 90px;
  }

  .avatar-crop__controls,
  .avatar-crop__foot {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
}
</style>
