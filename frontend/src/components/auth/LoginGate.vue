<script setup lang="ts">
import { loginPromptState, settleLogin } from '../../auth/loginPrompt'
import LoginModal from './LoginModal.vue'

/**
 * 登录闸门的**宿主**（每个应用挂一次）。
 *
 * <p>任何动作 `await requireLogin()` 都会把 {@link loginPromptState} 置为可见；
 * 这里只负责"把浮层画出来 + 把结果交回去"，不关心是谁点的。
 */
</script>

<template>
  <LoginModal
    v-if="loginPromptState.visible"
    @success="settleLogin(true)"
    @close="settleLogin(false)"
  />
</template>
