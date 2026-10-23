// ============================================================================
// 详情 #/video/:id —— 左播放器 + 标题/UP主/简介 + 评论区 + 右侧相关推荐(用 /start 兜底)
// ============================================================================

import { request, coinAdd } from '../api.js';
import { isLoggedIn, getUserId } from '../auth.js';
import { showToast, formatTime, formatNumber, emptyBox, initialChar, avatarColor } from '../utils.js';
import { navigate } from '../router.js';
import { openEditWorkModal } from '../editWork.js';
import { createChunkedList } from '../chunkedList.js';

// T10-B：评论列表"大 chunk + 本地小批"——一次拉 CHUNK_SIZE 主楼，本地按 BATCH_SIZE 小批展示，
// 本地余量用尽才发下一次 chunk 请求（公共 helper createChunkedList，T11 复用）。
// T11-B：**请求只传 `page`**——信封大小由后端评论域常量（200）决定，CHUNK_SIZE 只作兜底值，
// 首次响应后用响应回显的 pageSize 自适应覆盖（见 chunkedList.js）。
const CHUNK_SIZE = 200;
const BATCH_SIZE = 10;

let state = null;

export function mount(container, params) {
  state = {
    container,
    contentId: params.id,
    content: null,
    commentList: null,      // T10-B：createChunkedList 实例（评论主楼大 chunk + 本地小批）
    displayedComments: 0,   // 已展示条数（本地小批累积）
    expandedRoots: {},      // T10-B：rootId → { replies }（已展开的楼中楼全量；T14 起游标拉齐后重灌）
    related: [],
    parentId: null,
    isFavorited: false,      // 收藏按钮态（T7；T10 起初始值读详情内联 interaction.isFavorited，弹窗仍走 /favorite/status 取 folders）
    // 收藏数不再单独存：T43 起由详情响应内联到 state.content.favoriteCount（动作后仍用 /favorite/count 刷新）
    pickFolders: null,       // 收藏弹窗最近一次拉到的"我的夹"列表
    coinBalance: null,       // T41：我的硬币余额（投币弹层显示；写成功由响应体回带的 balance 就地覆盖）
  };
  if (!state.contentId) {
    container.innerHTML = emptyBox('缺少内容 ID');
    return;
  }
  render();
  init();
}

export function unmount() {
  state = null;
}

function render() {
  state.container.innerHTML = `
    <div class="detail">
      <div class="detail-main">
        <div class="player" id="player"></div>
        <div class="detail-gallery" id="gallery" style="display:none"></div>
        <div class="detail-title" id="title"></div>
        <div class="detail-stats" id="stats"></div>
        <div class="author-row">
          <div class="author-avatar" id="authorAvatar"></div>
          <span class="author-name" id="authorName"></span>
          <span class="author-spacer"></span>
          <button class="follow-btn unfollowed" id="followBtn" style="display:none">+ 关注</button>
        </div>
        <div class="detail-actions">
          <button class="like-btn" id="likeBtn">❤ 点赞 <span id="likeCount">0</span></button>
          <button class="like-btn" id="coinBtn" style="display:none">🪙 投币</button>
          <button class="like-btn" id="favBtn">⭐ 收藏</button>
          <button class="like-btn" id="wlBtn">🕐 稍后再看</button>
          <button class="edit-btn" id="editBtn" style="display:none">✏ 编辑</button>
        </div>
        <div class="detail-desc" id="desc"></div>
        <div class="comment-section">
          <h3 id="commentHeader">评论</h3>
          <div class="comment-input-row" id="commentInputRow" style="display:none">
            <input class="input" id="commentInput" placeholder="发一条友善的评论" maxlength="500">
            <button class="btn-primary" id="sendBtn">发送</button>
          </div>
          <div class="comment-list" id="commentList"></div>
          <div class="load-more" id="commentMore"></div>
        </div>
      </div>
      <div class="detail-side">
        <div class="side-card">
          <div class="side-title">相关推荐</div>
          <div id="relatedList"><div class="empty"><div class="empty-msg">加载中...</div></div></div>
        </div>
      </div>
    </div>

    <div class="modal-overlay hidden" id="favOverlay">
      <div class="modal fav-modal">
        <div class="modal-title">收藏到</div>
        <div class="fav-picker" id="favPicker"></div>
        <div class="fav-new-row">
          <input class="input" id="favNewName" placeholder="新建收藏夹" maxlength="30">
          <button class="fav-new-btn" id="favCreateBtn">创建</button>
        </div>
        <div class="btn-row">
          <button class="btn-confirm" id="favDone">完成</button>
        </div>
      </div>
    </div>

    <div class="modal-overlay hidden" id="coinOverlay">
      <div class="modal coin-modal">
        <div class="modal-title">投币</div>
        <div class="coin-balance-row">我的硬币：<span id="coinBalanceTxt">-</span></div>
        <div class="coin-opt-row">
          <button class="coin-opt" id="coinOpt1">投 1 枚</button>
          <button class="coin-opt" id="coinOpt2">投 2 枚</button>
        </div>
        <div class="btn-row">
          <button class="btn-cancel" id="coinCancel">取消</button>
        </div>
      </div>
    </div>`;

  const input = state.container.querySelector('#commentInput');
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendComment(); }
  });
  state.container.querySelector('#sendBtn').addEventListener('click', sendComment);

  // 收藏弹窗（选夹）
  state.container.querySelector('#favDone').addEventListener('click', closeFavPicker);
  state.container.querySelector('#favOverlay').addEventListener('click', (e) => {
    if (e.target.id === 'favOverlay') closeFavPicker();
  });
  state.container.querySelector('#favCreateBtn').addEventListener('click', createFolderFromPicker);
  state.container.querySelector('#favNewName').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') createFolderFromPicker();
  });

  // 投币弹层（T41）：两个枚数选项 + 取消；点遮罩也关闭（同收藏弹窗）
  state.container.querySelector('#coinCancel').addEventListener('click', closeCoinModal);
  state.container.querySelector('#coinOverlay').addEventListener('click', (e) => {
    if (e.target.id === 'coinOverlay') closeCoinModal();
  });
  state.container.querySelector('#coinOpt1').addEventListener('click', () => doCoinAdd(1));
  state.container.querySelector('#coinOpt2').addEventListener('click', () => doCoinAdd(2));
}

