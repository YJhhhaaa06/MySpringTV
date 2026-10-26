<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/**
 * 搜索页应用外壳。
 *
 * <p>需要 `LoginGate` / `ToastHost`：搜索结果卡片与首页共用 `VideoCard`，
 * 2026-10-26 起带 hover「稍后再看」⇒ 未登录点了要弹登录、加入/取消要 toast。
 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
