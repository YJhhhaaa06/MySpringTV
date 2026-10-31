<script setup lang="ts">
import { computed } from 'vue'
import avatarSunrise from '../../assets/avatars/default-01-sunrise.svg'
import avatarPlanet from '../../assets/avatars/default-02-planet.svg'
import avatarCat from '../../assets/avatars/default-03-cat.svg'
import avatarBird from '../../assets/avatars/default-04-bird.svg'
import avatarTv from '../../assets/avatars/default-05-tv.svg'
import avatarCloud from '../../assets/avatars/default-06-cloud.svg'

/**
 * 默认头像（GAP-08）：后端没有头像字段 ⇒ 前端按 `userId` 稳定取一张本地 SVG。
 *
 * <p>数组顺序即分配顺序：同一 `userId` 永远同一张，不随刷新 / 页面变化。
 * 没有 `userId`（例如未登录的评论框）才退回昵称首字 —— 那只是「游客」占位，不是用户头像。
 */
const props = withDefaults(
  defineProps<{
    /** 用户 id；null = 没有身份（游客占位） */
    userId?: number | null
    /** 无 userId 时的兜底文字（昵称首字） */
    initial?: string
    /** 渲染边长（px），各页按 B 站实测尺寸传 */
    size?: number
  }>(),
  { userId: null, initial: '?', size: 40 },
)

const variants = [avatarSunrise, avatarPlanet, avatarCat, avatarBird, avatarTv, avatarCloud] as const

const variant = computed(() => {
  const id = props.userId
  if (id == null) return null
  return variants[Math.abs(id) % variants.length]
})

const boxStyle = computed(() => ({ width: `${props.size}px`, height: `${props.size}px` }))
const initialStyle = computed(() => ({ fontSize: `${Math.round(props.size * 0.4)}px` }))
</script>

<template>
  <img v-if="variant" class="user-avatar" :src="variant" :style="boxStyle" alt="" draggable="false" />
  <span v-else class="user-avatar user-avatar--initial" :style="[boxStyle, initialStyle]">{{ initial }}</span>
</template>

<style scoped>
.user-avatar {
  display: block;
  flex: none;
  border-radius: 50%;
  object-fit: cover;
  background: var(--bg-2);
}

.user-avatar--initial {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-2);
  font-weight: 600;
  line-height: 1;
}
</style>