async function init() {
  try {
    const [content] = await Promise.all([
      request(`api/v1/contents/${state.contentId}`),
      loadInitialComments(),
    ]);
    state.content = content;
    renderContent();
    loadRelated();
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    state.container.querySelector('#player').innerHTML = emptyBox(e.message || '加载失败，请刷新重试');
  }
}

async function loadRelated() {
  try {
    const list = await request('api/v1/contents/recommended');
    state.related = list || [];
  } catch (e) {
    state.related = [];
  }
  renderRelated();
}

function renderContent() {
  const c = state.container;
  const item = state.content;
  if (!item) return;

  // 媒体区
  const player = c.querySelector('#player');
  player.innerHTML = '';
  if (item.type === 1) {
    const video = document.createElement('video');
    video.src = item.videoUrl || '';
    video.controls = true;
    if (item.coverUrl) video.poster = item.coverUrl;
    video.addEventListener('error', () => {
      if (!item.coverUrl) player.innerHTML = emptyBox('视频加载失败');
    });
    player.appendChild(video);
  } else {
    // 图文：封面为主图，其余图进画廊（点缩略图切换主图）
    const images = [];
    if (item.coverUrl) images.push(item.coverUrl);
    if (item.imageUrls) images.push(...item.imageUrls);
    if (images.length) {
      const main = document.createElement('img');
      main.className = 'player-cover';
      main.src = images[0];
      main.alt = item.title || '';
      player.appendChild(main);
      if (images.length > 1) {
        const gallery = c.querySelector('#gallery');
        gallery.style.display = 'flex';
        gallery.innerHTML = '';
        images.forEach((url) => {
          const img = document.createElement('img');
          img.src = url;
          img.alt = '';
          img.loading = 'lazy';
          img.addEventListener('click', () => { main.src = url; });
          gallery.appendChild(img);
        });
      }
    }
  }

  c.querySelector('#title').textContent = item.title || '';
  renderStats();

  const authorName = item.authorName || '未知作者';
  const avatar = c.querySelector('#authorAvatar');
  avatar.textContent = initialChar(authorName);
  avatar.style.background = avatarColor(authorName);
  avatar.addEventListener('click', () => { if (item.authorId != null) navigate('/user/' + item.authorId); });
  const nameEl = c.querySelector('#authorName');
  nameEl.textContent = authorName;
  nameEl.addEventListener('click', () => { if (item.authorId != null) navigate('/user/' + item.authorId); });

  const followBtn = c.querySelector('#followBtn');
  if (item.authorId != null) {
    followBtn.style.display = '';
    updateFollowBtn();
    followBtn.addEventListener('click', toggleFollow);
  }

  const likeBtn = c.querySelector('#likeBtn');
  updateLikeBtn();
  c.querySelector('#likeCount').textContent = item.likeCount || 0;
  likeBtn.addEventListener('click', toggleContentLike);

  // 投币（T41 / R-17）：按钮只对**视频**渲染（图文帖不含投币这一维）；展示态 + onclick 收敛在 updateCoinBtn
  updateCoinBtn();

  // 收藏（T7 / T10）：按钮初始态读详情内联 interaction.isFavorited；公开计数仍走 /favorite/count
  // （onclick 赋值，避免重复渲染叠加监听）
  updateFavBtn();
  c.querySelector('#favBtn').onclick = openFavPicker;
  initFavorite();

  // 稍后再看（T19）：★ 按钮**不做"已加入"持久态**、外观恒不变 —— 点击只发 add + 弹提示。
  // 红线见favorite-需求.md §六 / favorite.md §九（除 /watchlater/list 外任何地方都看不出"是否在稍后再看"）。
  // onclick 赋值（同 favBtn）避免重复渲染叠加监听。
  c.querySelector('#wlBtn').onclick = addWatchLater;

  // 作者本人可编辑作品（改标题/简介 + 替换/删除媒体）；onclick 赋值避免重复渲染时监听叠加
  const editBtn = c.querySelector('#editBtn');
  if (item.authorId != null && item.authorId === getUserId()) {
    editBtn.style.display = '';
    editBtn.onclick = () => openEditWorkModal(item, reloadAfterEdit);
  } else {
    editBtn.style.display = 'none';
    editBtn.onclick = null;
  }

  c.querySelector('#desc').textContent = item.description || '';

  // 评论区：作者关闭后整体不可见/不可发（数据保留，重新开启即恢复）
  if (item.commentEnabled === false) {
    c.querySelector('#commentInputRow').style.display = 'none';
    c.querySelector('#commentHeader').textContent = '评论区已关闭';
    c.querySelector('#commentList').innerHTML = '<div class="empty-comments">作者已关闭评论区</div>';
    c.querySelector('#commentMore').innerHTML = '';
  } else {
    // 评论输入（登录后显示）
    if (isLoggedIn()) c.querySelector('#commentInputRow').style.display = '';
    renderComments();
  }
}

