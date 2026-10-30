<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { gridColumns, gridPreview, isEditableTarget, rotateBy, stepIndex } from '../../api/opusImages'
import AppIcon from '../common/AppIcon.vue'

/**
 * 图文详情的图片区：**九宫格 ↔ 放大态** 两种形态，点图片 / 收起互相切换。
 *
 * <h2>两种形态（B 站 2026-10 实拍）</h2>
 * <ul>
 *   <li><b>九宫格（默认 / 未放大）</b>：1 张单图、2 张两列、3 张及以上三列；最多平铺 9 张，
 *       第 9 张盖 `+N` 角标（多出来的去放大态看）；</li>
 *   <li><b>放大态</b>：工具条（收起 / 查看大图 / 向左旋转 / 向右旋转）+ 一张大图 +
 *       底部缩小平铺的缩略图条；点缩略图切换，左右方向键也能切。</li>
 * </ul>
 *
 * <p>「查看大图」再叠一层全屏 lightbox（与放大态共用当前索引 / 旋转角），Esc 关闭。
 * <p>全部是**前端交互**：图片 URL 来自 `ContentDetailVO.imageUrls`，没有新接口。
 * <p>图片 404 / 解码失败 ⇒ 灰底 + 文档图标占位（与卡片封面同一口径，不画破图）。
 */
const props = defineProps<{ images: string[]; title: string }>()

const mode = ref<'grid' | 'zoom'>('grid')
const activeIndex = ref(0)
const rotation = ref(0)
const lightboxOpen = ref(false)
const lightboxEl = ref<HTMLElement | null>(null)
const stripEl = ref<HTMLElement | null>(null)
const lightboxStripEl = ref<HTMLElement | null>(null)
/** 「查看大图」的触发按钮：关闭 lightbox 后把焦点还给它 */
let lightboxTrigger: HTMLElement | null = null
/** 已经加载失败的图片 URL（同一 URL 在网格 / 大图 / 缩略图里都走占位） */
const broken = reactive(new Set<string>())

const preview = computed(() => gridPreview(props.images))
const columns = computed(() => gridColumns(props.images.length))
const currentImage = computed(() => props.images[activeIndex.value] ?? '')

function markBroken(url: string): void {
  broken.add(url)
}

/** 点九宫格里的某张 ⇒ 进放大态，并定位到这张 */
function openZoom(index: number): void {
  activeIndex.value = index
  rotation.value = 0
  mode.value = 'zoom'
}

/** 收起：回到九宫格（同时关掉 lightbox） */
function collapse(): void {
  lightboxOpen.value = false
  rotation.value = 0
  mode.value = 'grid'
}

/** 打开全屏查看器；记下触发按钮，关闭后把焦点还回去 */
function openLightbox(): void {
  lightboxTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  lightboxOpen.value = true
}

/** 全屏查看器的焦点陷阱：Tab / Shift+Tab 只在弹层内循环 */
function trapLightboxFocus(event: KeyboardEvent): void {
  const root = lightboxEl.value
  if (!root) return
  const focusables = [
    ...root.querySelectorAll<HTMLElement>('button, [href], [tabindex]:not([tabindex="-1"])'),
  ]
  if (!focusables.length) return
  const first = focusables[0]
  const last = focusables[focusables.length - 1]
  if (document.activeElement === root) {
    event.preventDefault()
    const next = event.shiftKey ? last : first
    next.focus()
    return
  }
  if (!root.contains(document.activeElement)) {
    event.preventDefault()
    first.focus()
    return
  }
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

/** 放大态里切换缩略图：旋转角归零（每张图各自从正向看起） */
function select(index: number): void {
  activeIndex.value = index
  rotation.value = 0
}

function rotate(delta: number): void {
  rotation.value = rotateBy(rotation.value, delta)
}

function onKeydown(event: KeyboardEvent): void {
  const target = event.target
  // 评论框里打字时 ←/→ / Esc 不能抢（否则切图 + 收起，用户完全没法写评论）
  if (target instanceof HTMLElement && isEditableTarget(target.tagName, target.isContentEditable)) {
    return
  }
  if (lightboxOpen.value && event.key === 'Tab') {
    trapLightboxFocus(event)
    return
  }
  if (event.key === 'Escape') {
    if (lightboxOpen.value) {
      lightboxOpen.value = false
      return
    }
    if (mode.value === 'zoom') {
      collapse()
      return
    }
  }
  if (mode.value !== 'zoom') return
  if (event.key === 'ArrowLeft') {
    select(stepIndex(activeIndex.value, -1, props.images.length))
  } else if (event.key === 'ArrowRight') {
    select(stepIndex(activeIndex.value, 1, props.images.length))
  }
}

// lightbox 打开时锁滚动 + 聚焦弹层；关闭后把焦点还给触发按钮
watch(lightboxOpen, async (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
  if (open) {
    await nextTick()
    lightboxEl.value?.focus()
  } else {
    lightboxTrigger?.focus()
    lightboxTrigger = null
  }
})

// 方向键 / 缩略图切换后，让当前缩略图留在可视区（不把选中项滚出屏幕）
watch(activeIndex, async () => {
  await nextTick()
  stripEl.value
    ?.querySelector<HTMLElement>('.opus-images__thumb.is-active')
    ?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  lightboxStripEl.value
    ?.querySelector<HTMLElement>('.opus-lightbox__thumb.is-active')
    ?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
})

// 换内容（/opus/91 → /opus/92）时回到九宫格第一张
watch(
  () => props.images,
  () => {
    mode.value = 'grid'
    activeIndex.value = 0
    rotation.value = 0
    lightboxOpen.value = false
  },
)

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})
</script>

