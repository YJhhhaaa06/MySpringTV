<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  COVER_MAX_ZOOM,
  COVER_MIN_ZOOM,
  COVER_OUTPUT_HEIGHT,
  COVER_OUTPUT_WIDTH,
  clampZoom,
  coverFileName,
  cropRect,
  dragBy,
  fitCropState,
  stageLayout,
  zoomAround,
} from '../../api/coverCrop'
import type { CoverCropState, Size } from '../../api/coverCrop'
import AppIcon from '../common/AppIcon.vue'

/**
 * 封面裁剪弹层：**窗里看到的就是卡片 / 播放器里看到的**。
 *
 * <p>为什么必须裁（而不是"原图直接传、展示时自动切"）：本仓封面展示位全是 16:9 +
 * `object-fit: cover`，非 16:9 的图会被展示端默默切掉上下（或左右）。投稿页原先只显示
 * 一张 160×90 的缩略图，作者看不出被切了什么 ⇒ 这里把"切哪一刀"交还给作者。
 *
 * <h2>几何全在 `api/coverCrop.ts`</h2>
 * 组件只做三件事：量舞台、把指针位移 / 缩放倍数喂给纯函数、导出 canvas。
 * 拖动与缩放的物理含义（含"以光标为锚"）由单测复算，组件里没有第二套数学。
 *
 * <h2>导出</h2>
 * 1280×720 JPEG（质量 0.92）。先铺白底 —— JPEG 没有透明通道，PNG 透明区不铺底会落成黑块。
 * 产物文件名走 `coverFileName`（`photo.png` → `photo-cover.jpg`）：它要参与
 * `fileKey` / `draftSignature`，名字与大小不能跟原图撞。
 */
const props = defineProps<{
  /** 源图地址（本地文件用 object URL，编辑页的已上传封面用同源 `/upload/**`） */
  sourceUrl: string | null
  /** 源文件名（决定裁剪产物名与扩展名） */
  fileName: string
  /** 标题（视频 / 图文用同一句也可以传空） */
  heading?: string
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'cropped', file: File): void
}>()

const stage = ref<HTMLDivElement | null>(null)
const dialog = ref<HTMLElement | null>(null)
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
const layout = computed(() => stageLayout(imageSize.value, stageSize.value, state.value))
const zoomPercent = computed(() => Math.round(state.value.zoom * 100))
const exportingLabel = computed(() => (exporting.value ? '处理中…' : '使用这张封面'))