// 编辑作品完成后重新拉取详情并渲染
async function reloadAfterEdit() {
  try {
    state.content = await request(`api/v1/contents/${state.contentId}`);
    renderContent();
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
  }
}

/**
 * 详情内联互动态（R-10 §六）：赞 / 藏 / 关注三维在 `content.interaction` 子对象里。
 * 匿名（或 token 已失效）⇒ `interaction` 为 null ⇒ 返回空对象，三个读点一律按"未点亮"处理。
 */
function interactionOf(item) {
  return (item && item.interaction) || {};
}

function updateFollowBtn() {
  const btn = state.container.querySelector('#followBtn');
  const followed = interactionOf(state.content).isFollowed === true;
  btn.textContent = followed ? '已关注' : '+ 关注';
  btn.className = 'follow-btn ' + (followed ? 'followed' : 'unfollowed');
}

function updateLikeBtn() {
  const btn = state.container.querySelector('#likeBtn');
  btn.classList.toggle('liked', interactionOf(state.content).isLiked === true);
}

// ---------- 投币（T41 / R-17；口径源 = architecture/domain/coin/coin.md §四 · §五.1 · §七） ----------
/**
 * 投币按钮展示态：`interaction.coin` = 我投了几枚（`0/1/2`；匿名 ⇒ 无该子对象 ⇒ 按未投处理）。
 * ★ 已投满 `2` ⇒ 文案进"已用完"、视觉进禁用态，但**不用 `disabled` 属性** ——
 * 属性会让 click 根本不触发，而契约要求"点了给「对本稿件投币枚数已用完」提示"。
 * 故用类做视觉禁用 + click 里提示（后端仍会兜 409，前端不预判）。
 */
function updateCoinBtn() {
  const btn = state.container.querySelector('#coinBtn');
  if (!btn) return;
  const item = state.content;
  if (!item || item.type !== 1) {   // 图文帖 / 无内容：不渲染（并清掉上一次挂的 onclick）
    btn.style.display = 'none';
    btn.onclick = null;
    return;
  }
  btn.style.display = '';
  const n = interactionOf(item).coin;      // 0 / 1 / 2；匿名时 undefined
  const used = n === 2;
  btn.className = 'like-btn' + (used ? ' coin-used' : (n ? ' liked' : ''));
  btn.textContent = (n === 1 || n === 2) ? `🪙 已投 ${n}` : '🪙 投币';
  btn.onclick = used ? () => showToast('对本稿件投币枚数已用完') : openCoinModal;
}

/**
 * 投币弹层：显示我的余额 + 可选枚数。选项只按"**本次还能投几枚**"裁剪
 * （`0` ⇒ 1 / 2 都可选；`1` ⇒ 只能再投 1）；**余额够不够不预判** —— 后端 400「硬币不足」兜底。
 */
async function openCoinModal() {
  if (!requireLogin()) return;
  const n = interactionOf(state.content).coin || 0;
  state.container.querySelector('#coinOpt1').disabled = false;
  state.container.querySelector('#coinOpt2').disabled = n >= 1;
  state.container.querySelector('#coinOverlay').classList.remove('hidden');
  await showCoinBalance();
}

