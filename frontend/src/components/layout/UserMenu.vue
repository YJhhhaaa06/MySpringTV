<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { sessionState, signOut } from '../../auth/session'
import { loadProfileHeader } from '../../api/profile'
import { formatCount, formatCoinInt } from '../../api/adapters/format'
import { CREATOR_HREF } from '../../constants/features'
import type { SpaceHeaderVM } from '../../types/ui'
import { createHoverMenu } from './menuHover'
import AppIcon from '../common/AppIcon.vue'
import UserAvatar from '../common/UserAvatar.vue'

/**
 * 顶栏账号卡片（`R-30`）。
 *
 * <h2>★ 它承载的是本仓前端**唯一**的主动「退出登录」入口</h2>
 * 所以"能打开"比"好看"优先：卡片关闭带 180ms 宽限（`menuHover.ts`），
 * 且头像与卡片之间的 8px 是**透明外壳的 padding**，不是空隙 ——
 * 旧实现 `top: 44px` + `display: none` 让指针必须跨过一条 4px 的缝，
 * 穿过去 hover 就断、卡片立刻消失，"正常手速点不到"的根因在这里。
 *
 * <h2>交互三分支</h2>
 * - 桌面（`hover: hover`）：悬停头像/卡片开，移出 180ms 后关；头像点击**仍直接进空间**（B 站口径）。
 * - 触屏（`hover: none`）：没有可靠的 hover ⇒ **点击头像 toggle 卡片**，进空间走卡片里的「个人中心」。
 * - 键盘：`focusin` 开、`focusout` 关、`Esc` 关并把焦点还给头像、`aria-expanded` 同步。
 *
 * <h2>计数为什么是懒加载</h2>
 * `GET /users/me`（`UserInfoVO`）只有 id / username / coin / admin / signedToday / avatarUrl，
 * **没有**关注 / 粉丝 / 投稿三列；这三列在 `ProfileVO` 上 ⇒ 首次打开卡片时补一次
 * `GET /users/{id}?pageSize=1`（`loadProfileHeader`，动态页左卡已在用同一招）。
 * 失败**静默降级**为 `-`：卡片的必需功能是退出登录，不能因为计数拉不到就打不开。
 *
 * <h2>「动态」是本仓口径</h2>
 * 没有转发 / 纯文字动态域 ⇒ 第三列用 `contentPage.total`（投稿总数），
 * 点进去是空间主页（投稿时间线）。见 `architecture/frontend/前端.md` §六。
 */
const user = computed(() => sessionState.user)

const root = ref<HTMLElement | null>(null)
const open = ref(false)

/**
 * 主指针是否支持 hover。触屏（手机 / 平板）为 false ⇒ 走"点击开卡片"。
 * `matchMedia` 不存在（极老浏览器）时按 true 处理：桌面是主场景。
 */
const canHover = ref(true)

const counts = ref<SpaceHeaderVM | null>(null)
const countsLoading = ref(false)
const countsFailedAt = ref(0)

/** 失败后 10s 内不再重试：防止"指针快速划过"打出一串请求，又不在弱网恢复后永远锁死 */
const COUNT_RETRY_MS = 10_000

const menu = createHoverMenu({
  closeDelayMs: 180,
  onChange: (value) => {
    open.value = value
  },
})

function openCard(): void {
  menu.open()
  void ensureCounts()
}

function closeCard(): void {
  menu.close()
}

/** 悬停只对"能 hover"的设备生效；触屏由点击驱动，否则 iOS 的粘滞 hover 会把卡片卡住 */
function onPointerEnter(): void {
  if (!canHover.value) return
  openCard()
}

function onPointerLeave(): void {
  if (!canHover.value) return
  menu.scheduleClose()
}

/**
 * 触屏：头像不再直接导航，改为开卡片（`preventDefault`）。
 * 桌面不拦截 —— 点头像进空间是 B 站行为，卡片里的「个人中心」只是同一条路。
 */
