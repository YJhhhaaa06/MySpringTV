<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { HistoryItemVM } from '../../types/ui'
import AppIcon from '../common/AppIcon.vue'

/**
 * 历史页的卡片（B 站 2026-10 历史记录版式）。
 *
 * <h2>与 `VideoCard` 的三处不同（所以不复用它）</h2>
 * 1. **封面底部是"观看进度条 + 已看/总长"角标**（`18:09/49:45` / `已看完` / `看到 12:00`）——
 *    普通卡片的角标是**总时长**，语义不同，硬塞进 `durationText` 会让字段说谎；
 * 2. 卡片 meta 是「UP 主 · 今天 12:57」（**观看时刻**，不是发布时间）；
 * 3. 失效条目要保留「观看时间」但去掉进度与作者（历史数据是真的，展示字段是脱敏的）。
 *
 * <h2>`R-26` 起有两个操作入口（B站 2026-10-31 实测）</h2>
 * <ul>
 *   <li><b>普通态</b>：meta 行右端一个垃圾桶 ⇒ **直删，不确认**（B站 就是这样；单条点错也能再看到）；</li>
 *   <li><b>管理态</b>（{@code selectable}）：封面左上角出现勾选框，**点卡片任意处 = 勾选/取消**，
 *       删除动作挪到工具栏（且**必过确认框**）。</li>
 * </ul>
 * 管理态下卡片不再可跳转（`href` 保留但 `click` 被拦）——一个"点了会选中"的卡片同时能跳转，
 * 用户永远不知道自己刚才那一下会做哪件事。
 */
const props = defineProps<{
  item: HistoryItemVM
  dayLabel: string
  /** 管理态：整卡变成选择器（`R-26`） */
  selectable?: boolean
  selected?: boolean
}>()

const emit = defineEmits<{
  /** 普通态的垃圾桶：直删这一条 */
  (event: 'remove'): void
  /** 管理态：勾选 / 取消勾选 */
  (event: 'toggle'): void
}>()

/** 封面解码失败用灰底兜底（dev 数据里有坏图，不是 404） */
const coverBroken = ref(false)
watch(
  () => props.item.coverUrl,
  () => {
    coverBroken.value = false
  },
)
const showCover = computed(() => Boolean(props.item.coverUrl) && !coverBroken.value)

/** `今天 12:57` / `10-24 18:00`；时间未知时不硬拼日期 */
const metaTime = computed(() => {
  const day = props.dayLabel === '时间未知' ? '' : props.dayLabel
  return [day, props.item.watchedTimeText].filter(Boolean).join(' ')
})

/** 失效条目链接必然 404 ⇒ 不跳转（与 `VideoCard#guardInvalid` 同口径） */
function guardInvalid(event: MouseEvent): void {
  if (props.item.invalid) event.preventDefault()
}

/** 管理态：任何一次点击都只是勾选（不跳转、不播放） */
function onCardClick(event: MouseEvent): void {
  if (!props.selectable) return
  event.preventDefault()
  emit('toggle')
}
</script>

<template>
  <article
    class="history-card"
    :class="{ 'is-invalid': item.invalid, 'is-managing': selectable, 'is-selected': selected }"
    @click="onCardClick"
  >
    <a
      class="history-card__cover"
      :href="item.href"
      :aria-label="item.title"
      :aria-disabled="item.invalid || undefined"
      @click="guardInvalid"
    >
      <img
        v-if="showCover"
        class="history-card__img"
        :src="item.coverUrl ?? undefined"
        :alt="item.title"
        loading="lazy"
        decoding="async"
        @error="coverBroken = true"
      />
      <span v-else class="history-card__cover-fallback" aria-hidden="true">
        <AppIcon name="tv" :size="28" />
      </span>

      <span v-if="item.invalid" class="history-card__invalid">已失效</span>

      <!-- 管理态的勾选框：装饰元素（真正的可点区域是整张卡，避免 12px 的命中区） -->
      <span
        v-if="selectable"
        class="history-card__check"
        :class="{ 'is-checked': selected }"
        aria-hidden="true"
      >
        <AppIcon v-if="selected" name="check" :size="12" />
      </span>

      <!-- B 站历史页的标志性元素：封面底的进度条 + 「已看/总长」角标 -->
      <span v-if="item.badgeText" class="history-card__footer">
        <span class="history-card__bar" :style="{ width: `${item.progressPercent}%` }" aria-hidden="true" />
        <span class="history-card__badge">{{ item.badgeText }}</span>
      </span>
    </a>

    <a
      class="history-card__title"
      :href="item.href"
      :aria-disabled="item.invalid || undefined"
      @click="guardInvalid"
    >{{ item.title }}</a>

    <div class="history-card__bottom">
      <p class="history-card__meta">
        <template v-if="item.authorName">
          <AppIcon name="user" :size="13" />
          <span class="history-card__author">{{ item.authorName }}</span>
          <span v-if="metaTime" class="history-card__dot">·</span>
        </template>
        <template v-if="metaTime">
          <AppIcon name="clock" :size="12" />
          <span>{{ metaTime }}</span>
        </template>
      </p>
      <!-- 普通态的直删（B站 实测：不确认）。管理态隐藏 —— 那时删除只能从工具栏走 -->
      <button
        v-if="!selectable"
        class="history-card__remove"
        type="button"
        :aria-label="`删除「${item.title}」的历史记录`"
        @click.stop="emit('remove')"
      >
        <AppIcon name="trash" :size="15" />
      </button>
    </div>
  </article>