function closeCoinModal() {
  const overlay = state.container.querySelector('#coinOverlay');
  if (overlay) overlay.classList.add('hidden');
}

/** 弹层的余额：`GET api/v1/users/me` 取一次即缓存；投币成功由响应体回带的 `balance` 就地覆盖。 */
async function showCoinBalance() {
  const el = state.container.querySelector('#coinBalanceTxt');
  if (!el) return;
  if (state.coinBalance == null) {
    try {
      const me = await request('api/v1/users/me');
      state.coinBalance = me && me.coin != null ? me.coin : 0;
    } catch (e) {
      el.textContent = '--';
      return;
    }
  }
  el.textContent = formatBalance(state.coinBalance);
}

/**
 * 投币：★ **不做乐观更新** —— 只在成功后就地更新三个展示量（公共计数 `coinCount`、
 * 互动态 `interaction.coin`、余额），失败一律照后端 `msg` 提示、本地态不动。
 * 理由：409 既有"已投满 2"也有"只剩 1 枚额度"两种成因（coin 设计 §十 #1 已知代价），
 * 后者推不出 `amount` ⇒ 失败时改本地态会把展示钉成假值。
 */
async function doCoinAdd(count) {
  const opt = state.container.querySelector(count === 1 ? '#coinOpt1' : '#coinOpt2');
  if (opt) opt.disabled = true;
  try {
    const res = await coinAdd(state.contentId, count);
    const item = state.content;
    item.coinCount = (item.coinCount || 0) + count;
    interactionOf(item).coin = (interactionOf(item).coin || 0) + count;
    if (res && res.balance != null) state.coinBalance = res.balance;
    closeCoinModal();
    updateCoinBtn();
    renderStats();
    showToast('投币成功');
  } catch (e) {
    showToast(e.message || '投币失败');   // 「硬币不足」/「对本稿件投币枚数已用完」/「该内容不支持投币」…
  } finally {
    if (opt) opt.disabled = false;
  }
}

/**
 * 余额展示口径（coin 设计 §四）：一位小数、精确不舍入。
 * 后端 `BigDecimal(scale=1)` 序列化成 JSON 数字后 `1.0` 会被 JS 收成 `1` ⇒ 显示前补回一位小数
 * （页面内的局部口径，**不动** `utils.js` 的既有格式化函数 —— 那两个是数字缩写 / 时长）。
 */
function formatBalance(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(1) : '';
}

function renderRelated() {
  const box = state.container.querySelector('#relatedList');
  if (!state.related.length) {
    box.innerHTML = '<div class="empty"><div class="empty-msg">暂无推荐</div></div>';
    return;
  }
  box.innerHTML = '';
  state.related.forEach((item) => box.appendChild(createSideItem(item)));
}

function createSideItem(item) {
  const el = document.createElement('div');
  el.className = 'side-item';
  el.addEventListener('click', () => navigate('/video/' + item.id));

  const thumb = document.createElement('div');
  thumb.className = 'thumb';
  const fb = document.createElement('div');
  fb.className = 'cover-fallback';
  fb.textContent = initialChar(item.title || item.authorName);
  fb.style.background = avatarColor(item.title || item.authorName);
  thumb.appendChild(fb);
  if (item.coverUrl) {
    const img = document.createElement('img');
    img.src = item.coverUrl; img.alt = ''; img.loading = 'lazy';
    img.addEventListener('error', () => img.remove());
    thumb.appendChild(img);
  }

  const info = document.createElement('div');
  info.className = 'side-info';
  const t = document.createElement('div');
  t.className = 't';
  t.textContent = item.title || '';
  const m = document.createElement('div');
  m.className = 'm';
  m.textContent = `${item.authorName || ''} · ${formatNumber(item.likeCount || 0)}赞`;

  info.appendChild(t); info.appendChild(m);
  el.appendChild(thumb); el.appendChild(info);
  return el;
}

// ---------- 评论分页加载（T10-B：大 chunk + 本地小批，公共 chunkedList helper；N15 复用点） ----------
function ensureCommentList() {
  if (!state.commentList) {
    state.commentList = createChunkedList({
      // T11-B：只传 page（信封大小后端定，响应回显自适应）
      fetchChunk: async (page) => request(
        `api/v1/contents/${state.contentId}/comments?page=${page}`),
      chunkSize: CHUNK_SIZE,
      batchSize: BATCH_SIZE,
      // 评论 VO 同时含 userId（作者），必须显式按 commentId 去重（见 chunkedList.js keyOf 说明）
      keyOf: (c) => c.commentId,
    });
  }
  return state.commentList;
}

// 首次打开 / 发删评论后：重置列表并拉首批（本地小批展示）
async function loadInitialComments() {
  ensureCommentList().reset();
  state.comments = [];
  state.expandedRoots = {};
  await extendComments();
  renderComments();
}