function onAvatarClick(event: MouseEvent): void {
  if (canHover.value) return
  event.preventDefault()
  if (open.value) closeCard()
  else openCard()
}

function onFocusIn(event: FocusEvent): void {
  /*
   * ★ 组件**内部**换焦点不算"打开意图"。
   * 反例：Esc 关闭后把焦点还给头像、或点卡片里的链接，都会再派一次 focusin；
   * 不拦住就会"刚关掉又立刻打开"，用户看到的是 Esc 没反应。
   */
  const from = event.relatedTarget as Node | null
  if (from && root.value?.contains(from)) return

  /*
   * ★ 只有**键盘聚焦**才在 focusin 时开卡片。
   * 触摸 / 鼠标点 `<a>` 也会先让它获得焦点：若不区分，触屏点一下的时序是
   * `focusin` 开 → `click` 再 toggle → **又关掉**，点击等于没反应（T92-5 实测抓到）。
   * `:focus-visible` 正好是"这个焦点该不该画出来"的浏览器判据：Tab 为 true，
   * 指针点击通常为 false。
   */
  const target = event.target as HTMLElement | null
  let keyboard = canHover.value
  try {
    keyboard = target?.matches(':focus-visible') ?? canHover.value
  } catch {
    // 极老浏览器没有 :focus-visible：退回"只让 hover 设备走 focusin"
  }
  if (!keyboard) return
  openCard()
}

function onFocusOut(event: FocusEvent): void {
  const next = event.relatedTarget as Node | null
  if (next && root.value?.contains(next)) return
  closeCard()
}

/**
 * Esc 必须挂 **document**：桌面 hover 打开时焦点通常还在 `body`，
 * 只绑在 `.user-chip` 上的话按键根本不进 handler（review P1）。
 * 关闭后只在"焦点本来就在卡片里"时归还焦点，避免 hover 场景平白抢焦点。
 */
function onDocumentKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || !open.value) return
  const hadFocus = root.value?.contains(document.activeElement) ?? false
  closeCard()
  if (hadFocus) root.value?.querySelector<HTMLElement>('.user-chip__link')?.focus()
}

/**
 * 点击卡片外部关闭（触屏是主要用途；桌面鼠标移出本来就会走延时关闭）。
 *
 * <p>用 `pointerdown` 而不是 `click`：iOS Safari 对正文空白区的 tap 不保证派发 `click`，
 * 但 `pointerdown` 一定派发（与 `VideoActionBar` 的浮层同一口径）。
 * 触屏点自己时 `open` 还是 false，这里会提前 return，不影响随后的 toggle。
 */
function onDocumentPointerDown(event: PointerEvent): void {
  if (!open.value) return
  const target = event.target as Node | null
  if (target && root.value?.contains(target)) return
  closeCard()
}

async function ensureCounts(): Promise<void> {
  const me = user.value
  if (!me || counts.value || countsLoading.value) return
  if (countsFailedAt.value && Date.now() - countsFailedAt.value < COUNT_RETRY_MS) return

  countsLoading.value = true
  try {
    counts.value = await loadProfileHeader(me.id)
  } catch {
    // ★ 静默：三个数字不是卡片的必需功能，退出登录才是
    countsFailedAt.value = Date.now()
  } finally {
    countsLoading.value = false
  }
}

/**
 * 退出：**不做二次确认、不做 toast**（`R-30`）。
 * 整页回首页是刻意的 —— 点赞 / 收藏 / 播放上报 / 页面级 `watch(sessionState.status)` 都有内存态，
 * 就地切"登录"按钮会留下"已点赞"这类假象；整页加载一次清干净（B 站退出同样整页跳转）。
 */
function onLogout(): void {
  closeCard()
  signOut()
  // 用 replace 而不是 assign：退出后按「后退」不该再回到刚登出的那页
  window.location.replace('/')
}

onMounted(() => {
  canHover.value = window.matchMedia?.('(hover: hover)').matches ?? true
  document.addEventListener('pointerdown', onDocumentPointerDown)
  document.addEventListener('keydown', onDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onDocumentKeydown)
  menu.dispose()
})
</script>

