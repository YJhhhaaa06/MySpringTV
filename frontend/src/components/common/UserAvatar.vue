<script setup lang="ts">
import { computed } from 'vue'
import { defaultAvatarForUser } from '../../constants/avatars'

/**
 * 用户头像（`R-27`）。
 *
 * <p>`avatarUrl` 是后端返回的**生效 URL**（默认 `default:N` / 自定义 `/upload/avatar/...` /
 * 未选过时按 `userId % 6` 回落）。调用方有 URL 就传；没有（老后端 / 未接线的旧面）
 * 仍按 `userId` 稳定回落本地 `public/avatars`。
 *
 * <p>★ 优先级：`avatarUrl` → `userId` 对应默认头像 → 昵称首字（游客）。
 * 没有任何身份（游客评论框）才用首字 —— 那只是占位，不是用户头像。
 */
const props = withDefaults(
  defineProps<{
    /** 生效头像 URL；null / 缺省 = 按 userId 回落 */
    avatarUrl?: string | null
    /** 用户 id；null = 没有身份（游客占位） */
    userId?: number | null
    /** 无头像 URL / userId 时的兜底文字（昵称首字） */
    initial?: string
    /** 渲染边长（px），各页按 B 站实测尺寸传 */
    size?: number
  }>(),
  { avatarUrl: null, userId: null, initial: '?', size: 40 },
)

const src = computed(() => {
  if (typeof props.avatarUrl === 'string' && props.avatarUrl) return props.avatarUrl
  return defaultAvatarForUser(props.userId)
})

const boxStyle = computed(() => ({ width: `${props.size}px`, height: `${props.size}px` }))
const initialStyle = computed(() => ({ fontSize: `${Math.round(props.size * 0.4)}px` }))
</script>

<template>
  <img v-if="src" class="user-avatar" :src="src" :style="boxStyle" alt="" draggable="false" />
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
