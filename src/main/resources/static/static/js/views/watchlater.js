// ============================================================================
// 稍后再看 #/watchlater —— 我的"稍后再看"清单（后端 T17 / 前端 T19）
// 由左抽屉「稍后再看」进入；未登录显示锁定提示（同 favorite.js 的 setLock）。
//
// ★ 红线（favorite-需求.md §六 / favorite.md §九）：**除本清单外任何地方都看不出"是否在稍后再看"**
//   —— 本页只读 /watchlater/list；详情页那个「稍后再看」按钮点击后**不反映状态**（见 detail.js）。
// ★ 失效条目**照常渲染占位**（后端 WatchLaterItemVO 已给 invalid + 占位标题「内容已失效」，
//   前后端同口径）；占位封面由代码合成（灰底 + 内联 SVG），不引入图片素材（同 favorite.js）。
// ★ 每条提供「移出」与「移入收藏夹」；**不含**点赞 / 关注 / 收藏等互动态（红线②）。
//   失效卡片样式沿用一期占位实现（F-15 同族），本期不做样式专项（红线③）。
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn } from '../auth.js';
import { navigate } from '../router.js';
import { createChunkedList } from '../chunkedList.js';
import {
  showToast, emptyBox, escapeHtml, initialChar, avatarColor, formatTime, skeletonCards,
} from '../utils.js';

// 信封大小由后端稍后再看域常量（100）决定，请求只传 page；本地小批展示（12 = 3 行 4 列）
const CHUNK_SIZE = 100;
const BATCH_SIZE = 12;

// 失效占位封面图标：内联 SVG（代码合成，零请求、零素材；与 favorite.js 同款）
const ICON_INVALID = '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="2.4" '
  + 'stroke-linecap="round" stroke-linejoin="round"><rect x="7" y="11" width="34" height="26" rx="4"/>'
  + '<path d="M7 32l9-9 7 7"/><circle cx="32" cy="20" r="3"/><path d="M8 7l32 32"/></svg>';

let state = null;

export function mount(container) {
  state = { container, list: null, total: null, pickerContentId: null };
  if (!isLoggedIn()) { setLock(); return; }
  render();
  loadFirst();
}

export function unmount() {
  state = null;
}

function setLock() {
  state.container.innerHTML = '<div class="empty"><div class="empty-icon">🔒</div>'
    + '<div class="empty-msg">登录后可查看稍后再看</div>'
    + '<a class="btn-primary" href="#/login">去登录</a></div>';
}

// ---------- 外壳 ----------
function render() {
  state.container.innerHTML = `
    <div class="fav-page">
      <div class="fav-head">
        <h2>稍后再看</h2>
        <span class="fav-main-sub" id="wlCount"></span>
      </div>
      <div class="home-content" id="wlGrid"></div>
      <div class="load-more" id="wlMore"></div>
    </div>

    <div class="modal-overlay hidden" id="wlMoveOverlay">
      <div class="modal fav-modal">
        <div class="modal-title">移入收藏夹</div>
        <div class="fav-picker" id="wlPicker"></div>
        <div class="btn-row">
          <button class="btn-cancel" id="wlMoveCancel">取消</button>
        </div>
      </div>
    </div>`;

  state.container.querySelector('#wlMoveCancel').addEventListener('click', closeMovePicker);
  state.container.querySelector('#wlMoveOverlay').addEventListener('click', (e) => {
    if (e.target.id === 'wlMoveOverlay') closeMovePicker();
  });
}

// ---------- 列表加载 ----------
function ensureList() {
  if (!state.list) {
    state.list = createChunkedList({
      // 只传 page（信封大小后端定）；顺带记下 total 供标题展示
      fetchChunk: async (page) => {
        const res = await request(`watchlater/list?page=${page}`);
        state.total = res && res.total != null ? res.total : null;
        return res;
      },
      chunkSize: CHUNK_SIZE,
      batchSize: BATCH_SIZE,
      // WatchLaterItemVO **不含 id**，按 contentId 去重（唯一的行键）
      keyOf: (it) => it.contentId,
    });
  }
  return state.list;
}