</template>

<style scoped>
.history-card {
  display: block;
  min-width: 0;
}

.history-card__cover {
  position: relative;
  display: block;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-2);
}

.history-card__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}

.history-card:hover .history-card__img {
  transform: scale(1.03);
}

.history-card__cover-fallback {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-4);
  background: linear-gradient(135deg, #f1f2f3 0%, #e3e5e7 100%);
}

.history-card__invalid {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.62);
}

/* 封面底：半透明白条 + 右侧「已看/总长」；左下的粉色细线是观看进度 */
.history-card__footer {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  height: 24px;
  align-items: center;
  justify-content: flex-end;
  padding: 0 6px;
  background: rgba(255, 255, 255, 0.88);
}

.history-card__bar {
  position: absolute;
  bottom: 0;
  left: 0;
  height: 2px;
  background: var(--brand-pink);
}

.history-card__badge {
  font-size: 12px;
  line-height: 1;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}

.history-card__title {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  height: 44px;
  margin-top: 10px;
  font-size: var(--card-title-size);
  font-weight: 500;
  line-height: 22px;
  color: var(--text-1);
  word-break: break-word;
}

.history-card__title:hover {
  color: var(--brand-blue);
}

/* 管理态的勾选框：贴封面左上角，未选中是半透明白描边，选中是品牌蓝实底（B站 同形） */
.history-card__check {
  position: absolute;
  top: 6px;
  left: 6px;
  display: flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid rgba(255, 255, 255, 0.9);
  border-radius: 4px;
  color: #fff;
  background: rgba(0, 0, 0, 0.28);
  box-sizing: border-box;
}

.history-card__check.is-checked {
  border-color: var(--brand-blue);
  background: var(--brand-blue);
}

/* 管理态：整卡是选择器 ⇒ 手型 + 封面不缩放（否则像"能点进去"） */
.history-card.is-managing {
  cursor: pointer;
}

.history-card.is-managing:hover .history-card__img {
  transform: none;
}

.history-card.is-managing .history-card__title:hover {
  color: var(--text-1);
}

/* 底行：meta 占满，垃圾桶贴右（B站 普通态的样子） */
.history-card__bottom {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.history-card__meta {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  line-height: 18px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
}

.history-card__remove {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  color: var(--text-4);
  background: transparent;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s, color 0.15s, background 0.15s;
}

/* 常态淡出，hover 才亮：B站 也是如此（低对比的灰，避免每张卡都在喊"删我"） */
.history-card:hover .history-card__remove,
.history-card__remove:focus-visible {
  opacity: 1;
}

.history-card__remove:hover {
  color: var(--brand-pink);
  background: var(--bg-2);
}

/* 触屏 / 无 hover 的设备：直接常显（否则根本点不到） */
@media (hover: none) {
  .history-card__remove {
    opacity: 1;
  }
}

.history-card__author {
  max-width: 9em;
  overflow: hidden;
  text-overflow: ellipsis;
}

.history-card__dot {
  color: var(--text-4);
}

/* 失效：整卡去饱和、链接不可点 */
.history-card.is-invalid .history-card__cover,
.history-card.is-invalid .history-card__title {
  cursor: default;
}

.history-card.is-invalid .history-card__img,
.history-card.is-invalid .history-card__cover-fallback {
  filter: grayscale(1);
  opacity: 0.82;
}

.history-card.is-invalid .history-card__title {
  color: var(--text-3);
}
</style>
