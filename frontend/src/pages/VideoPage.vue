<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { VideoPageData } from '../api/endpoints'
import { loadVideoPage } from '../api/endpoints'
import { ApiError } from '../api/http'
import EmptyState from '../components/common/EmptyState.vue'
import AppIcon from '../components/common/AppIcon.vue'
import VideoCard from '../components/common/VideoCard.vue'

/**
 * 播放页（**只读**）：`GET /contents/{id}` +（并行）`GET /users/{id}` + `GET /contents/recommended`。
 *
 * <p>★ 详情侧**有**五个计数，与列表侧（无计数）相反 —— 所以这里显示真数字。
 * 点赞 / 投币 / 收藏 / 评论 / 弹幕 / 播放进度上报都**不在本窗口**：
 * 弹幕是永远占位，其余在后续窗口接（不摆一排点了没反应的按钮）。
 */
const route = useRoute()

const id = computed(() => {
  const raw = Number(route.params.id)
  return Number.isInteger(raw) && raw > 0 ? raw : 0
})

const data = ref<VideoPageData | null>(null)
const loading = ref(true)
const error = ref('')

async function load(): Promise<void> {
  if (!id.value) {
    error.value = '地址里的内容 id 不合法'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    data.value = await loadVideoPage(id.value)
  } catch (exception) {
    data.value = null
    if (exception instanceof ApiError) {
      error.value =
        exception.status === 404 ? '内容不存在或已被作者删除' : exception.message
    } else {
      error.value = '加载失败，请稍后重试'
    }
  } finally {
    loading.value = false
  }
}

watch(id, load, { immediate: true })
</script>

<template>
  <main class="video-page page-content page-main">
    <div v-if="loading" class="video-loading">
      <div class="skeleton video-loading__player" />
      <div class="skeleton video-loading__line" />
      <div class="skeleton video-loading__line video-loading__line--short" />
    </div>

    <EmptyState v-else-if="error" icon="tv" title="打不开这个页面" :description="error">
      <a class="btn btn-primary" href="/">回首页</a>
    </EmptyState>

    <div v-else-if="data" class="video-layout">
      <div class="video-main">
        <div class="player">
          <video
            v-if="data.detail.videoUrl"
            class="player__video"
            :src="data.detail.videoUrl"
            :poster="data.detail.coverUrl ?? undefined"
            controls
            preload="metadata"
            playsinline
          />
          <img
            v-else-if="data.detail.images.length"
            class="player__image"
            :src="data.detail.images[0]"
            :alt="data.detail.title"
          />
          <span v-else class="player__empty">这个内容没有可播放的媒体</span>
        </div>

        <!-- 弹幕：永远占位（§五：播放页无弹幕层，卡片不显示弹幕数） -->
        <div class="danmaku-bar" title="本仓没有弹幕域（无表 / 无端点 / 无 WS）">
          <AppIcon name="danmaku" :size="18" />
          <span>弹幕功能未适配：本仓没有弹幕域，按专项边界永远占位</span>
        </div>
        <h1 class="video-title">{{ data.detail.title }}</h1>

        <div class="video-stats">
          <span class="video-stats__item"><AppIcon name="play" :size="16" />{{ data.detail.stats.play }}</span>
          <span class="video-stats__item"><AppIcon name="thumb" :size="16" />{{ data.detail.stats.like }}</span>
          <span class="video-stats__item"><AppIcon name="coin" :size="16" />{{ data.detail.stats.coin }}</span>
          <span class="video-stats__item"><AppIcon name="star" :size="16" />{{ data.detail.stats.favorite }}</span>
          <span class="video-stats__item"><AppIcon name="comment" :size="16" />{{ data.detail.stats.comment }}</span>
          <span class="video-stats__date">{{ data.detail.dateText }}</span>
        </div>

        <section class="video-desc">
          <h2 class="video-desc__head">简介</h2>
          <p class="video-desc__text">{{ data.detail.description || '这个内容还没有简介' }}</p>
          <p class="video-desc__meta">
            类型：{{ data.detail.isPost ? '图文动态' : '视频' }}
            <template v-if="data.detail.durationText"> · 时长 {{ data.detail.durationText }}</template>
            <template v-if="data.detail.isPost && data.detail.images.length"> · {{ data.detail.images.length }} 张图片</template>
          </p>
        </section>

        <section v-if="data.detail.isPost && data.detail.images.length > 1" class="image-list">
          <img
            v-for="(image, index) in data.detail.images.slice(1)"
            :key="image"
            class="image-list__item"
            :src="image"
            :alt="`${data.detail.title} 图 ${index + 2}`"
            loading="lazy"
          />
        </section>

        <section class="comments-placeholder">
          <h2 class="comments-placeholder__head">评论</h2>
          <p class="comments-placeholder__text">
            评论区在后续窗口接入（评论分页与楼中楼后端都已就绪）。
          </p>
        </section>
      </div>

      <aside class="video-side">
        <section v-if="data.author" class="author-card">
          <a class="author-card__avatar" :href="data.author.href" :title="`${data.author.name} 的空间（占位页）`">
            {{ data.author.initial }}
          </a>
          <div class="author-card__body">
            <a class="author-card__name" :href="data.author.href">{{ data.author.name }}</a>
            <p class="author-card__meta">
              粉丝 {{ data.author.followerText }} · 关注 {{ data.author.followingText }} · 投稿 {{ data.author.worksText }}
            </p>
          </div>
          <span class="author-card__tag">空间占位</span>
        </section>

        <section v-if="data.related.length" class="related">
          <h2 class="related__title">相关推荐</h2>
          <div class="related__list">
            <VideoCard v-for="item in data.related" :key="item.id" :item="item" layout="row" />
          </div>
        </section>
      </aside>
    </div>
  </main>
