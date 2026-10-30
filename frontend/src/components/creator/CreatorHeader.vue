<script setup lang="ts">
import { sessionState } from '../../auth/session'
import { requireLogin } from '../../auth/loginPrompt'
import AppIcon from '../common/AppIcon.vue'

/**
 * 创作中心顶栏（与主站 `AppHeader` 刻意不同）。
 *
 * <p>B 站创作中心是独立文档 + 独立顶栏：左边「创作中心」标识、一个回主站的入口，
 * 右边是账号。**没有搜索框 / 分区导航 / 消息** —— 消息在本仓没有域，不渲染。
 */
function login(): void {
  void requireLogin()
}
</script>

<template>
  <header class="creator-header">
    <div class="creator-header__inner">
      <a class="creator-header__logo" href="/creator">
        <AppIcon name="tv" :size="24" />
        <span class="creator-header__brand">哔哩哔哩</span>
        <span class="creator-header__sub">创作中心</span>
      </a>
      <a class="creator-header__main-site" href="/">主站</a>

      <div class="creator-header__actions">
        <a class="creator-header__upload" href="/creator">投稿</a>
        <a
          v-if="sessionState.status === 'authenticated' && sessionState.user"
          class="creator-header__user"
          :href="sessionState.user.spaceHref"
          :title="`${sessionState.user.name} 的空间`"
        >
          <span class="creator-header__avatar">{{ sessionState.user.initial }}</span>
          <span class="creator-header__name">{{ sessionState.user.name }}</span>
        </a>
        <button
          v-else
          class="creator-header__login"
          type="button"
          :disabled="sessionState.status === 'restoring'"
          @click="login"
        >
          登录
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.creator-header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: 56px;
  background: var(--bg-0);
  border-bottom: 1px solid var(--line-1);
}

.creator-header__inner {
  display: flex;
  height: 100%;
  align-items: center;
  gap: 16px;
  padding: 0 24px;
}

.creator-header__logo {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 18px;
  font-weight: 700;
  color: var(--brand-blue);
}

.creator-header__logo:hover {
  color: var(--brand-blue-dark);
}

.creator-header__brand {
  letter-spacing: -0.4px;
}

.creator-header__sub {
  padding-left: 8px;
  border-left: 1px solid var(--line-1);
  font-size: 15px;
  font-weight: 500;
  color: var(--text-1);
}

.creator-header__main-site {
  font-size: 13px;
  color: var(--text-3);
}

.creator-header__main-site:hover {
  color: var(--brand-blue);
}

.creator-header__actions {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-left: auto;
}

.creator-header__upload {
  height: 32px;
  padding: 0 16px;
  border-radius: var(--radius);
  font-size: 13px;
  line-height: 32px;
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.creator-header__upload:hover {
  color: var(--text-inverse);
  background: var(--brand-blue-dark);
}

.creator-header__user {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-2);
}

.creator-header__avatar {
  display: flex;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
}

.creator-header__login {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.creator-header__login:hover {
  background: var(--brand-blue-dark);
}

.creator-header__login:disabled {
  opacity: 0.6;
}

@media (max-width: 720px) {
  .creator-header__name,
  .creator-header__brand {
    display: none;
  }

  .creator-header__inner {
    padding: 0 12px;
  }
}
</style>
