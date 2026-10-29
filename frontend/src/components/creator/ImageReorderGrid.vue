<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import type { EditImage } from '../../api/creatorEdit'

/**
 * 图文图片墙（编辑态）：换序 / 换源 / 删除 / 追加。
 *
 * <h2>★ 换序交互：长按选中 → 点目标即交换</h2>
 * 需求口径是"长按某张图片，和另一张图片调换顺序"。这里**不用拖拽**，而是
 * "长按进入换序态 → 点另一张交换"，理由三条：
 * <ol>
 *   <li><b>触屏上拖拽与页面滚动会打架</b>：手指在图片上纵向下滑本来该滚页面，
 *       一旦进入拖拽就要 {@code preventDefault} + {@code touch-action:none}，
 *       结果是"想滚动却把图拖走了"。长按不移动不会抢滚动手势。</li>
 *   <li><b>交换是离散操作</b>（B 站图文也没有拖拽排序）：两图互换 → 保存，
 *       中间态只有"选中了哪一张"，比"拖到第几个缝隙"更好解释。</li>
 *   <li>纯逻辑可以是 {@code swapImages(from,to)}，能单测；拖拽坐标只能靠截图回归。</li>
 * </ol>
 * 长按阈值 420ms：低于它太快（滑动起手容易误触），高于它像卡住。
 * 指针移动超过 10px 取消计时（= 用户其实在滚页面）。
 *
 * <p>鼠标/键盘用户不必长按：直接点一张也会选中（点过就退？不会 —— 选中态一直保留到
 * 交换 / 取消），键盘 Enter 等价于点击，Esc 取消。
 */
const props = defineProps<{ images: EditImage[]; disabled?: boolean }>()
const emit = defineEmits<{
  (event: 'swap', from: number, to: number): void
  (event: 'remove', index: number): void
  (event: 'replace', index: number): void
  (event: 'add'): void
}>()

const LONG_PRESS_MS = 420
const MOVE_TOLERANCE_PX = 10

const selected = ref<number | null>(null)

let timer: number | null = null
let pressIndex = -1
let startX = 0
let startY = 0
/** 长按刚触发时浏览器还会补一次 click —— 吞掉它，否则会被当成"点自己=取消" */
let swallowClick = false

function clearTimer(): void {
  if (timer !== null) {
    window.clearTimeout(timer)
    timer = null
  }
}

function onPointerDown(index: number, event: PointerEvent): void {
  if (props.disabled || selected.value !== null) return
  pressIndex = index
  startX = event.clientX
  startY = event.clientY
  clearTimer()
  timer = window.setTimeout(() => {
    timer = null
    if (pressIndex !== index) return
    selected.value = index
    swallowClick = true
  }, LONG_PRESS_MS)
}

function onPointerMove(event: PointerEvent): void {
  if (timer === null) return
  if (Math.abs(event.clientX - startX) > MOVE_TOLERANCE_PX || Math.abs(event.clientY - startY) > MOVE_TOLERANCE_PX) {
    clearTimer()
    pressIndex = -1
  }
}

function onPointerUp(): void {
  clearTimer()
  pressIndex = -1
}

/** 点一下：未选中 ⇒ 选中；点自己 ⇒ 取消；点别人 ⇒ 交换。 */
function onClick(index: number): void {
  if (props.disabled) return
  if (swallowClick) {
    swallowClick = false
    return
  }
  if (selected.value === null) {
    selected.value = index
    return
  }
  if (selected.value === index) {
    selected.value = null
    return
  }
  emit('swap', selected.value, index)
  selected.value = null
}

function cancel(): void {
  selected.value = null
  swallowClick = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') cancel()
}

onBeforeUnmount(clearTimer)
</script>

<template>
  <div class="image-order" @keydown="onKeydown">
    <p v-if="selected !== null" class="image-order__hint" role="status">
      已选中第 {{ selected + 1 }} 张 —— 点另一张图片交换位置，点自己或按 Esc 取消
    </p>
    <p v-else class="image-order__tip">长按某张图片（或直接点击）选中，再点另一张即可交换顺序</p>

    <div class="image-order__grid">
      <div
        v-for="(image, index) in images"
        :key="image.url"
        class="tile"
        :class="{ 'is-selected': selected === index }"
      >
        <button
          class="tile__hit"
          type="button"
          :disabled="disabled"
          :aria-label="`第 ${index + 1} 张图片${selected === index ? '（已选中，点另一张交换）' : ''}`"
          :aria-pressed="selected === index"
          @click="onClick(index)"
          @pointerdown="onPointerDown(index, $event)"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
        >
          <img :src="image.url" alt="" draggable="false" loading="lazy" />
        </button>
        <span class="tile__index">{{ index + 1 }}</span>
        <span v-if="image.isNew" class="tile__new">新</span>
        <div class="tile__ops">
          <button type="button" :disabled="disabled" @click.stop="emit('replace', index)">换源</button>
          <button type="button" :disabled="disabled" @click.stop="emit('remove', index)">删除</button>
        </div>
      </div>

      <button class="tile tile--add" type="button" :disabled="disabled" @click="emit('add')">
        <span class="tile--add__plus">+</span>
        <span>添加图片</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.image-order__hint,
.image-order__tip {
  margin-bottom: 10px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}

.image-order__hint {
  color: var(--brand-blue);
}

.image-order__grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.tile {
  position: relative;
  width: 120px;
  height: 120px;
  overflow: hidden;
  border-radius: var(--radius-sm);
  background: var(--bg-1);
}

.tile.is-selected {
  outline: 2px solid var(--brand-blue);
  outline-offset: -2px;
}

.tile__hit {
  display: block;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: none;
  cursor: grab;
  touch-action: manipulation;
}

.tile__hit img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  user-select: none;
}

.tile__index {
  position: absolute;
  top: 4px;
  left: 4px;
  min-width: 18px;
  padding: 0 4px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  pointer-events: none;
}

.tile__new {
  position: absolute;
  top: 4px;
  right: 4px;
  padding: 0 4px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 18px;
  color: var(--text-inverse);
  background: #00b42a;
  pointer-events: none;
}

.tile__ops {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  justify-content: space-around;
  padding: 3px 0;
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.tile:hover .tile__ops,
.tile:focus-within .tile__ops,
.tile.is-selected .tile__ops {
  opacity: 1;
}

.tile__ops button {
  color: #fff;
  font-size: 11px;
}

.tile__ops button:hover:not(:disabled) {
  color: var(--brand-blue);
}

.tile--add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px dashed var(--line-1);
  font-size: 12px;
  color: var(--text-3);
}

.tile--add:hover:not(:disabled) {
  border-color: var(--brand-blue);
  color: var(--brand-blue);
}

.tile--add__plus {
  font-size: 20px;
  line-height: 1;
}

@media (max-width: 640px) {
  .tile {
    width: 96px;
    height: 96px;
  }
}
</style>