<template>
  <div
    v-if="user"
    ref="root"
    class="user-chip"
    :class="{ 'is-open': open }"
    @mouseenter="onPointerEnter"
    @mouseleave="onPointerLeave"
    @focusin="onFocusIn"
    @focusout="onFocusOut"
  >
    <a
      class="user-chip__link"
      :href="user.spaceHref"
      :title="`${user.name} 的空间`"
      :aria-label="`${user.name} 的空间`"
      aria-haspopup="true"
      aria-controls="user-menu-panel"
      :aria-expanded="open"
      @click="onAvatarClick"
    >
      <UserAvatar :avatar-url="user.avatarUrl" :user-id="user.id" :size="34" />
    </a>

    <div class="user-menu" :class="{ 'is-open': open }">
      <div id="user-menu-panel" class="user-menu__panel">
        <UserAvatar class="user-menu__avatar" :avatar-url="user.avatarUrl" :user-id="user.id" :size="56" />
        <p class="user-menu__name">{{ user.name }}</p>
        <!-- 硬币按 B 站口径取整（后端是一位小数，只有投币弹层显示小数） -->
        <p class="user-menu__meta">硬币 {{ formatCoinInt(user.coin) }}</p>

        <!-- 三列都是真数据：关注 / 粉丝来自 ProfileVO，动态 = 投稿总数（本仓无独立动态域） -->
        <div class="user-menu__stats">
          <a
            class="user-menu__stat"
            :href="`${user.spaceHref}?tab=following`"
            target="_blank"
            rel="noopener"
            title="关注列表（新标签页）"
            aria-label="关注列表（新标签页）"
          >
            <span class="user-menu__stat-value">{{ formatCount(counts?.followingCount) }}</span>
            <span class="user-menu__stat-label">关注</span>
          </a>
          <a
            class="user-menu__stat"
            :href="`${user.spaceHref}?tab=followers`"
            target="_blank"
            rel="noopener"
            title="粉丝列表（新标签页）"
            aria-label="粉丝列表（新标签页）"
          >
            <span class="user-menu__stat-value">{{ formatCount(counts?.followerCount) }}</span>
            <span class="user-menu__stat-label">粉丝</span>
          </a>
          <a
            class="user-menu__stat"
            :href="user.spaceHref"
            target="_blank"
            rel="noopener"
            title="我的投稿（新标签页）"
            aria-label="我的投稿（新标签页）"
          >
            <span class="user-menu__stat-value">{{ formatCount(counts?.worksCount) }}</span>
            <span class="user-menu__stat-label">动态</span>
          </a>
        </div>

        <nav class="user-menu__links" aria-label="账号菜单">
          <a class="user-menu__link" :href="user.spaceHref">
            <AppIcon name="user" :size="17" />
            <span>个人中心</span>
            <AppIcon class="user-menu__arrow" name="chevron-right" :size="14" />
          </a>
          <a class="user-menu__link" :href="CREATOR_HREF">
            <AppIcon name="upload" :size="17" />
            <span>投稿管理</span>
            <AppIcon class="user-menu__arrow" name="chevron-right" :size="14" />
          </a>
        </nav>

        <div class="user-menu__divider" aria-hidden="true" />

        <button class="user-menu__logout" type="button" @click="onLogout">
          <AppIcon name="logout" :size="17" />
          <span>退出登录</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.user-chip {
  position: relative;
  z-index: 2;
  display: flex;
  height: 40px;
  align-items: center;
}

.user-chip__link {
  display: block;
  border-radius: 50%;
  line-height: 0;
  transition: transform 0.18s ease;
}

/*
 * 头像放大只在真 hover 设备上做：触屏点一下会留下粘滞 hover，
 * 头像停在放大态看起来像"卡住了"。
 */
@media (hover: hover) {
  .user-chip:hover .user-chip__link,
  .user-chip.is-open .user-chip__link {
    transform: scale(1.12);
  }
}

