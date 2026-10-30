<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/**
 * 动态页应用外壳。
 *
 * <p>要 `LoginGate`：页面级身份态里的「登录」按钮与点赞动作都走 `requireLogin()`；
 * 要 `ToastHost`：点赞失败 / 登录过期要说清楚原因。
 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
