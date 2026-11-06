<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { currentUserId, restoreSession } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { legacyFavoriteTarget } from '../common/legacyFavoriteRedirect'
import EmptyState from '../components/common/EmptyState.vue'

/**
 * `/favorites` / `/watchlater` 的**软弃用重定向壳**（`R-34` ①）。
 *
 * <p>收藏夹内容的唯一查看入口已经收敛到 `/space/{uid}?tab=folders`。旧 URL 不再维护
 * 独立页面，只做 query 平移后整页跳转（跨 HTML 入口不能用应用内 router）：
 * `/favorites?folder=3&page=2` → `/space/{uid}?tab=folders&folder=3&itemPage=2`；
 * `/watchlater?page=2` → `...&view=later&itemPage=2`。
 *
 * <p>匿名不自动弹登录框（与其它页面级身份态一致）：给「登录后跳转」按钮，
 * 登录成功后继续同一个意图。旧书签仍可用，但看到的是空间收藏 tab。
 */
const route = useRoute()

const sessionReady = ref(false)
const loggedIn = computed(() => currentUserId() !== null)
const target = computed(() => {
  const uid = currentUserId()
  return uid === null ? '' : legacyFavoriteTarget(route.name?.toString(), route.query, uid)
})

function redirectIfPossible(): void {
  if (target.value) window.location.replace(target.value)
}

onMounted(async () => {
  await restoreSession()
  sessionReady.value = true
  redirectIfPossible()
})

// 顶栏的登录按钮也能登入（不经过本页的「登录后跳转」）⇒ 登录态一变就继续跳转
watch(loggedIn, (yes) => {
  if (yes) redirectIfPossible()
})

async function signIn(): Promise<void> {
  if (!(await requireLogin())) return
  redirectIfPossible()
}
</script>

<template>
  <main class="legacy-page page-main">
    <div class="page-content">
      <p v-if="!sessionReady" class="legacy-loading">正在恢复登录态…</p>
      <p v-else-if="loggedIn" class="legacy-loading">正在跳转到你的空间「收藏」…</p>

      <EmptyState
        v-else
        icon="star"
        title="收藏夹已迁移到个人空间"
        description="收藏夹与稍后再看现在都在个人空间的「收藏」tab 里。登录后会自动跳转过去。"
      >
        <button class="btn btn-primary" type="button" @click="signIn">登录后跳转</button>
        <a class="btn btn-ghost" href="/">先回首页</a>
      </EmptyState>
    </div>
  </main>
</template>

<style scoped>
.legacy-page {
  min-height: 100vh;
  padding-top: var(--header-h);
}

.legacy-loading {
  padding: 96px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}
</style>
