// ============================================================================
// 收藏夹 #/favorite —— 我的夹列表（建 / 改名 / 私密开关 / 删）+ 夹内内容（含失效占位）
// 由左抽屉「收藏」进入；未登录显示锁定提示。
//
// ★ 失效卡片占位封面**由代码合成**（灰底 + 内联 SVG），不引入任何图片素材：
//   后端按 R-03 契约对失效条目返回 coverUrl=null（"占位卡片不给素材"），
//   前端本就必须自己画这张底 —— 加素材只是多一个静态依赖，省不掉这条分支。
//   样式细节归三期（CURRENT_NEEDS §四反面清单），本期只保证"看得见、不泄漏、删得掉"。
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn } from '../auth.js';
import { navigate } from '../router.js';
import { createChunkedList } from '../chunkedList.js';
import {
  showToast, emptyBox, escapeHtml, initialChar, avatarColor, formatTime, skeletonCards,
} from '../utils.js';

// 信封大小由后端收藏域常量（100）决定，请求只传 page；本地小批展示（12 = 3 行 4 列）
const CHUNK_SIZE = 100;
const BATCH_SIZE = 12;

// 失效占位封面图标：内联 SVG（代码合成，零请求、零素材）
const ICON_INVALID = '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="2.4" '
  + 'stroke-linecap="round" stroke-linejoin="round"><rect x="7" y="11" width="34" height="26" rx="4"/>'
  + '<path d="M7 32l9-9 7 7"/><circle cx="32" cy="20" r="3"/><path d="M8 7l32 32"/></svg>';

let state = null;

export function mount(container) {
  state = { container, folders: [], currentId: null, list: null, nameMode: 'create', nameFolderId: null };
  if (!isLoggedIn()) { setLock(); return; }
  render();
  loadFolders();
}

export function unmount() {
  state = null;
}

function setLock() {
  state.container.innerHTML = '<div class="empty"><div class="empty-icon">🔒</div>'
    + '<div class="empty-msg">登录后可查看收藏夹</div>'
    + '<a class="btn-primary" href="#/login">去登录</a></div>';
}

// ---------- 外壳 ----------
function render() {
  state.container.innerHTML = `
    <div class="fav-page">
      <div class="fav-head">
        <h2>我的收藏夹</h2>
        <button class="btn-primary" id="favNewBtn">+ 新建收藏夹</button>
      </div>
      <div class="fav-layout">
        <aside class="fav-folders" id="favFolders"></aside>
        <section class="fav-main" id="favMain"></section>
      </div>
    </div>

    <div class="modal-overlay hidden" id="favNameOverlay">
      <div class="modal">
        <div class="modal-title" id="favNameTitle">新建收藏夹</div>
        <input class="input" id="favNameInput" placeholder="夹名（最多 30 字）" maxlength="30">
        <div class="btn-row">
          <button class="btn-cancel" id="favNameCancel">取消</button>
          <button class="btn-confirm" id="favNameConfirm">确定</button>
        </div>
      </div>
    </div>`;

  state.container.querySelector('#favNewBtn').addEventListener('click', () => openNameModal('create'));
  state.container.querySelector('#favNameCancel').addEventListener('click', closeNameModal);
  state.container.querySelector('#favNameConfirm').addEventListener('click', confirmNameModal);
  state.container.querySelector('#favNameOverlay').addEventListener('click', (e) => {
    if (e.target.id === 'favNameOverlay') closeNameModal();
  });
  state.container.querySelector('#favNameInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') confirmNameModal();
  });
}

// ---------- 夹列表 ----------
async function loadFolders() {
  const fbox = state.container.querySelector('#favFolders');
  fbox.innerHTML = '<div class="fav-folders-empty">加载中...</div>';
  let folders;
  try {
    folders = (await request('favorite/folder/list')) || [];
  } catch (e) {
    fbox.innerHTML = '<div class="fav-folders-empty">加载失败</div>';
    return;
  }
  state.folders = folders;
  // 当前选中夹失效（被删 / 首次进入）→ 回落到默认夹（或第一个）
  if (!state.folders.some((f) => f.id === state.currentId)) {
    const def = state.folders.find((f) => f.isDefault) || state.folders[0];
    state.currentId = def ? def.id : null;
  }
  renderFolders();
  loadItems();
}

