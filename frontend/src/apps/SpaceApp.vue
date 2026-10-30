<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/**
 * 空间页应用外壳。
 *
 * <p>需要 `LoginGate`：关注按钮是动作级登录（未登录点了先弹登录），
 * 与播放页的点赞/投币同一套闸门。顶栏是白底（空间页有 banner，但 B 站那里是**整块**插图，
 * 顶栏不透明 —— 与首页 hero 的处理不同）。
 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
