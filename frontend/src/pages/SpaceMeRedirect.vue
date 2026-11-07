<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentUserId, restoreSession } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import EmptyState from '../components/common/EmptyState.vue'

/**
 * `/space/me` 的登录态解析入口（`R-34` ②）。
 *
 * <p>顶栏「收藏」从任意 HTML 入口点进来时还不知道自己的 uid，所以先落这个别名；
 * 登录态落定后 `replace` 成 B 站形状的 `/space/{uid}?tab=folders`（保留原 query）。
 * 匿名不自动弹框：页面级「登录后查看」+ 登录按钮，成功后继续同一个跳转意图。
 */
const route = useRoute()
const router = useRouter()

const sessionReady = ref(false)
const loggedIn = computed(() => currentUserId() !== null)

function redirectToSelf(): void {
  const uid = currentUserId()
  if (uid === null) return
  void router.replace({
    name: 'space',
    params: { id: uid },
    query: { ...route.query },
    hash: route.hash,
  })
}

onMounted(async () => {
  await restoreSession()
  sessionReady.value = true
  redirectToSelf()
})

// 顶栏的登录按钮也能登入（不经过本页的「立即登录」）⇒ 登录态一变就继续跳转
watch(loggedIn, (yes) => {
  if (yes) redirectToSelf()
})

async function signIn(): Promise<void> {
  if (!(await requireLogin())) return
  redirectToSelf()
}
</script>

<template>
  <main class="space-me-page page-main">
    <div class="page-content">
      <p v-if="!sessionReady" class="space-me-loading">正在恢复登录态…</p>
      <p v-else-if="loggedIn" class="space-me-loading">正在跳转到你的空间…</p>

      <EmptyState
        v-else
        icon="user"
        title="登录后查看自己的空间"
        description="「收藏」入口需要先知道你是谁：登录后会自动跳到你的个人空间收藏 tab。"
      >
        <button class="btn btn-primary" type="button" @click="signIn">立即登录</button>
        <a class="btn btn-ghost" href="/">先回首页</a>
      </EmptyState>
    </div>
  </main>
</template>

<style scoped>
.space-me-page {
  min-height: 100vh;
  padding-top: var(--header-h);
}

.space-me-loading {
  padding: 96px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}
</style>
