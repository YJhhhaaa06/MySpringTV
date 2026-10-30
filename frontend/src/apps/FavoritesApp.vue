<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/**
 * 收藏页应用外壳（第 5 个入口，2026-10-26）。
 *
 * <p>需要 `LoginGate`：页面级「登录后查看」按钮走的是**动作级**闸门 `requireLogin()`；
 * 需要 `ToastHost`：单条移除成功/失败都要有反馈（静默删除用户会怀疑没生效）。
 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
