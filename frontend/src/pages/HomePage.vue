<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { loadHome } from '../api/endpoints'
import { ApiError } from '../api/http'
import type { HeroSlideVM, VideoCardVM } from '../types/ui'
import EmptyState from '../components/common/EmptyState.vue'
import AppIcon from '../components/common/AppIcon.vue'
import ChannelWall from '../components/layout/ChannelWall.vue'
import HeroBanner from '../components/home/HeroBanner.vue'
import VideoGrid from '../components/home/VideoGrid.vue'

/**
 * 首页闭环：`GET /contents/recommended`（固定 12 条、无分页 ⇒ **不做无限滚动**）。
 *
 * <p>12 条的分配：前 3 条有封面的进大轮播（「推荐橱窗」，见 HeroBanner 注释），
 * 其余 9 条进 5 列网格 —— 同一内容不会重复出现。
 */
const slides = ref<HeroSlideVM[]>([])
const cards = ref<VideoCardVM[]>([])
const loading = ref(true)
const error = ref('')

/** 只有「首屏还没有任何数据」才铺骨架；点「换一换」时保留旧内容，不闪屏 */
const initialLoading = computed(() => loading.value && !cards.value.length && !slides.value.length && !error.value)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await loadHome()
    slides.value = data.slides
    cards.value = data.cards
  } catch (exception) {
    error.value = exception instanceof ApiError ? exception.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(load)
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
          class="home__refresh"
          type="button"
          :disabled="loading"
          title="换一换：重新拉取推荐流（真接口，顺序每次不同）"
          @click="load"
        >
          <AppIcon name="refresh" :size="20" :class="{ 'is-spinning': loading }" />
          <span>换一换</span>
        </button>

        <VideoGrid
          :items="cards"
          :loading="initialLoading"
        >
          <HeroBanner v-if="slides.length" :slides="slides" />
          <div v-else-if="initialLoading" class="home__hero-skeleton skeleton" />
        </VideoGrid>

        <EmptyState
          v-if="error"
          icon="tv"
          title="内容加载失败"
          :description="error"
        >
          <button class="btn btn-blue" type="button" @click="load">重试</button>
        </EmptyState>

        <EmptyState
          v-else-if="!loading && !cards.length && !slides.length"
          icon="tv"
          title="还没有内容"
          description="后端返回的推荐流是空的。可以先去投稿页发布第一条内容（投稿页在后续窗口交付）。"
        >
          <a class="btn btn-primary" href="/placeholder/upload">去投稿</a>
        </EmptyState>
      </div>
    </div>
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

@media (max-width: 1500px) {
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
</style>
