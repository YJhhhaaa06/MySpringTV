<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRecommendFeed } from '../composables/useRecommendFeed'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import HeroBanner from '../components/home/HeroBanner.vue'
import HomeFloatingActions from '../components/home/HomeFloatingActions.vue'
import VideoGrid from '../components/home/VideoGrid.vue'
import ChannelWall from '../components/layout/ChannelWall.vue'

/**
 * 首页：`GET /contents/recommended` 的**无限滚动**（游标信封）。
 *
 * <p>分工：状态迁移在 `feed/listState.ts`（纯函数），请求 / 提示在
 * `composables/useRecommendFeed`；本页只管 DOM —— 哨兵、骨架、到底文案、两个「换一换」。
 *
 * <p>★ 三个容易写错的点：
 * <ol>
 *   <li>轮播只在**第一页**拆（`loadHome` 负责）；续页全进网格；</li>
 *   <li>`nextCursor === null` 是**唯一**的到底判据（不按"本页不满"猜）；</li>
 *   <li>首屏空（池子真空 / 读失败同形）走空态，**不是**"刷到底"。</li>
 * </ol>
 */
const feed = useRecommendFeed()
const { state, slides, view, errorText, retryAfter, canRetry } = feed

/** 离底还有 800px 就开始拉下一页：滚到底时下一页通常已经在路上 */
const PREFETCH_MARGIN = 800

const sentinel = ref<HTMLElement | null>(null)
/** 内联「换一换」：它的可见性决定右下浮层出不出现（`display:none` 时 IO 报 false） */
const inlineRefresh = ref<HTMLButtonElement | null>(null)
const inlineVisible = ref(true)

const initialLoading = computed(() => view.value === 'initial')
const loadingMore = computed(() => view.value === 'loadingMore')
const refreshing = computed(() => view.value === 'initial' || view.value === 'refreshing')
const skeletons = computed(() => (initialLoading.value ? 11 : 10))
const retryLabel = computed(() => (canRetry.value ? '重试' : `${retryAfter.value} 秒后再试`))
/**
 * "有没有可展示的内容"要**算上轮播**：池子只有 1~3 条且都有封面时，`splitHero` 会把它们
 * 全抽进轮播、`cards` 为空 —— 只按 `items` 判会在这三张真轮播下面写「暂时没有内容」。
 */
const hasContent = computed(() => state.value.items.length > 0 || slides.value.length > 0)

let sentinelObserver: IntersectionObserver | null = null
let inlineObserver: IntersectionObserver | null = null

function inPrefetchRange(): boolean {
  const element = sentinel.value
  // 卸载后元素已脱离文档，rect 恒 0 ⇒ 不加这一句会让 while 继续把整池拉完
  if (!element?.isConnected) return false
  return element.getBoundingClientRect().top <= window.innerHeight + PREFETCH_MARGIN
}

/**
 * 连续补页：短页 / 大视口下哨兵可能仍留在触发区，而 IntersectionObserver 不会因为
 * "元素还在视口里"再报一次。补到哨兵被推出触发区、到底、或失败为止。
 * 熔断在纯函数里（空页 + 游标非空 ⇒ error），这里不会死循环。
 */
async function loadMore(): Promise<void> {
  while (feed.shouldLoadMore() && inPrefetchRange()) {
    await feed.loadMore()
    await nextTick()
  }
}

/** 首屏 / 换一批 + 补一次"首屏装不满"的续页（小池子时 IO 不会二次触发） */
async function refresh(): Promise<void> {
  await feed.refresh()
  await nextTick()
  if (feed.shouldLoadMore() && inPrefetchRange()) void loadMore()
}

/** 行内重试 + 补一次预取：失败时哨兵可能仍在触发区，IO 不会因为"还在视口里"再报 */
async function retryLoadMore(): Promise<void> {
  await feed.retry()
  await nextTick()
  if (feed.shouldLoadMore() && inPrefetchRange()) void loadMore()
}

/** 内联按钮：**原地**换一批，不动滚动位置 */
function onInlineRefresh(): void {
  void refresh()
}

/** 浮层：**先回顶再拉**（列表整批替换，滚动动画越过变化中的内容只会更晕） */
function onFloatRefresh(): void {
  window.scrollTo({ top: 0, behavior: 'auto' })
  void refresh()
}

function backToTop(): void {
  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
  window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' })
}

onMounted(() => {
  void refresh()

  // 先按"真实的 display 状态"定初值：`display:none` 时 offsetParent 为 null。
  // 只靠 IO 的首次回调也行，但那一帧会让浮层闪一下（窄屏）/ 判断晚一拍。
  inlineVisible.value = inlineRefresh.value?.offsetParent !== null

  inlineObserver = new IntersectionObserver((entries) => {
    inlineVisible.value = entries[0]?.isIntersecting ?? false
  })
  if (inlineRefresh.value) inlineObserver.observe(inlineRefresh.value)

  sentinelObserver = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadMore()
    },
    { rootMargin: `0px 0px ${PREFETCH_MARGIN}px 0px` },
  )
  if (sentinel.value) sentinelObserver.observe(sentinel.value)
})

onBeforeUnmount(() => {
  inlineObserver?.disconnect()
  sentinelObserver?.disconnect()
})
</script>