function renderFolders() {
  const fbox = state.container.querySelector('#favFolders');
  fbox.innerHTML = '';
  if (!state.folders.length) {
    fbox.innerHTML = '<div class="fav-folders-empty">还没有收藏夹<br>收藏内容时会自动创建默认夹</div>';
    return;
  }
  state.folders.forEach((f) => {
    const row = document.createElement('div');
    row.className = 'fav-folder-item' + (f.id === state.currentId ? ' active' : '');
    row.innerHTML = `
      <div class="ff-top">
        <span class="ff-name">${escapeHtml(f.name)}</span>
        ${f.isDefault ? '<span class="ff-badge">默认</span>' : ''}
        ${f.isPrivate ? '<span class="ff-badge private">私密</span>' : ''}
        <span class="ff-count">${f.itemCount}</span>
      </div>
      <div class="ff-acts">
        <button class="ff-act" data-act="rename">改名</button>
        <button class="ff-act" data-act="private">${f.isPrivate ? '设为公开' : '设为私密'}</button>
        ${f.isDefault ? '' : '<button class="ff-act danger" data-act="remove">删除</button>'}
      </div>`;
    row.addEventListener('click', () => selectFolder(f.id));
    row.querySelector('.ff-acts').addEventListener('click', (e) => {
      e.stopPropagation();
      const act = e.target.dataset ? e.target.dataset.act : null;
      if (act === 'rename') openNameModal('rename', f);
      else if (act === 'private') togglePrivate(f);
      else if (act === 'remove') removeFolder(f);
    });
    fbox.appendChild(row);
  });
}

function selectFolder(id) {
  if (id === state.currentId) return;
  state.currentId = id;
  renderFolders();
  loadItems();
}

// ---------- 夹内内容 ----------
function loadItems() {
  const main = state.container.querySelector('#favMain');
  if (state.currentId == null) {
    main.innerHTML = emptyBox('还没有收藏夹，去首页收藏点内容吧', '📁');
    return;
  }
  const folder = state.folders.find((f) => f.id === state.currentId) || {};
  main.innerHTML = `
    <div class="fav-main-head">
      <span class="fav-main-title">${escapeHtml(folder.name || '')}</span>
      <span class="fav-main-sub">${folder.itemCount || 0} 个内容</span>
    </div>
    <div class="home-content" id="favGrid"></div>
    <div class="load-more" id="favMore"></div>`;

  state.list = createChunkedList({
    fetchChunk: async (page) => request(`favorite/list?folderId=${state.currentId}&page=${page}`),
    chunkSize: CHUNK_SIZE,
    batchSize: BATCH_SIZE,
    keyOf: (it) => it.id,
  });

  const grid = main.querySelector('#favGrid');
  grid.innerHTML = '<div class="grid">' + skeletonCards(4) + '</div>';
  state.list.nextBatch()
    .then((batch) => { renderItems(batch, false); renderMore(); })
    .catch((e) => {
      if (e.code === 401 || e.code === 403) return;
      grid.innerHTML = emptyBox('加载失败，请刷新重试');
    });
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

function renderMore() {
  const box = state.container.querySelector('#favMore');
  box.innerHTML = '';
  if (!state.list || !state.list.hasMore()) return;
  const btn = document.createElement('button');
  btn.className = 'load-more-btn';
  btn.textContent = '加载更多';
  btn.addEventListener('click', () => loadMoreItems(btn));
  box.appendChild(btn);
}

function renderItems(batch, append) {
  const gridBox = state.container.querySelector('#favGrid');
  let grid = gridBox.querySelector('.grid');
  if (!append) {
    if (!batch.length) {
      gridBox.innerHTML = emptyBox('这个收藏夹还是空的', '📁');
      return;
    }
    gridBox.innerHTML = '';
    grid = document.createElement('div');
    grid.className = 'grid';
    gridBox.appendChild(grid);
  } else if (!grid) {
    return;
  }
  batch.forEach((it, i) => {
    const card = it.invalid ? createInvalidCard(it) : createFavoriteCard(it, i);
    grid.appendChild(card);
  });
}

// ---------- 卡片（正常 / 失效占位） ----------
/**
 * 正常收藏卡片：封面缺失/加载失败 → 首字渐变占位（与首页卡片同一套兜底）。
 * 点击打开内容详情（★ 用 contentId，不是收藏记录 id）；作者名本期不可点
 * （FavoriteItemVO 刻意不含 authorId —— T6 键集注释：要它得先回需求篇）。
 */
function createFavoriteCard(item, index) {
  const card = document.createElement('div');
  card.className = 'v-card';
  if (index != null) card.style.animationDelay = (index % 12) * 45 + 'ms';

  const cover = document.createElement('div');
  cover.className = 'v-card-cover';
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
  card.appendChild(cardBody(item.title, item.authorName, item.favoriteTime));
  attachRemove(card, item.contentId);
  card.addEventListener('click', () => navigate('/video/' + item.contentId));
  return card;
}

/**
 * 失效占位卡片（R-03 / R-11）：**封面无素材**（灰底 + 代码合成的内联 SVG 破图图标），
 * 标题位是后端给的固定文案「内容已失效」，**不显示作者**（后端已返回 null）。
 * 不可点开详情（内容已打不开），但保留「移出」——占位看得见就得删得掉（G11）。
 */
function createInvalidCard(item) {
  const card = document.createElement('div');
  card.className = 'v-card invalid-card';

  const cover = document.createElement('div');
  cover.className = 'v-card-cover invalid-cover';
  cover.innerHTML = ICON_INVALID;
  card.appendChild(cover);
  card.appendChild(cardBody(item.title, '', item.favoriteTime));
  attachRemove(card, item.contentId);
  return card;
}

function cardBody(title, authorName, favoriteTime) {
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
  time.textContent = favoriteTime ? '收藏于 ' + formatTime(favoriteTime) : '';
  meta.appendChild(up);
  meta.appendChild(time);
  body.appendChild(titleEl);
  body.appendChild(meta);
  return body;
}

function attachRemove(card, contentId) {
  const btn = document.createElement('button');
  btn.className = 'fav-remove-btn';
  btn.textContent = '移出';
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    removeItems(contentId);
  });
  card.querySelector('.v-card-body').appendChild(btn);
}

