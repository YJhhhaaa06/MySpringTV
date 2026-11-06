<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppIcon from '../common/AppIcon.vue'

/**
 * 收藏夹封面（`R-33` 的三种状态都由一个 `coverUrl` 表达）。
 *
 * <p>后端现算的生效封面：自定义 → 夹内最新一条收藏的封面 → `null`。
 * `null` / 破图 / 加载中统一露出灰底 TV 占位，**不拿假图顶上**；
 * 图未到时先显示占位，加载成功后淡入（与 `VideoCard` 的封面位同一口径）。
 */
const props = withDefaults(
  defineProps<{
    coverUrl: string | null
    /** 封面图是装饰性的：名称通常就在旁边，默认空 alt，避免读屏重复 */
    alt?: string
    iconSize?: number
  }>(),
  { alt: '', iconSize: 24 },
)

const broken = ref(false)
const loaded = ref(false)

watch(
  () => props.coverUrl,
  () => {
    broken.value = false
    loaded.value = false
  },
)

const showCover = computed(() => Boolean(props.coverUrl) && !broken.value)
</script>

<template>
  <span class="folder-cover">
    <span class="folder-cover__placeholder" aria-hidden="true">
      <AppIcon name="tv" :size="iconSize" />
    </span>
    <img
      v-if="showCover"
      class="folder-cover__img"
      :class="{ 'is-loaded': loaded }"
      :src="coverUrl ?? undefined"
      :alt="alt"
      loading="lazy"
      decoding="async"
      @load="loaded = true"
      @error="broken = true"
    />
  </span>
</template>

<style scoped>
.folder-cover {
  position: relative;
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: var(--radius-sm);
  background: var(--bg-2);
}

.folder-cover__placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-4);
}

.folder-cover__img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity 0.18s ease;
}

.folder-cover__img.is-loaded {
  opacity: 1;
}

@media (prefers-reduced-motion: reduce) {
  .folder-cover__img {
    transition: none;
  }
}
</style>