</template>

<style scoped>
.video-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 350px;
  gap: 32px;
  align-items: start;
}

.player {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: var(--radius);
  background: #000;
}

.player__video {
  width: 100%;
  height: 100%;
}

.player__image {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}

.player__empty {
  font-size: 13px;
  color: var(--text-3);
}

.danmaku-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 0 14px;
  height: 38px;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-3);
  background: var(--bg-1);
}

.video-title {
  margin-top: 16px;
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  color: var(--text-1);
  word-break: break-word;
}

.video-stats {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 12px;
  font-size: 13px;
  color: var(--text-3);
}

.video-stats__item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}

.video-stats__date {
  margin-left: auto;
  color: var(--text-3);
}

.video-desc {
  margin-top: 18px;
  padding: 14px 16px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.video-desc__head {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}

.video-desc__text {
  margin-top: 8px;
  font-size: 14px;
  line-height: 22px;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.video-desc__meta {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-3);
}

.image-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.image-list__item {
  width: 100%;
  border-radius: var(--radius-sm);
}

.comments-placeholder {
  margin-top: 24px;
  padding: 18px 16px;
  border: 1px dashed var(--line-1);
  border-radius: var(--radius);
}

.comments-placeholder__head {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.comments-placeholder__text {
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-3);
}

.video-side {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.author-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.author-card__avatar {
  display: flex;
  width: 48px;
  height: 48px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
}

.author-card__body {
  min-width: 0;
}

.author-card__name {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.author-card__meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-3);
}

.author-card__tag {
  margin-left: auto;
  flex: none;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-size: 11px;
  color: var(--text-3);
  background: var(--bg-0);
}

.related__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.related__list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 12px;
}

.video-loading__player {
  aspect-ratio: 16 / 9;
  border-radius: var(--radius);
}

.video-loading__line {
  height: 20px;
  margin-top: 16px;
}

.video-loading__line--short {
  width: 40%;
}

@media (max-width: 1100px) {
  .video-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