async function loadFirst() {
  const grid = state.container.querySelector('#wlGrid');
  grid.innerHTML = '<div class="grid">' + skeletonCards(4) + '</div>';
  const list = ensureList();
  list.reset();
  try {
    const batch = await list.nextBatch();
    renderCount();
    renderItems(batch, false);
    renderMore();
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    grid.innerHTML = emptyBox('加载失败，请刷新重试');
  }
}

async function loadMoreItems(btn) {
  btn.disabled = true;
  btn.textContent = '加载中...';
  try {
    const batch = await state.list.nextBatch();
    renderItems(batch, true);
    renderMore();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = '加载更多';
    showToast('加载失败，请重试');
  }
}

function renderCount() {
  const el = state.container.querySelector('#wlCount');
  if (el) el.textContent = state.total != null ? state.total + ' 个内容' : '';
}

function renderMore() {
  const box = state.container.querySelector('#wlMore');
  box.innerHTML = '';
  if (!state.list || !state.list.hasMore()) return;
  const btn = document.createElement('button');
  btn.className = 'load-more-btn';
  btn.textContent = '加载更多';
  btn.addEventListener('click', () => loadMoreItems(btn));
  box.appendChild(btn);
}

function renderItems(batch, append) {
  const gridBox = state.container.querySelector('#wlGrid');
  let grid = gridBox.querySelector('.grid');
  if (!append) {
    if (!batch.length) {
      gridBox.innerHTML = emptyBox('稍后再看还是空的，去详情页加一个吧', '🕐');
      return;
    }
    gridBox.innerHTML = '';
    grid = document.createElement('div');
    grid.className = 'grid';
    gridBox.appendChild(grid);
  } else if (!grid) {
    return;
  }
  batch.forEach((it, i) => grid.appendChild(createWatchLaterCard(it, i)));
}

// ---------- 卡片（正常 / 失效占位） ----------
/** 正常卡片：封面缺失/加载失败 → 首字渐变占位；点击打开内容详情（用 contentId）。 */
function createWatchLaterCard(item, index) {
  const card = document.createElement('div');
  card.className = 'v-card';
  if (index != null && index < BATCH_SIZE) card.style.animationDelay = (index % 12) * 45 + 'ms';

  const cover = document.createElement('div');
  cover.className = 'v-card-cover';

  if (item.invalid) {
    // 失效占位（同收藏列表口径）：封面无素材（灰底 + 内联 SVG）、标题是后端给的「内容已失效」、
    // 不显示作者、**不可点开详情** —— 但仍保留两个动作（占位看得见就得删得掉 / 挪得走）
    cover.classList.add('invalid-cover');
    cover.innerHTML = ICON_INVALID;
    card.classList.add('invalid-card');
    card.appendChild(cover);
    card.appendChild(cardBody(item.title, '', item.addedTime));
  } else {
    const fallback = document.createElement('div');
    fallback.className = 'cover-fallback';
    fallback.textContent = initialChar(item.title || item.authorName);
    fallback.style.background = avatarColor(item.title || item.authorName);
    cover.appendChild(fallback);
    if (item.coverUrl) {
      const img = document.createElement('img');
      img.className = 'cover-img';
      img.src = item.coverUrl;
      img.alt = '';
      img.loading = 'lazy';
      img.addEventListener('error', () => img.remove());
      cover.appendChild(img);
    }
    card.appendChild(cover);
    card.appendChild(cardBody(item.title, item.authorName, item.addedTime));
    card.addEventListener('click', () => navigate('/video/' + item.contentId));
  }
  attachActions(card, item.contentId);
  return card;
}