// 拉下一小批：本地余量足够直接用，不足才发下一个 big chunk 请求
async function extendComments() {
  const list = ensureCommentList();
  const batch = await list.nextBatch();
  state.comments = state.comments.concat(batch);
  return batch;
}

async function loadMoreComments(btn) {
  btn.disabled = true;
  btn.textContent = '加载中...';
  try {
    await extendComments();
    renderComments();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = '加载更多评论';
    showToast(e.message || '加载失败');
  }
}

function renderCommentLoadMore() {
  const box = state.container.querySelector('#commentMore');
  box.innerHTML = '';
  if (!ensureCommentList().hasMore()) return;
  const btn = document.createElement('button');
  btn.className = 'load-more-btn';
  btn.textContent = '加载更多评论';
  btn.addEventListener('click', () => loadMoreComments(btn));
  box.appendChild(btn);
}

// ---------- 评论渲染（T10-B：主楼 children 只带前 K 条 + replyCount 总数；展开时拉 /comment/replies） ----------
function renderComments() {
  const c = state.container;
  const list = c.querySelector('#commentList');
  // 计数用详情接口的 commentCount（含楼中楼的总评论数）：分页后只加载了部分主楼，
  // 不能再按"已加载条数"显示，否则数字会随加载页数增长、与详情统计不一致
  const count = (state.content && state.content.commentCount != null)
    ? state.content.commentCount : countTotal(state.comments);
  c.querySelector('#commentHeader').textContent = '评论 (' + count + ')';
  list.innerHTML = '';
  if (!state.comments.length) {
    list.innerHTML = '<div class="empty-comments">暂无评论，快来抢沙发吧</div>';
    renderCommentLoadMore();
    return;
  }
  state.comments.forEach((cm) => list.appendChild(createCommentItem(cm, false)));
  renderCommentLoadMore();
}

function countTotal(list) {
  let n = list.length;
  list.forEach((c) => { if (c.children && c.children.length) n += countTotal(c.children); });
  return n;
}

function createCommentItem(comment, isReply) {
  const wrapper = document.createElement('div');
  wrapper.className = 'comment-item' + (isReply ? ' reply-indent' : '');

  const body = document.createElement('div');
  body.className = 'comment-body ' + (isReply ? 'level-1' : 'level-0');

  const nameWrap = document.createElement('span');
  nameWrap.style.cursor = 'pointer';
  nameWrap.addEventListener('click', () => { if (comment.userId != null) navigate('/user/' + comment.userId); });

  const avatar = document.createElement('span');
  avatar.className = 'comment-avatar';
  avatar.textContent = initialChar(comment.username);
  avatar.style.background = avatarColor(comment.username);

  const name = document.createElement('span');
  name.className = 'comment-username';
  name.textContent = comment.username || '匿名';
  nameWrap.appendChild(avatar);
  nameWrap.appendChild(name);

  const text = document.createElement('span');
  text.className = 'comment-text';
  if (isReply && comment.replyToUserId != null) {
    const at = document.createElement('span');
    at.className = 'comment-at';
    at.textContent = '回复 @' + (comment.replyToUsername || comment.replyToUserId) + '：';
    text.appendChild(at);
  }
  text.appendChild(document.createTextNode(comment.content || ''));

  const meta = document.createElement('div');
  meta.className = 'comment-meta';
  const likeBtn = document.createElement('button');
  likeBtn.className = 'comment-like' + (comment.isLiked ? ' liked' : '');
  likeBtn.innerHTML = '❤ ' + (comment.likeCount || 0);
  likeBtn.addEventListener('click', () => toggleCommentLike(comment, likeBtn));
  meta.appendChild(likeBtn);
  // 主楼与楼内回复均可回复（parentId 传该评论 id，后端自动上溯挂主楼）
  const replyBtn = document.createElement('button');
  replyBtn.className = 'comment-reply-btn';
  replyBtn.textContent = '回复';
  replyBtn.addEventListener('click', () => setReply(comment));
  meta.appendChild(replyBtn);
  if (comment.userId === getUserId()) {
    const delBtn = document.createElement('button');
    delBtn.className = 'comment-delete-btn';
    delBtn.textContent = '删除';
    delBtn.addEventListener('click', () => deleteComment(comment));
    meta.appendChild(delBtn);
  }

  body.appendChild(nameWrap);
  body.appendChild(text);
  body.appendChild(meta);
  wrapper.appendChild(body);

  // 主楼下方：楼内回复折叠列表（T10-B：children 只带前 K 条 + replyCount 总数；展开按需拉全）
  if (!isReply && (comment.children || []).length) {
    const total = Math.max(comment.replyCount || 0, (comment.children || []).length);
    const expanded = state.expandedRoots[comment.commentId]; // 已在展开：render 重建后按缓存数据重灌（L2）
    const toggle = document.createElement('div');
    toggle.className = 'comment-replies-toggle';
    toggle.textContent = '共 ' + total + ' 条回复 ' + (expanded ? '▾' : '▾');
    toggle.addEventListener('click', async () => {
      const box = wrapper.querySelector('.comment-replies');
      if (box.style.display !== 'none') { // 收起
        box.style.display = 'none';
        toggle.textContent = '共 ' + total + ' 条回复 ▸';
        return;
      }
      box.style.display = 'block';
      toggle.textContent = '共 ' + total + ' 条回复 ▾';
      // 首屏只带前 K 条：不足 total 且未拉过 → 点击展开拉取剩余（/comment/replies）
      if (state.expandedRoots[comment.commentId] || (comment.children || []).length >= total) return;
      toggle.textContent = '加载中...';
      try {
        await ensureRootExpanded(comment, wrapper);
        toggle.textContent = '共 ' + total + ' 条回复 ▾';
      } catch (e) {
        toggle.textContent = '共 ' + total + ' 条回复 ▾';
        showToast(e.message || '展开失败');
      }
    });

    const repliesBox = document.createElement('div');
    repliesBox.className = 'comment-replies';
    repliesBox.style.display = expanded ? 'block' : 'none';
    const seed = expanded ? (expanded.replies || []) : (comment.children || []);
    seed.forEach((child) => repliesBox.appendChild(createCommentItem(child, true)));

    wrapper.appendChild(toggle);
    wrapper.appendChild(repliesBox);
  }
  return wrapper;
}

