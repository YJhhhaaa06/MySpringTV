<script setup lang="ts">
import { CHANNELS, featureUrl } from '../../constants/features'
import AppIcon from '../common/AppIcon.vue'

/**
 * 频道墙：**18 个分区是前端常量**（后端没有分类列表端点，且已拍板不补）。
 *
 * <p>每一项都链到应用内占位路由 `/placeholder/<key>` —— 不是死链，但也不假装分区页存在。
 */
</script>

<template>
  <section class="channel-wall" aria-label="频道">
    <div class="channel-wall__circles">
      <a class="circle" :href="featureUrl('feed')" title="动态：动态页后续窗口交付">
        <span class="circle__icon circle__icon--dynamic"><AppIcon name="dynamic" :size="20" /></span>
        <span class="circle__label">动态</span>
      </a>
      <a class="circle" :href="featureUrl('popular')" title="热门：本仓没有排行榜端点（永远占位）">
        <span class="circle__icon circle__icon--popular"><AppIcon name="fire" :size="20" /></span>
        <span class="circle__label">热门</span>
      </a>
    </div>

    <div class="channel-wall__tags">
      <a
        v-for="channel in CHANNELS"
        :key="channel.key"
        class="channel-tag"
        :href="featureUrl(channel.key)"
        :title="`${channel.label}：分区页本专项不做（占位页）`"
      >
        {{ channel.label }}
      </a>
    </div>
  </section>
</template>

<style scoped>
.channel-wall {
  display: flex;
  align-items: flex-start;
  gap: 24px;
  padding: 18px 0 8px;
}

.channel-wall__circles {
  display: flex;
  gap: 18px;
  flex: none;
}

.circle {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 48px;
}

.circle__icon {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-inverse);
  transition: transform 0.2s ease;
}

.circle:hover .circle__icon {
  transform: translateY(-2px);
}

.circle__icon--dynamic {
  background: linear-gradient(135deg, #ffb232 0%, #ff8a00 100%);
}

.circle__icon--popular {
  background: linear-gradient(135deg, #ff7a95 0%, #fb7299 100%);
}

.circle__label {
  font-size: 13px;
  color: var(--text-2);
}

.channel-wall__tags {
  display: grid;
  flex: 1;
  grid-template-columns: repeat(9, minmax(0, 1fr));
  gap: 8px;
  max-width: 900px;
}

.channel-tag {
  display: flex;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-2);
  background: var(--bg-1);
  transition: background-color 0.15s ease, color 0.15s ease;
  white-space: nowrap;
}

.channel-tag:hover {
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

@media (max-width: 1100px) {
  .channel-wall__tags {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .channel-wall {
    gap: 12px;
  }

  .channel-wall__tags {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
</style>