function cardBody(title, authorName, addedTime) {
  const body = document.createElement('div');
  body.className = 'v-card-body';
  const titleEl = document.createElement('div');
  titleEl.className = 'v-card-title';
  titleEl.textContent = title || '';
  const meta = document.createElement('div');
  meta.className = 'v-card-meta';
  const up = document.createElement('span');
  up.className = 'up-name';
  up.textContent = authorName || '';
  const time = document.createElement('span');
  time.className = 'meta-stats';
  time.textContent = addedTime ? '加入于 ' + formatTime(addedTime) : '';
  meta.appendChild(up);
  meta.appendChild(time);
  body.appendChild(titleEl);
  body.appendChild(meta);
  return body;
}

// 每项两个动作（不含任何互动态）：移出 / 移入收藏夹
function attachActions(card, contentId) {
  const box = card.querySelector('.v-card-body');
  const moveBtn = document.createElement('button');
  moveBtn.className = 'fav-remove-btn';
  moveBtn.textContent = '移入收藏夹';
  moveBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    openMovePicker(contentId);
  });
  const removeBtn = document.createElement('button');
  removeBtn.className = 'fav-remove-btn';
  removeBtn.textContent = '移出';
  removeBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    removeItem(contentId);
  });
  box.appendChild(moveBtn);
  box.appendChild(removeBtn);
}

// ---------- 写操作：移出 ----------
async function removeItem(contentId) {
  try {
    await request(`watchlater/remove?contentIds=${contentId}`, { method: 'POST' });
    showToast('已移出');
    await loadFirst();
  } catch (e) {
    showToast(e.message || '移出失败');
  }
}

// ---------- 写操作：移入收藏夹（选夹弹窗） ----------
/**
 * 选夹弹窗：首行恒为「默认收藏夹」（folderId 缺省 ⇒ 后端懒建 / 复用现有默认夹），
 * 随后是已有的**自建夹**（过滤掉默认夹，避免与首行重复）。
 * 选中即调 POST /watchlater/move?contentId=X[&folderId=N]（同事务：加入目标夹 + 从池中移除）。
 */
function openMovePicker(contentId) {
  state.pickerContentId = contentId;
  const c = state.container;
  c.querySelector('#wlMoveOverlay').classList.remove('hidden');
  loadPicker();
}

function closeMovePicker() {
  const overlay = state.container.querySelector('#wlMoveOverlay');
  if (overlay) overlay.classList.add('hidden');
  state.pickerContentId = null;
}

async function loadPicker() {
  const picker = state.container.querySelector('#wlPicker');
  picker.innerHTML = '<div class="fav-pick-empty">加载中...</div>';
  let folders;
  try {
    folders = (await request('favorite/folder/list')) || [];
  } catch (e) {
    picker.innerHTML = '<div class="fav-pick-empty">加载失败</div>';
    return;
  }
  const rows = [{ id: null, name: '默认收藏夹' }].concat(folders.filter((f) => !f.isDefault));
  picker.innerHTML = '';
  rows.forEach((f) => {
    const row = document.createElement('div');
    row.className = 'fav-pick-row';
    const name = document.createElement('span');
    name.className = 'fp-name';
    name.textContent = f.name;
    const cnt = document.createElement('span');
    cnt.className = f.id == null ? 'fp-hint' : 'fp-count';
    cnt.textContent = f.id == null
      ? '首次移入时创建'
      : (f.itemCount != null ? f.itemCount + ' 个内容' : '');
    row.appendChild(name);
    row.appendChild(cnt);
    row.addEventListener('click', () => doMove(f));
    picker.appendChild(row);
  });
}

async function doMove(folder) {
  const contentId = state.pickerContentId;
  if (contentId == null) return;
  const url = folder.id == null
    ? `watchlater/move?contentId=${contentId}`
    : `watchlater/move?contentId=${contentId}&folderId=${folder.id}`;
  try {
    await request(url, { method: 'POST' });
    showToast('已移入收藏夹');
    closeMovePicker();
    await loadFirst();
  } catch (e) {
    // 409 = 目标夹已有同内容（稍后再看条目**保留**）；其余按后端文案提示
    showToast(e.message || '移动失败');
  }
}