// 展开主楼全部回复：★T14 游标（keyset）翻页拉 /comment/replies（页间与 children 前 K 重叠去重）并增量渲染；
// 结果存入 state.expandedRoots[rootId]={replies:[...]}，供 render 重建后重灌（review L2）
async function ensureRootExpanded(comment, wrapper) {
  const rootId = comment.commentId;
  const box = wrapper.querySelector('.comment-replies');
  const known = new Map();
  (comment.children || []).forEach((c) => known.set(c.commentId, c));
  let cursor = 0;
  for (;;) {
    // T14：游标翻页——cursor 缺省 0（楼头）；后端 nextCursor=null 即到底（不再有 page/totalPages）
    const data = await request(`api/v1/comments/${rootId}/replies?cursor=${cursor}`);
    (data.list || []).forEach((r) => { known.set(r.commentId, r); });
    const next = data.nextCursor;
    if (!next || next <= cursor) break;   // 到底；`next <= cursor` 为防御性兜底（防不前进的死循环）
    cursor = next;
  }
  if (!known.has(rootId) && known.size === 0) return; // 无任何行
  state.expandedRoots[rootId] = { replies: Array.from(known.values()) };
  box.innerHTML = '';
  state.expandedRoots[rootId].replies.forEach((c) => box.appendChild(createCommentItem(c, true)));
}

// ---------- 交互 ----------
function requireLogin() {
  if (!isLoggedIn()) { showToast('请先登录'); return false; }
  return true;
}

async function deleteComment(comment) {
  if (!requireLogin()) return;
  const removed = comment.parentId ? 1 : 1 + (comment.children || []).length;
  try {
    await request(`api/v1/comments/${comment.commentId}`, { method: 'DELETE' });
    showToast('删除成功');
    // 删除后回到第 1 页重新累积（避免页码与已加载内容错位）
    await loadInitialComments();
    if (state.content) {
      state.content.commentCount = Math.max(0, (state.content.commentCount || 0) - removed);
      renderStats();
    }
  } catch (e) {
    showToast(e.message || '删除失败');
  }
}

// ---------- 收藏（T7） ----------
/** 详情页统计行（点赞 / 评论 / 收藏 / **投币**数）—— 写同一行，收敛到此以免各写一份漂移。 */
function renderStats() {
  const item = state.content;
  const el = state.container && state.container.querySelector('#stats');
  if (!item || !el) return;
  el.textContent = `❤ ${formatNumber(item.likeCount || 0)} · 💬 ${formatNumber(item.commentCount || 0)}`
    + ` · ⭐ ${formatNumber(item.favoriteCount || 0)} · 🪙 ${formatNumber(item.coinCount || 0)}`
    + ` · ${formatTime(item.createTime)}`;
}

function updateFavBtn() {
  const btn = state.container && state.container.querySelector('#favBtn');
  if (!btn) return;
  const on = state.isFavorited === true;
  btn.className = 'like-btn' + (on ? ' liked' : '');
  btn.textContent = on ? '⭐ 已收藏' : '⭐ 收藏';
}

/**
 * 收藏按钮初始态。
 * T10（R-10）：初始态读**详情内联**的 `interaction.isFavorited`（匿名/失效 token ⇒ null ⇒ 未点亮）。
 * T43：收藏数也由**详情响应内联**（`favoriteCount`），首次渲染不再单发 `favorite/count`
 * —— 请求 3 → 1；只有收藏动作之后才用该端点刷新数字（用户去重 ⇒ 不能本地 ±1）。
 */
