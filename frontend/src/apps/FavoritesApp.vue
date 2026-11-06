<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/**
 * 旧收藏页应用外壳（第 5 个入口，2026-10-26；`R-34` 起**软弃用**）。
 *
 * <p>页面已换成 `LegacyFavoriteRedirect`：`/favorites` / `/watchlater` 只做旧 query 平移，
 * 然后整页跳到空间收藏 tab。`LoginGate` 仍需要（匿名时「登录后跳转」走动作级闸门）；
 * `ToastHost` 本页不再产生写操作，但保留外壳形状，待入口删除时一起清掉。
 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
