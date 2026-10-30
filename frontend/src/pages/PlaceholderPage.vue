<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { resolveFeature } from '../constants/features'
import EmptyState from '../components/common/EmptyState.vue'
import AppIcon from '../components/common/AppIcon.vue'

/**
 * 占位页（`/placeholder/:feature`）：**所有灰态入口的落点**。
 *
 * <p>刻意区分两种占位：
 * - `never`：本仓设计上不做 ⇒ 永远占位、不进「等后端」清单；
 * - `pending`：后端接口已有、页面排在后续窗口。
 *
 * <p>未知 feature（含各应用的 catch-all 404）落到「页面不存在」。
 */
const route = useRoute()
const router = useRouter()

const feature = computed(() => {
  const raw = route.params.feature
  return resolveFeature(typeof raw === 'string' ? raw : null)
})

const isNever = computed(() => feature.value?.kind === 'never')
</script>

<template>
  <main class="page-content page-main">
    <div v-if="feature" class="placeholder-card">
      <span class="placeholder-card__icon">
        <AppIcon :name="isNever ? 'tv' : 'clock'" :size="40" />
      </span>
      <h1 class="placeholder-card__title">
        {{ feature.label }}
        <span class="placeholder-card__badge" :class="{ 'is-pending': !isNever }">
          {{ isNever ? '永远占位' : '后续窗口' }}
        </span>
      </h1>
      <p class="placeholder-card__note">{{ feature.note }}</p>
      <p class="placeholder-card__policy">
        {{
          isNever
            ? '按方案：这个功能不进「等后端」清单，也不阻塞重制。'
            : '按方案：后端接口已就绪，页面排期在后续窗口。'
        }}
      </p>
      <div class="placeholder-card__actions">
        <a class="btn btn-primary" href="/">回首页</a>
        <button class="btn btn-ghost" type="button" @click="router.back()">返回上一页</button>
      </div>
    </div>

    <EmptyState v-else icon="tv" title="页面不存在" description="这个地址没有对应页面。可能是链接过期，或者内容已被删除。">
      <a class="btn btn-primary" href="/">回首页</a>
    </EmptyState>
  </main>
</template>

<style scoped>
.placeholder-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: 620px;
  margin: 48px auto 0;
  padding: 40px 32px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius-lg);
  text-align: center;
}

.placeholder-card__icon {
  display: flex;
  width: 84px;
  height: 84px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-3);
  background: var(--bg-1);
}

.placeholder-card__title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.placeholder-card__badge {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 12px;
  font-weight: 400;
  color: var(--text-3);
  background: var(--bg-2);
}

.placeholder-card__badge.is-pending {
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

.placeholder-card__note {
  margin-top: 14px;
  font-size: 14px;
  line-height: 22px;
  color: var(--text-2);
}

.placeholder-card__policy {
  margin-top: 10px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}

.placeholder-card__actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}
</style>