function initFavorite() {
  state.isFavorited = interactionOf(state.content).isFavorited === true;
  updateFavBtn();
}

async function refreshFavoriteCount() {
  try {
    const count = (await request(`favorite/count?contentId=${state.contentId}`)) || 0;
    if (state.content) state.content.favoriteCount = count;
  } catch (e) {
    // 刷新失败保持详情里的旧值，不打断页面
  }
  renderStats();
}

async function openFavPicker() {
  if (!requireLogin()) return;
  state.container.querySelector('#favOverlay').classList.remove('hidden');
  await loadPicker();
}

function closeFavPicker() {
  const overlay = state.container.querySelector('#favOverlay');
  if (overlay) overlay.classList.add('hidden');
}

/**
 * 懒建默认夹的**占位行**（id = null 表示"还不存在"）。
 *
 * <p>favorite-需求.md §二「每个用户自带一个默认收藏夹；收藏时没选夹，内容自动进这里」。
 * 默认夹只由 `add` **不传 folderId** 触发懒建（R-01，`FavoriteService.ensureDefaultFolder`），
 * 后端**没有**独立的"建默认夹"端点 ⇒ 前端必须自己给出这条入口（否则默认夹永远建不出来：
 * 弹窗只列已有夹、而默认夹只由"不选夹"产生，两者互锁）。
 * 勾它 = 调 `add` 不带 `folderId` ⇒ 懒建落成，随后 `loadPicker()` 会拿到真实行（带 isDefault）。
 */
const LAZY_DEFAULT_ROW = { id: null, name: '默认收藏夹', itemCount: null, isDefault: true };

/** 拉"我的夹" + 当前内容收藏状态，渲染勾选框：勾上 = 已收在该夹（与后端契约同源）。 */
async function loadPicker() {
  const picker = state.container.querySelector('#favPicker');
  if (!picker) return;
  picker.innerHTML = '<div class="fav-pick-empty">加载中...</div>';
  let folders;
  let st;
  try {
    [folders, st] = await Promise.all([
      request('favorite/folder/list'),
      request(`favorite/status?contentId=${state.contentId}`),
    ]);
  } catch (e) {
    picker.innerHTML = '<div class="fav-pick-empty">加载失败</div>';
    return;
  }
  state.pickFolders = folders || [];
  state.isFavorited = !!(st && st.isFavorited);
  const checkedIds = new Set(((st && st.folders) || []).map((f) => f.id));
  updateFavBtn();

  // 默认夹还不存在 ⇒ 补一行占位入口（已存在时它本就由 /folder/list 返回，且按契约默认夹置顶）
  const hasDefault = state.pickFolders.some((f) => f.isDefault === true);
  const rows = hasDefault ? state.pickFolders : [LAZY_DEFAULT_ROW, ...state.pickFolders];

  picker.innerHTML = '';
  rows.forEach((f) => {
    const row = document.createElement('label');
    row.className = 'fav-pick-row';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = checkedIds.has(f.id); // 占位行 id=null ⇒ 恒未勾
    cb.addEventListener('change', () => toggleFolderPick(f, cb));
    const name = document.createElement('span');
    name.className = 'fp-name';
    name.textContent = f.name;
    const cnt = document.createElement('span');
    cnt.className = f.id == null ? 'fp-hint' : 'fp-count';
    cnt.textContent = f.id == null ? '首次收藏时创建' : (f.itemCount != null ? f.itemCount : '');
    row.appendChild(cb);
    row.appendChild(name);
    row.appendChild(cnt);
    picker.appendChild(row);
  });
}

/** 勾选 = 收藏到该夹；取消 = 从该夹移出。409（已在夹里）按幂等成功处理。 */
async function toggleFolderPick(folder, cb) {
  const want = cb.checked;
  cb.disabled = true;
  try {
    if (folder.id == null) {
      // 勾占位行 ⇒ 走 R-01 懒建：add **不带 folderId**（后端就地建默认夹并落这条收藏）
      await request(`favorite/add?contentId=${state.contentId}`, { method: 'POST' });
      showToast('已收藏到默认收藏夹');
    } else if (want) {
      await request(`favorite/add?contentId=${state.contentId}&folderId=${folder.id}`, { method: 'POST' });
      showToast('已收藏');
    } else {
      await request(`favorite/remove?folderId=${folder.id}&contentIds=${state.contentId}`, { method: 'POST' });
      showToast('已取消收藏');
    }
    await loadPicker();
    await refreshFavoriteCount();
  } catch (e) {
    if (e.code === 409) {
      await loadPicker();
      await refreshFavoriteCount();
    } else {
      cb.checked = !want;
      showToast(e.message || '操作失败');
    }
  } finally {
    cb.disabled = false;
  }
}

