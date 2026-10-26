<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/**
 * 首页应用外壳。
 *
 * <p>需要 `LoginGate` / `ToastHost`：2026-10-26 起卡片 hover 有「稍后再看」——
 * 未登录点它是**动作级**闸门（弹登录框），加入/取消/失败都要 toast。
 * 之前首页只有只读卡片，所以这两个宿主一直没挂；别再把"页面只读"当成"不需要闸门"。
 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