// ---------- 写操作 ----------
async function removeItems(contentId) {
  try {
    await request(`favorite/remove?folderId=${state.currentId}&contentIds=${contentId}`, { method: 'POST' });
    showToast('已移出');
    await loadFolders();
  } catch (e) {
    showToast(e.message || '移出失败');
  }
}

async function removeFolder(folder) {
  if (!window.confirm(`确定删除收藏夹「${folder.name}」？夹内收藏记录将一并删除`)) return;
  try {
    await request(`favorite/folder/remove?folderId=${folder.id}`, { method: 'POST' });
    showToast('已删除');
    await loadFolders();
  } catch (e) {
    showToast(e.message || '删除失败');
  }
}

async function togglePrivate(folder) {
  const next = folder.isPrivate ? 0 : 1;
  try {
    await request(`favorite/folder/update?folderId=${folder.id}&isPrivate=${next}`, { method: 'POST' });
    await loadFolders();
  } catch (e) {
    showToast(e.message || '操作失败');
  }
}

// ---------- 新建 / 改名（共用一个小弹窗） ----------
function openNameModal(mode, folder) {
  state.nameMode = mode;
  state.nameFolderId = folder ? folder.id : null;
  const c = state.container;
  c.querySelector('#favNameTitle').textContent = mode === 'create' ? '新建收藏夹' : '修改夹名';
  const input = c.querySelector('#favNameInput');
  input.value = mode === 'rename' ? folder.name : '';
  c.querySelector('#favNameOverlay').classList.remove('hidden');
  input.focus();
}

function closeNameModal() {
  state.container.querySelector('#favNameOverlay').classList.add('hidden');
}

async function confirmNameModal() {
  const input = state.container.querySelector('#favNameInput');
  const name = input.value.trim();
  if (!name) { showToast('夹名不能为空'); return; }
  try {
    if (state.nameMode === 'create') {
      await request(`favorite/folder/add?name=${encodeURIComponent(name)}`, { method: 'POST' });
      showToast('创建成功');
    } else {
      await request(`favorite/folder/update?folderId=${state.nameFolderId}&name=${encodeURIComponent(name)}`, { method: 'POST' });
      showToast('修改成功');
    }
    closeNameModal();
    await loadFolders();
  } catch (e) {
    showToast(e.message || '操作失败');
  }
}
