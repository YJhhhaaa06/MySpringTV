<script setup lang="ts">
import type { IconName } from './icons'
import AppIcon from './AppIcon.vue'

withDefaults(defineProps<{ title: string; description?: string; icon?: IconName }>(), { icon: 'tv' })
</script>

<template>
  <div class="empty-state">
    <span class="empty-state__icon"><AppIcon :name="icon" :size="44" /></span>
    <p class="empty-state__title">{{ title }}</p>
    <p v-if="description" class="empty-state__desc">{{ description }}</p>
    <div class="empty-state__actions"><slot /></div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 64px 16px;
  text-align: center;
}

.empty-state__icon {
  display: flex;
  width: 88px;
  height: 88px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-4);
  background: var(--bg-1);
}

.empty-state__title {
  margin-top: 16px;
  font-size: 16px;
  color: var(--text-2);
}

.empty-state__desc {
  margin-top: 6px;
  max-width: 460px;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-3);
}

.empty-state__actions {
  margin-top: 20px;
  display: flex;
  gap: 12px;
}
</style>