<template>
  <section v-if="images.length" class="opus-images" aria-label="图文图片">
    <template v-if="mode === 'grid'">
      <div class="opus-images__grid" :class="`opus-images__grid--${columns}`">
        <button
          v-for="(image, index) in preview.shown"
          :key="`${image}-${index}`"
          class="opus-images__tile"
          type="button"
          :aria-label="
            preview.overflow > 0 && index === preview.shown.length - 1
              ? `查看第 ${index + 1} 张图片（还有 ${preview.overflow} 张）`
              : `查看第 ${index + 1} 张图片`
          "
          @click="openZoom(index)"
        >
          <img
            v-if="!broken.has(image)"
            :src="image"
            :alt="`${title} 图 ${index + 1}`"
            loading="lazy"
            decoding="async"
            @error="markBroken(image)"
          />
          <span v-else class="opus-images__broken" aria-hidden="true">
            <AppIcon name="article" :size="24" />
          </span>
          <span v-if="preview.overflow > 0 && index === preview.shown.length - 1" class="opus-images__more">
            +{{ preview.overflow }}
          </span>
        </button>
      </div>
    </template>

    <template v-else>
      <div class="opus-images__toolbar">
        <button class="opus-images__tool" type="button" @click="collapse">
          <AppIcon name="chevron-left" :size="16" />
          <span>收起</span>
        </button>
        <button class="opus-images__tool" type="button" @click="openLightbox">
          <AppIcon name="search" :size="16" />
          <span>查看大图</span>
        </button>
        <button class="opus-images__tool" type="button" @click="rotate(-90)">
          <AppIcon name="rotate-left" :size="16" />
          <span>向左旋转</span>
        </button>
        <button class="opus-images__tool" type="button" @click="rotate(90)">
          <AppIcon name="rotate-right" :size="16" />
          <span>向右旋转</span>
        </button>
      </div>

      <div class="opus-images__stage">
        <img
          v-if="currentImage && !broken.has(currentImage)"
          class="opus-images__large"
          :src="currentImage"
          :alt="`${title} 图 ${activeIndex + 1}`"
          :style="{ transform: `rotate(${rotation}deg)` }"
          @error="markBroken(currentImage)"
        />
        <span v-else class="opus-images__broken opus-images__broken--large" aria-hidden="true">
          <AppIcon name="article" :size="36" />
        </span>
      </div>

      <div ref="stripEl" class="opus-images__strip" role="tablist" aria-label="图片缩略图">
        <button
          v-for="(image, index) in images"
          :key="`thumb-${image}-${index}`"
          class="opus-images__thumb"
          :class="{ 'is-active': index === activeIndex }"
          type="button"
          role="tab"
          :aria-selected="index === activeIndex"
          :aria-label="`第 ${index + 1} 张图片`"
          @click="select(index)"
        >
          <img v-if="!broken.has(image)" :src="image" alt="" loading="lazy" decoding="async" @error="markBroken(image)" />
          <span v-else class="opus-images__broken" aria-hidden="true">
            <AppIcon name="article" :size="16" />
          </span>
        </button>
      </div>
    </template>

    <Teleport to="body">
      <div
        v-if="lightboxOpen"
        ref="lightboxEl"
        class="opus-lightbox"
        role="dialog"
        aria-modal="true"
        aria-label="查看大图"
        tabindex="-1"
        @click.self="lightboxOpen = false"
      >
        <div class="opus-lightbox__bar">
          <button class="opus-lightbox__btn" type="button" @click="rotate(-90)">
            <AppIcon name="rotate-left" :size="16" />
            <span>向左旋转</span>
          </button>
          <button class="opus-lightbox__btn" type="button" @click="rotate(90)">
            <AppIcon name="rotate-right" :size="16" />
            <span>向右旋转</span>
          </button>
          <button class="opus-lightbox__btn" type="button" @click="lightboxOpen = false">
            <AppIcon name="close" :size="16" />
            <span>关闭</span>
          </button>
        </div>

        <img
          v-if="currentImage && !broken.has(currentImage)"
          class="opus-lightbox__img"
          :src="currentImage"
          :alt="`${title} 图 ${activeIndex + 1}`"
          :style="{ transform: `rotate(${rotation}deg)` }"
          @error="markBroken(currentImage)"
        />
        <span v-else class="opus-images__broken opus-images__broken--lightbox" aria-hidden="true">
          <AppIcon name="article" :size="40" />
        </span>

        <div ref="lightboxStripEl" class="opus-lightbox__strip" role="tablist" aria-label="图片缩略图">
          <button
            v-for="(image, index) in images"
            :key="`light-${image}-${index}`"
            class="opus-lightbox__thumb"
            :class="{ 'is-active': index === activeIndex }"
            type="button"
            role="tab"
            :aria-selected="index === activeIndex"
            :aria-label="`第 ${index + 1} 张图片`"
            @click="select(index)"
          >
            <img v-if="!broken.has(image)" :src="image" alt="" loading="lazy" decoding="async" @error="markBroken(image)" />
          </button>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.opus-images {
  margin: 8px 0 24px;
}

