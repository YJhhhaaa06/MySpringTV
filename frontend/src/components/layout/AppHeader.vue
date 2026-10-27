<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { sessionState, signOut } from '../../auth/session'
import { HEADER_ACTIONS, HEADER_NAV, featureUrl } from '../../constants/features'
import LoginModal from '../auth/LoginModal.vue'
import AppIcon from '../common/AppIcon.vue'
import SearchBox from './SearchBox.vue'

/**
 * 顶栏（B 站 2026 布局：半透明浮在 banner 上，滚动后变白）。
 *
 * <p>是否透明由**路由 meta**决定（`meta.overlay`）：首页有 banner、其余页面永远白底。
 * 这样跨应用（首页 / 搜索 / 播放页）各自实例化时都不用额外传参。
 */
const route = useRoute()

const overlay = computed(() => route.meta.overlay === true)
const scrolled = ref(false)
const solid = computed(() => !overlay.value || scrolled.value)
const loginOpen = ref(false)

const keyword = computed(() => (typeof route.query.keyword === 'string' ? route.query.keyword : ''))

function onScroll(): void {
  scrolled.value = window.scrollY > 32
}

onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
})
</script>

<template>
  <header class="app-header" :class="{ 'app-header--solid': solid, 'app-header--overlay': !solid }">
    <div class="app-header__inner">
      <a class="app-header__logo" href="/" aria-label="回首页">
        <AppIcon name="tv" :size="28" />
        <span class="app-header__brand">bilibili</span>
      </a>

      <nav class="app-header__nav" aria-label="主导航">
        <a
          v-for="item in HEADER_NAV"
          :key="item.href"
          class="nav-item"
          :class="{ 'is-placeholder': item.placeholder }"
          :href="item.href"
          :title="item.placeholder ? `${item.label}：本专项不做（占位页）` : undefined"
        >
          {{ item.label }}
        </a>
      </nav>

      <div class="app-header__search">
        <SearchBox :initial-keyword="keyword" />
      </div>

      <div class="app-header__actions">
        <div v-if="sessionState.status === 'authenticated' && sessionState.user" class="user-chip">
          <a
            class="user-chip__link"
            :href="sessionState.user.spaceHref"
            :title="`${sessionState.user.name} 的空间`"
          >
            <span class="avatar">{{ sessionState.user.initial }}</span>
          </a>
          <div class="user-menu">
            <p class="user-menu__name">{{ sessionState.user.name }}</p>
            <p class="user-menu__meta">硬币 {{ sessionState.user.coin }}</p>
            <button class="user-menu__logout" type="button" @click="signOut">退出登录</button>
          </div>
        </div>
        <button
          v-else
          class="login-btn"
          type="button"
          :disabled="sessionState.status === 'restoring'"
          @click="loginOpen = true"
        >
          登录
        </button>

        <a
          v-for="action in HEADER_ACTIONS"
          :key="action.label"
          class="header-icon"
          :class="{ 'is-placeholder': action.placeholder }"
          :href="action.href"
          :title="action.placeholder ? `${action.label}（占位页）` : action.label"
          :aria-label="action.label"
        >
          <AppIcon :name="action.icon" :size="20" />
        </a>

        <a class="upload-btn" :href="featureUrl('upload')" title="投稿（占位页）">
          <AppIcon name="upload" :size="16" />
          <span>投稿</span>
        </a>
      </div>
    </div>
  </header>

  <LoginModal v-if="loginOpen" @close="loginOpen = false" @success="loginOpen = false" />
</template>

<style scoped>
.app-header {
  --search-bg: var(--bg-1);
  position: fixed;
  top: 0;
  right: 0;
  left: 0;
  z-index: 100;
  height: var(--header-h);
  color: var(--text-1);
  background: var(--bg-0);
  border-bottom: 1px solid var(--line-2);
  transition: background-color 0.25s ease, color 0.25s ease, border-color 0.25s ease;
}

/* 浮在 banner 上：透明底、白字 */
.app-header--overlay {
  --search-bg: rgba(255, 255, 255, 0.92);
  color: var(--text-inverse);
  background: transparent;
  border-bottom-color: transparent;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.18);
}

.app-header--overlay .nav-item.is-placeholder {
  color: rgba(255, 255, 255, 0.72) !important;
}

.app-header__inner {
  display: flex;
  height: 100%;
  align-items: center;
  gap: 12px;
  padding: 0 24px;
}

.app-header__logo {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.6px;
  color: inherit;
}

.app-header__logo:hover {
  color: inherit;
}

.app-header__brand {
  display: inline;
}

/* 首页浮在 banner 上时，banner 里已经有大的 wordmark ⇒ 顶栏只留图标，不重复 */
.app-header--overlay .app-header__brand {
  display: none;
}

.app-header__nav {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: none;
}

.nav-item {
  padding: 0 9px;
  font-size: 14px;
  line-height: var(--header-h);
  color: inherit;
  white-space: nowrap;
}

.nav-item:hover {
  color: var(--brand-blue);
}

.app-header--overlay .nav-item:hover {
  color: var(--text-inverse);
  text-shadow: 0 0 8px rgba(0, 0, 0, 0.35);
}

.app-header__search {
  flex: 1 1 auto;
  max-width: 480px;
  margin-left: 8px;
}

.app-header__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
  flex: none;
}

.login-btn {
  height: 34px;
  padding: 0 14px;
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-inverse);
  background: var(--brand-blue);
  text-shadow: none;
}

.login-btn:hover {
  background: var(--brand-blue-dark);
}

.login-btn:disabled {
  opacity: 0.6;
}

.header-icon {
  display: flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: inherit;
  opacity: 0.9;
}

.header-icon:hover {
  color: var(--brand-blue);
  background: rgba(0, 0, 0, 0.04);
  opacity: 1;
}

.app-header--overlay .header-icon:hover {
  color: var(--text-inverse);
  background: rgba(255, 255, 255, 0.18);
}

.upload-btn {
  display: flex;
  height: 34px;
  align-items: center;
  gap: 5px;
  padding: 0 14px;
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-inverse);
  background: var(--brand-pink);
  text-shadow: none;
}

.upload-btn:hover {
  color: var(--text-inverse);
  background: var(--brand-pink-dark);
}

/* 登录后的用户区（hover 出下拉） */
.user-chip {
  position: relative;
  height: 40px;
  display: flex;
  align-items: center;
}

.user-chip__link {
  display: block;
  border-radius: 50%;
  line-height: 0;
}

.avatar {
  display: flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
  text-shadow: none;
}

.user-menu {
  position: absolute;
  top: 44px;
  right: 0;
  display: none;
  width: 172px;
  padding: 12px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  text-shadow: none;
  color: var(--text-1);
}

.user-chip:hover .user-menu,
.user-chip:focus-within .user-menu {
  display: block;
}

.user-menu__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}

.user-menu__meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-3);
}

.user-menu__logout {
  width: 100%;
  height: 30px;
  margin-top: 10px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--text-2);
  background: var(--bg-1);
}

.user-menu__logout:hover {
  color: var(--text-1);
  background: var(--bg-2);
}

@media (max-width: 1200px) {
  .app-header__nav {
    display: none;
  }

  .app-header__search {
    margin-left: 0;
    max-width: none;
  }
}

@media (max-width: 900px) {
  .app-header__actions {
    gap: 4px;
  }
}

@media (max-width: 640px) {
  .app-header__inner {
    padding: 0 12px;
    gap: 8px;
  }

  .app-header__brand {
    display: none !important;
  }

  .upload-btn span {
    display: none;
  }
}
</style>