<template>
  <div class="home">
    <!-- 顶部 banner：**纯装饰**（本仓没有运营位/广告端点，不假装是内容） -->
    <div class="home__banner">
      <div class="home__banner-inner">
        <span class="home__wordmark">bilibili</span>
        <span class="home__tagline">发现有趣的视频</span>
      </div>
      <span class="home__banner-icon" aria-hidden="true"><AppIcon name="tv" :size="150" /></span>
    </div>

    <div class="page-content">
      <ChannelWall />

      <div class="home__grid-wrap">
        <button
          ref="inlineRefresh"
          class="home__refresh"
          type="button"
          :disabled="refreshing"
          title="换一换：重新拉取推荐流（真接口，顺序每次不同）"
          @click="onInlineRefresh"
        >
          <AppIcon name="refresh" :size="20" :class="{ 'is-spinning': refreshing }" />
          <span>换一换</span>
        </button>

        <VideoGrid
          :items="state.items"
          :loading="initialLoading || loadingMore"
          :skeleton-count="skeletons"
        >
          <HeroBanner v-if="slides.length" :slides="slides" />
          <div v-else-if="initialLoading" class="home__hero-skeleton skeleton" />
        </VideoGrid>

        <!-- 行内失败：只影响"下一页"，已加载的内容留着 -->
        <div v-if="view === 'errorMore'" class="home__more-error">
          <span>{{ errorText }}</span>
          <button class="home__more-retry" type="button" :disabled="!canRetry" @click="retryLoadMore">
            {{ retryLabel }}
          </button>
        </div>

        <!-- 到底：会话级文案；块里不放按钮（此时右下浮层一定在）。
             池子只有 1~3 条时 items 为空、内容全在轮播里 —— 仍算到底，不算空 -->
        <p v-if="(view === 'done' || view === 'empty') && hasContent" class="home__done">
          这次的推荐到底了
        </p>

        <EmptyState
          v-if="view === 'error'"
          icon="tv"
          title="内容加载失败"
          :description="errorText"
        >
          <button class="btn btn-blue" type="button" @click="refresh">重试</button>
        </EmptyState>

        <EmptyState
          v-else-if="view === 'empty' && !hasContent"
          icon="tv"
          title="暂时没有内容"
          description="刷新试试"
        >
          <button class="btn btn-blue" type="button" @click="refresh">刷新</button>
        </EmptyState>

        <!-- 哨兵：1px，必须在所有内容之后 -->
        <div ref="sentinel" class="home__sentinel" aria-hidden="true" />
      </div>
    </div>

    <HomeFloatingActions
      v-if="!inlineVisible"
      :refreshing="refreshing"
      @refresh="onFloatRefresh"
      @top="backToTop"
    />
  </div>
</template>

<style scoped>
.home__banner {
  position: relative;
  height: 170px;
  overflow: hidden;
  background:
    radial-gradient(900px 260px at 12% 130%, rgba(255, 255, 255, 0.4), transparent 62%),
    linear-gradient(100deg, #ff9db5 0%, #b3a5ff 52%, #7fd7ff 100%);
}

.home__banner-inner {
  position: absolute;
  bottom: 22px;
  left: 40px;
  display: flex;
  align-items: baseline;
  gap: 14px;
  color: #fff;
}

.home__wordmark {
  font-size: 42px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -1.5px;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.12);
}

.home__tagline {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.9);
}

.home__banner-icon {
  position: absolute;
  right: 48px;
  bottom: 12px;
  color: rgba(255, 255, 255, 0.22);
}

.home__grid-wrap {
  position: relative;
  padding-top: 8px;
}

.home__hero-skeleton {
  grid-column: span 2;
  grid-row: span 2;
  height: 380px;
}

.home__refresh {
  position: absolute;
  top: 8px;
  right: -66px;
  display: flex;
  width: 56px;
  padding: 12px 0;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  font-size: 12px;
  color: var(--text-2);
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
  transition: color 0.15s ease, border-color 0.15s ease;
}

.home__refresh:hover:not(:disabled) {
  color: var(--brand-blue);
  border-color: var(--brand-blue);
}

.home__refresh:disabled {
  color: var(--text-4);
}

.is-spinning {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* 续页失败：行内重试，不自动重试（429 的"等多久"由 api 层翻译成文案） */
.home__more-error {
  display: flex;
  padding: 20px 0;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 13px;
  color: var(--text-3);
}

.home__more-retry {
  padding: 6px 14px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius-pill);
  font-size: 13px;
  color: var(--text-2);
  background: var(--bg-0);
  transition: color 0.15s ease, border-color 0.15s ease;
}

.home__more-retry:hover:not(:disabled) {
  color: var(--brand-blue);
  border-color: var(--brand-blue);
}

.home__more-retry:disabled {
  color: var(--text-4);
}

/* 到底：会话级（"这次的"），不承诺"全站已被你看完" */
.home__done {
  padding: 28px 0 8px;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}

.home__sentinel {
  height: 1px;
}

@media (max-width: 1500px) {
  /* 边距不够：内联按钮让位给右下浮层（浮层的显隐由它的可见性驱动） */
  .home__refresh {
    display: none;
  }
}

@media (max-width: 760px) {
  .home__banner {
    height: 130px;
  }

  .home__banner-inner {
    left: 20px;
    bottom: 16px;
  }

  .home__wordmark {
    font-size: 30px;
  }

  .home__hero-skeleton {
    height: 220px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning {
    animation: none;
  }
}
</style>
