<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/**
 * 播放页应用外壳。
 *
 * <p>`LoginGate` 是**动作级登录闸门**的宿主：点赞 / 投币 / 收藏在没有登录时
 * 会 `await requireLogin()`，浮层由它渲染（顶栏那个登录按钮是另一条入口）。
 * <p>`ToastHost` 只在这个应用挂：本窗口的动作提示都发生在播放页。
 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