/* ---------- 九宫格 ---------- */
.opus-images__grid {
  display: grid;
  gap: 8px;
}

.opus-images__grid--1 {
  grid-template-columns: minmax(0, 420px);
}

.opus-images__grid--2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.opus-images__grid--3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.opus-images__tile {
  position: relative;
  display: block;
  aspect-ratio: 1 / 1;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-2);
}

.opus-images__grid--1 .opus-images__tile {
  aspect-ratio: 16 / 10;
}

.opus-images__tile img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.25s ease;
}

.opus-images__tile:hover img {
  transform: scale(1.03);
}

.opus-images__more {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 600;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.5);
}

/* ---------- 放大态工具条 ---------- */
.opus-images__toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-bottom: 10px;
  padding: 6px 8px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.opus-images__tool {
  display: flex;
  height: 30px;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--text-2);
  transition: color 0.15s ease, background-color 0.15s ease;
}

.opus-images__tool:hover {
  color: var(--brand-blue);
  background: var(--bg-0);
}

/* ---------- 放大态：大图 + 缩小平铺 ---------- */
.opus-images__stage {
  display: flex;
  min-height: 180px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-0);
}

.opus-images__large {
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
  transition: transform 0.2s ease;
}

.opus-images__strip {
  display: flex;
  gap: 6px;
  margin-top: 10px;
  padding: 2px;
  overflow-x: auto;
}

.opus-images__thumb {
  width: 64px;
  height: 48px;
  flex: none;
  overflow: hidden;
  border: 2px solid transparent;
  border-radius: var(--radius-sm);
  background: var(--bg-2);
}

.opus-images__thumb.is-active {
  border-color: var(--brand-blue);
}

.opus-images__thumb img,
.opus-images__thumb .opus-images__broken {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* ---------- 失败占位（不画破图） ---------- */
.opus-images__broken {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-4);
  background: var(--bg-2);
}

.opus-images__broken--large {
  min-height: 180px;
}

.opus-images__broken--lightbox {
  width: 60vw;
  height: 50vh;
  background: rgba(255, 255, 255, 0.08);
}

/* ---------- 全屏 lightbox ---------- */
.opus-lightbox {
  position: fixed;
  inset: 0;
  /* 与收藏 / 投币 / 登录弹层同层（`.dialog-mask` 也是 1000） */
  z-index: 1000;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px 16px 16px;
  background: rgba(0, 0, 0, 0.9);
}

.opus-lightbox__bar {
  position: absolute;
  top: 16px;
  right: 20px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.opus-lightbox__btn {
  display: flex;
  height: 32px;
  align-items: center;
  gap: 4px;
  padding: 0 10px;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-inverse);
}

.opus-lightbox__btn:hover {
  background: rgba(255, 255, 255, 0.14);
}

.opus-lightbox__img {
  max-width: 92vw;
  max-height: 78vh;
  object-fit: contain;
  transition: transform 0.2s ease;
}

.opus-lightbox__strip {
  display: flex;
  max-width: 92vw;
  gap: 6px;
  padding: 4px;
  overflow-x: auto;
}

.opus-lightbox__thumb {
  width: 52px;
  height: 40px;
  flex: none;
  overflow: hidden;
  border: 2px solid transparent;
  border-radius: var(--radius-sm);
  background: var(--bg-2);
  opacity: 0.55;
}

.opus-lightbox__thumb.is-active {
  border-color: var(--brand-pink);
  opacity: 1;
}

.opus-lightbox__thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 窄屏：九宫格退成两列（手机上的三列会窄到看不清） */
@media (max-width: 600px) {
  .opus-images__grid--3 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