async function createFolderFromPicker() {
  if (!requireLogin()) return;
  const input = state.container.querySelector('#favNewName');
  const name = input.value.trim();
  if (!name) { showToast('夹名不能为空'); return; }
  try {
    const prevIds = new Set((state.pickFolders || []).map((f) => f.id));
    await request(`favorite/folder/add?name=${encodeURIComponent(name)}`, { method: 'POST' });
    const folders = (await request('favorite/folder/list')) || [];
    const created = folders.find((f) => !prevIds.has(f.id)); // 刚建的那条（id 单调增）
    if (created) {
      await request(`favorite/add?contentId=${state.contentId}&folderId=${created.id}`, { method: 'POST' });
    }
    input.value = '';
    showToast('已创建并收藏');
    await loadPicker();
    await refreshFavoriteCount();
  } catch (e) {
    showToast(e.message || '创建失败');
  }
}

/**
 * 稍后再看（T19）：点击调 POST /watchlater/add（只收视频；重复 add ⇒ 后端移到最前，前端不感知）。
 * ★ 按钮外观**恒不变** —— 这里刻意不维护任何"已加入"状态（红线：除 /watchlater/list 外无状态面）。
 * 非视频（图文）⇒ 后端 400「只能添加视频」，照文案提示即可。
 */
async function addWatchLater() {
  if (!requireLogin()) return;
  try {
    await request(`watchlater/add?contentId=${state.contentId}`, { method: 'POST' });
    showToast('已加入稍后再看');
  } catch (e) {
    showToast(e.message || '操作失败');
  }
}

async function toggleContentLike() {
  if (!requireLogin()) return;
  const item = state.content;
  const it = interactionOf(item);
  const liked = it.isLiked === true;
  const action = liked ? 'remove' : 'add';
  it.isLiked = !liked;
  item.likeCount += liked ? -1 : 1;
  updateLikeBtn();
  state.container.querySelector('#likeCount').textContent = item.likeCount;
  renderStats();
  try {
    await request(`api/v1/contents/${state.contentId}/like`, { method: action === 'add' ? 'PUT' : 'DELETE' });
  } catch (e) {
    it.isLiked = liked;
    item.likeCount += liked ? 1 : -1;
    updateLikeBtn();
    state.container.querySelector('#likeCount').textContent = item.likeCount;
    showToast(e.message || '操作失败');
  }
}

async function toggleCommentLike(comment, btn) {
  if (!requireLogin()) return;
  const liked = comment.isLiked;
  const action = liked ? 'remove' : 'add';
  comment.isLiked = !liked;
  comment.likeCount += liked ? -1 : 1;
  btn.className = 'comment-like' + (comment.isLiked ? ' liked' : '');
  btn.innerHTML = '❤ ' + comment.likeCount;
  try {
    await request(`api/v1/comments/${comment.commentId}/like`, { method: action === 'add' ? 'PUT' : 'DELETE' });
  } catch (e) {
    comment.isLiked = liked;
    comment.likeCount += liked ? 1 : -1;
    btn.className = 'comment-like' + (comment.isLiked ? ' liked' : '');
    btn.innerHTML = '❤ ' + comment.likeCount;
    showToast(e.message || '操作失败');
  }
}

async function toggleFollow() {
  if (!requireLogin()) return;
  const item = state.content;
  const it = interactionOf(item);
  const followed = it.isFollowed === true;
  const action = followed ? 'remove' : 'add';
  it.isFollowed = !followed;
  updateFollowBtn();
  try {
    await request(`follow/${action}?followedUserId=${item.authorId}`, { method: 'POST' });
    showToast(followed ? '已取消关注' : '关注成功');
  } catch (e) {
    it.isFollowed = followed;
    updateFollowBtn();
    showToast(e.message || '操作失败');
  }
}

function setReply(comment) {
  if (!requireLogin()) return;
  state.parentId = comment.commentId;
  const input = state.container.querySelector('#commentInput');
  input.placeholder = '回复 @' + (comment.username || '匿名') + '：';
  input.focus();
}

async function sendComment() {
  if (!requireLogin()) return;
  const input = state.container.querySelector('#commentInput');
  const message = input.value.trim();
  if (!message) { showToast('评论内容不能为空'); return; }

  const body = { contentId: parseInt(state.contentId, 10), message };
  if (state.parentId) body.parentId = state.parentId;

  try {
    await request('api/v1/comments', { jsonBody: body });
    input.value = '';
    input.placeholder = '发一条友善的评论';
    state.parentId = null;
    showToast('评论成功');
    // 新评论进入主楼升序末位，回到第 1 页重新累积（与删除后同一处置）
    await loadInitialComments();
  } catch (e) {
    showToast(e.message || '评论失败');
  }
}
