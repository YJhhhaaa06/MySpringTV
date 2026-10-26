<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/** 历史页应用外壳（第 6 个入口，2026-10-26）：页面级登录态 + 登录浮层。 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
