<script setup lang="ts">
import AppFooter from '../components/layout/AppFooter.vue'
import AppHeader from '../components/layout/AppHeader.vue'
import LoginGate from '../components/auth/LoginGate.vue'
import ToastHost from '../components/common/ToastHost.vue'

/**
 * 图文详情应用外壳。
 *
 * <p>要 `LoginGate`：操作栏的点赞 / 收藏是**动作级**登录闸门（匿名可看正文与评论，
 * 点动作才弹登录）；要 `ToastHost`：复制链接 / 点赞 / 收藏成功与失败都要说清楚。
 */
</script>

<template>
  <AppHeader />
  <RouterView />
  <AppFooter />
  <LoginGate />
  <ToastHost />
</template>
