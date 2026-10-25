<script setup lang="ts">
import type { VideoCardVM } from '../../types/ui'
import SkeletonCard from '../common/SkeletonCard.vue'
import VideoCard from '../common/VideoCard.vue'

withDefaults(
  defineProps<{ items: VideoCardVM[]; loading?: boolean; skeletonCount?: number }>(),
  { loading: false, skeletonCount: 11 },
)
</script>

<template>
  <div class="video-grid">
    <!-- 首页的大轮播从 slot 进来（它自己声明 span 2×2），其余页面不用 -->
    <slot />
    <VideoCard v-for="item in items" :key="item.id" :item="item" />
    <template v-if="loading">
      <SkeletonCard v-for="n in skeletonCount" :key="`skeleton-${n}`" />
    </template>
  </div>
</template>

<style scoped>
.video-grid {
  display: grid;
  grid-template-columns: repeat(var(--grid-cols), minmax(0, 1fr));
  gap: 24px var(--grid-gap);
  align-items: start;
}
</style>