.user-menu {
  position: absolute;
  top: 100%;
  /*
   * 以**头像中心**为轴展开（不是右对齐）：卡内 56px 头像与顶栏 34px 头像同一条竖线，
   * 视觉上是"头像变大 + 卡片从下面长出来"；右对齐会让两张头像并排，像复制了一份。
   */
  left: 50%;
  z-index: 20;
  width: 300px;
  /*
   * ★ 这 8px 是**透明外壳的 padding**，不是视觉间隙。
   * 卡片与头像之间必须由同一个命中区域连起来 —— 旧实现把卡片放在 `top: 44px`
   * （chip 高 40）留出 4px 真空，指针穿过去 hover 就断，卡片随即 `display: none`。
   * 视觉上的留白由内层 panel 的上边距承担，外壳永远贴住 chip 底边。
   */
  padding-top: 8px;
  opacity: 0;
  visibility: hidden;
  transform: translateX(-50%) translateY(-6px);
  transition: opacity 0.16s ease, transform 0.16s ease, visibility 0s linear 0.16s;
}

.user-menu.is-open {
  opacity: 1;
  visibility: visible;
  transform: translateX(-50%);
  transition-delay: 0s;
}

.user-menu__panel {
  padding: 14px 16px 10px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  color: var(--text-1);
  /* 首页顶栏浮在 banner 上时父级有白色 text-shadow，卡片里必须关掉 */
  text-shadow: none;
  /*
   * 矮视口（手机横屏 / 320 高窗口）不能把「退出登录」推出屏幕：
   * 卡片是 fixed header 的子元素，页面滚动不会把它带回来 —— 那是唯一退出入口的静默失效。
   */
  max-height: calc(100vh - var(--header-h) - 16px);
  overflow-y: auto;
  overscroll-behavior: contain;
}

.user-menu__avatar {
  margin: 0 auto 8px;
  box-shadow: 0 0 0 2px var(--bg-0), var(--shadow-1);
}

.user-menu__name {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-menu__meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-3);
  text-align: center;
}

.user-menu__stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--line-2);
  border-bottom: 1px solid var(--line-2);
}

.user-menu__stat {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  border-radius: var(--radius-sm);
  color: var(--text-1);
}

.user-menu__stat:hover {
  color: var(--brand-blue);
}

.user-menu__stat-value {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
}

.user-menu__stat-label {
  font-size: 12px;
  color: var(--text-3);
}

.user-menu__stat:hover .user-menu__stat-label {
  color: inherit;
}

.user-menu__links {
  padding: 6px 0;
}

.user-menu__link {
  display: flex;
  height: 36px;
  align-items: center;
  gap: 10px;
  padding: 0 6px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--text-2);
}

.user-menu__link:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

.user-menu__link > span {
  flex: 1;
}

.user-menu__arrow {
  color: var(--text-4);
}

.user-menu__divider {
  height: 1px;
  margin: 4px 0;
  background: var(--line-2);
}

.user-menu__logout {
  display: flex;
  width: 100%;
  height: 36px;
  align-items: center;
  gap: 10px;
  padding: 0 6px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--text-2);
}

.user-menu__logout:hover {
  color: #f56c6c;
  background: rgba(245, 108, 108, 0.08);
}

@media (max-width: 640px) {
  /*
   * 窄屏下头像右侧还挤着 4 个图标 + 投稿按钮，以头像为中心的 300px 卡片会溢出左边界。
   * 改成相对视口贴边：左右各留 12px，宽度自适应（同时取消 translateX(-50%)）。
   *
   * top = 52（= header 64 - 12 - chip 40 的底边），让卡片**贴住 chip 底边**：
   * 窄桌面窗口仍走 hover，若像旧实现那样留 8px 真空，跨缝只能靠 180ms 宽限硬扛。
   */
  .user-menu,
  .user-menu.is-open {
    position: fixed;
    top: calc(var(--header-h) - 12px);
    right: 12px;
    left: 12px;
    width: auto;
    transform: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .user-chip__link,
  .user-menu {
    transition: none;
  }
}
</style>