/** 源图在舞台上的绘制盒：宽高按 scale 放大，位置由 offset 决定（舞台 overflow: hidden） */
const imageStyle = computed(() => ({
  width: `${imageSize.value.width * layout.value.scale}px`,
  height: `${imageSize.value.height * layout.value.scale}px`,
  transform: `translate(${layout.value.offsetX}px, ${layout.value.offsetY}px)`,
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

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  }
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
  state.value = dragBy(imageSize.value, stageSize.value, state.value, dx, dy)
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
  state.value = zoomAround(
    imageSize.value,
    stageSize.value,
    state.value,
    next,
    { x: event.clientX - box.left, y: event.clientY - box.top },
  )
}

/** 滑杆没有"光标位置"⇒ 锚在舞台正中（等比放大，构图不跑） */
function onZoomInput(event: Event): void {
  const value = Number((event.target as HTMLInputElement).value)
  state.value = zoomAround(
    imageSize.value,
    stageSize.value,
    state.value,
    clampZoom(value),
    { x: stageSize.value.width / 2, y: stageSize.value.height / 2 },
  )
}

// ==================== 导出 ====================

async function confirm(): Promise<void> {
  const source = image.value
  if (!source || !loaded.value) {
    // 不静默返回：图还没解码完就点确认时，要让用户看到"还没准备好"，而不是以为按钮坏了
    loadError.value = '图片还没准备好，请稍等再试'
    return
  }
  if (exporting.value) return
  exporting.value = true
  try {
    const rect = cropRect(imageSize.value, state.value)
    const canvas = document.createElement('canvas')
    canvas.width = COVER_OUTPUT_WIDTH
    canvas.height = COVER_OUTPUT_HEIGHT
    const context = canvas.getContext('2d')
    if (!context) throw new Error('canvas unavailable')
    // 白底：JPEG 无 alpha，不铺底会把 PNG 的透明区落成黑块
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
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
    if (!blob) throw new Error('toBlob failed')
    emit(
      'cropped',
      new File([blob], coverFileName(props.fileName), {
        type: 'image/jpeg',
        lastModified: Date.now(),
      }),
    )
  } catch {
    // 跨域图会让 canvas 变 tainted（toBlob 抛 SecurityError）——说清楚，别假装成功
    loadError.value = '这张图片无法裁切（跨域或格式不支持），请重新选择本地图片'
  } finally {
    exporting.value = false
  }
}

// ==================== 生命周期 ====================

watch(
  () => props.sourceUrl,
  async () => {
    if (!props.sourceUrl) return
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
    dialog.value?.focus()
  },
  { immediate: true },
)

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

onMounted(() => {
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKeydown)
  dialog.value?.focus()
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <Teleport to="body">
    <div class="dialog-mask" @click.self="close">
      <div
        ref="dialog"
        class="cropper"
        role="dialog"
        aria-modal="true"
        aria-labelledby="cropper-title"
        tabindex="-1"
      >
        <header class="cropper__head">
          <div>
            <h2 id="cropper-title" class="cropper__title">{{ heading || '裁剪封面' }}</h2>
            <p class="cropper__note">按 16:9 裁切：框里看到的就是卡片与播放器里看到的</p>
          </div>
          <button class="cropper__close" type="button" aria-label="关闭" @click="close">
            <AppIcon name="close" :size="18" />
          </button>
        </header>

        <div
          ref="stage"
          class="cropper__stage"
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
            class="cropper__image"
            :style="imageStyle"
            alt=""
            draggable="false"
            @load="onImageLoaded"
            @error="onImageError"
          />
          <!-- 三分线：帮作者把主体摆在交点上（不是装饰） -->
          <div v-if="loaded" class="cropper__grid" aria-hidden="true">
            <i class="cropper__grid-v cropper__grid-v--1"></i>
            <i class="cropper__grid-v cropper__grid-v--2"></i>
            <i class="cropper__grid-h cropper__grid-h--1"></i>
            <i class="cropper__grid-h cropper__grid-h--2"></i>
          </div>
          <p v-if="!loaded && !loadError" class="cropper__loading">正在读取图片…</p>
          <p v-if="loadError" class="cropper__error" role="alert">{{ loadError }}</p>
        </div>

        <div class="cropper__controls">
          <div class="cropper__zoom">
            <span class="cropper__zoom-icon" aria-hidden="true">−</span>
            <input
              class="cropper__range"
              type="range"
              :min="COVER_MIN_ZOOM"
              :max="COVER_MAX_ZOOM"
              step="0.01"
              :value="state.zoom"
              :disabled="!loaded"
              aria-label="裁剪缩放"
              @input="onZoomInput"
            />
            <span class="cropper__zoom-icon" aria-hidden="true">＋</span>
            <span class="cropper__zoom-value">{{ zoomPercent }}%</span>
          </div>
          <div class="cropper__tips">
            <span>拖动图片调整位置</span>
            <button class="link-btn" type="button" :disabled="!loaded" @click="reset">还原</button>
          </div>
        </div>

        <footer class="cropper__foot">
          <p class="cropper__output">
            将保存为 {{ COVER_OUTPUT_WIDTH }}×{{ COVER_OUTPUT_HEIGHT }} JPG（{{ coverFileName(fileName) }}）
          </p>
          <div class="cropper__actions">
            <button class="btn btn-ghost" type="button" :disabled="exporting" @click="close">取消</button>
            <button class="btn btn-blue" type="button" :disabled="!loaded || exporting" @click="confirm">
              {{ exportingLabel }}
            </button>
          </div>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.cropper {
  width: 100%;
  max-width: 760px;
  padding: 18px 20px 16px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  outline: none;
}

.cropper__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.cropper__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.cropper__note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.cropper__close {
  display: flex;
  width: 28px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-3);
}

.cropper__close:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

/* 舞台恒 16:9：它就是"实际展示"的比例 */
.cropper__stage {
  position: relative;
  width: 100%;
  margin-top: 14px;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: var(--radius);
  background: #0f0f10;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.cropper__stage.is-dragging {
  cursor: grabbing;
}

.cropper__image {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none;
  transform-origin: 0 0;
  will-change: transform;
}

.cropper__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.28);
}

.cropper__grid-v,
.cropper__grid-h {
  position: absolute;
  background: rgba(255, 255, 255, 0.18);
}

.cropper__grid-v {
  top: 0;
  bottom: 0;
  width: 1px;
}

.cropper__grid-h {
  right: 0;
  left: 0;
  height: 1px;
}

.cropper__grid-v--1 {
  left: 33.333%;
}

.cropper__grid-v--2 {
  left: 66.666%;
}

.cropper__grid-h--1 {
  top: 33.333%;
}

.cropper__grid-h--2 {
  top: 66.666%;
}

.cropper__loading,
.cropper__error {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 24px;
  font-size: 13px;
  text-align: center;
}

.cropper__loading {
  color: rgba(255, 255, 255, 0.7);
}

.cropper__error {
  color: #ffb4b4;
}

.cropper__controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-3);
}

.cropper__zoom {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cropper__zoom-icon {
  width: 12px;
  text-align: center;
  color: var(--text-3);
}

.cropper__range {
  width: 180px;
  accent-color: var(--brand-blue);
}

.cropper__zoom-value {
  min-width: 42px;
  font-variant-numeric: tabular-nums;
  color: var(--text-2);
}

.cropper__tips {
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

.cropper__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line-2);
}

.cropper__output {
  font-size: 12px;
  color: var(--text-3);
}

.cropper__actions {
  display: flex;
  flex: none;
  gap: 10px;
}

@media (max-width: 640px) {
  .cropper__controls,
  .cropper__foot {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .cropper__range {
    width: 140px;
  }
}
</style>
