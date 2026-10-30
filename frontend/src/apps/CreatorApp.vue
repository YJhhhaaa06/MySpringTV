<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'
import CreatorHeader from '../components/creator/CreatorHeader.vue'

/**
 * 创作中心应用外壳（`creator.html` 的第 9 个入口）。
 *
 * <p>要 `LoginGate` / `ToastHost`：投稿与稿件管理整页都属于"我"，匿名时走页面级身份态，
 * 登录按钮仍是 `requireLogin()`；上传失败 / 成功要说清楚原因。
 *
 * <p>★ 左栏只放**本仓真有页面**的入口：投稿 / 稿件管理。
 * 其余栏目（数据中心 / 粉丝管理 / 收益管理 / 创作成长…）本仓没有域 ⇒ **不渲染**
 * （不是灰态占位，摆出来就是点不动的东西）。
 *
 * <p>★ 高亮用 `route.path` 前缀判定、不用 RouterLink 的 `active-class`：
 * 后者会把 `/creator` 算作 `/creator/manage` 的激活项（两条同时高亮），
 * 而 `/creator/edit/:id` 属于「稿件管理」的上下文，不该让「投稿」亮着。
 */
const route = useRoute()

const activeRail = computed<'upload' | 'works' | null>(() => {
  const path = route.path
  if (path === '/creator' || path === '/creator/') return 'upload'
  if (path.startsWith('/creator/manage') || path.startsWith('/creator/edit')) return 'works'
  return null
})
</script>

<template>
  <div class="creator-app">
    <CreatorHeader />
    <div class="creator-shell">
      <aside class="creator-rail" aria-label="创作中心导航">
        <p class="creator-rail__group">创作中心</p>
        <RouterLink class="creator-rail__item" :class="{ 'is-active': activeRail === 'upload' }" to="/creator">
          投稿
        </RouterLink>
        <RouterLink
          class="creator-rail__item"
          :class="{ 'is-active': activeRail === 'works' }"
          to="/creator/manage"
        >
          稿件管理
        </RouterLink>
      </aside>
      <main class="creator-main">
        <RouterView />
      </main>
    </div>
    <LoginGate />
    <ToastHost />
  </div>
</template>

<style scoped>
.creator-app {
  min-height: 100vh;
  background: var(--bg-2);
}

.creator-shell {
  display: flex;
  width: min(1180px, calc(100vw - 48px));
  align-items: flex-start;
  gap: 16px;
  margin: 0 auto;
  padding: 20px 0 48px;
}

.creator-rail {
  flex: none;
  width: 176px;
  padding-top: 8px;
}

.creator-rail__group {
  padding: 0 12px 8px;
  font-size: 12px;
  color: var(--text-3);
}

.creator-rail__item {
  display: block;
  height: 40px;
  padding: 0 14px;
  border-radius: var(--radius);
  font-size: 14px;
  line-height: 40px;
  color: var(--text-2);
}

.creator-rail__item:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

.creator-rail__item.is-active,
.creator-rail__item.is-active:hover {
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.creator-main {
  flex: 1 1 auto;
  min-width: 0;
  min-height: calc(100vh - 56px - 68px);
  padding: 20px 24px 32px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
}

@media (max-width: 860px) {
  .creator-shell {
    width: calc(100vw - 24px);
    flex-direction: column;
    gap: 10px;
    padding-top: 12px;
  }

  .creator-rail {
    display: flex;
    width: 100%;
    gap: 8px;
    padding-top: 0;
  }

  .creator-rail__group {
    display: none;
  }

  .creator-rail__item {
    height: 34px;
    line-height: 34px;
  }

  .creator-main {
    width: 100%;
    padding: 16px 14px 24px;
  }
}
</style>
