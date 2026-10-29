import { reactive, ref, type Ref } from 'vue'
import type { AuthorCardVM, FavoriteFolderVM, InteractionVM, VideoDetailVM } from '../types/ui'
import { applyCoinBalance, applySignInResult } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { showToast } from '../common/toast'
import { ApiError } from '../api/http'
import {
  addCoins,
  addFavorite,
  checkInToday,
  createFavoriteFolder,
  fetchContentState,
  fetchFavoriteFolders,
  fetchFavoriteStatus,
  followUser,
  likeContent,
  removeFavorite,
  unfollowUser,
  unlikeContent,
} from '../api/interactions'

/**
 * 播放页互动态的**唯一状态机**（点赞 / 投币 / 收藏 / 关注）。
 *
 * <h2>为什么单独一个 composable 而不是写进 VideoPage</h2>
 * 四种动作共享三件容易写歪的事：① 登录闸门（动作级，不是页面级）；
 * ② 乐观更新与回滚；③ 写成功后用服务端权威值对齐。散在组件里就会变成
 * "点赞自己回滚、投币不回滚、关注忘了对齐"三种口径。
 *
 * <h2>★ 三态口径（最容易写成 bug 的地方）</h2>
 * 匿名时详情的 `interaction` 整个是 `null`（不是 `{}`）。所以：
 * - 登录前渲染"未点赞/未收藏"是**按设计**（本面不查）；
 * - 一旦用户要写，必须先 `requireLogin()`，**登录成功后重新读一次详情**再动手 ——
 *   否则会把"后端其实已点赞"覆盖成"未点赞"，用户点一下反而取消了自己的赞。
 *
 * <h2>计数为什么加减而不是直接重读</h2>
 * 乐观更新先行（点下去立刻有反馈），写成功后再 `fetchContentState` 对齐权威值；
 * 失败则回滚。回滚后仍然对齐一次 —— 冲突（别人同时点赞）时以服务端为准。
 */
export interface VideoInteractions {
  busy: Readonly<{ like: boolean; coin: boolean; favorite: boolean; follow: boolean }>
  coinOpen: Ref<boolean>
  coinError: Ref<string>
  coinInsufficient: Ref<boolean>
  coinSubmitting: Ref<boolean>
  favoriteOpen: Ref<boolean>
  favoriteError: Ref<string>
  folders: Ref<FavoriteFolderVM[]>
  foldersLoading: Ref<boolean>
  creatingFolder: Ref<boolean>
  /** 正在写的那一夹（弹层里只禁用它自己，不冻结整张列表） */
  pendingFolderId: Ref<number | null>
  /** 用服务端权威值对齐（登录后 / 外部动作后由页面调） */
  refreshState: () => Promise<void>
  toggleLike: () => Promise<void>
  openCoin: () => Promise<void>
  closeCoin: () => void
  giveCoins: (count: 1 | 2) => Promise<boolean>
  signInForCoins: () => Promise<void>
  openFavorite: () => Promise<void>
  closeFavorite: () => void
  toggleFolder: (folderId: number) => Promise<void>
  createFolder: (name: string) => Promise<boolean>
  toggleFollow: () => Promise<void>
}

/** 匿名 / 缺省时的零态（**只在写操作路径上用**，不要拿去渲染"未点赞"） */
function zeroInteraction(): InteractionVM {
  return { liked: false, coin: 0, favorited: false, followed: null }
}

function bump(
  stats: VideoDetailVM['stats'],
  key: 'like' | 'coin' | 'favorite' | 'comment',
  delta: number,
): void {
  const value = stats[key]
  // 后端没给这一维（null）时不做算术：null 是"不知道"，不是 0
  if (typeof value === 'number') stats[key] = Math.max(0, value + delta)
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    // ★ 429 单独给口径：本仓登录桶 20 枚 / 2 枚每秒 + 缓存 miss 三倍系数，
    //   连续几个写操作真能撞上；只说"请求过于频繁"用户不知道等多久。
    if (error.status === 429) {
      return `操作太频繁了，${error.retryAfterSeconds ?? 30} 秒后再试`
    }
    return error.message
  }
  return '操作失败，请稍后重试'
}

/** 投币的业务码 → 人话（这些分支必须有区别，否则用户不知道"为什么投不了"） */
function coinMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '投币失败，请稍后重试'
  switch (error.code) {
    case 'COIN_INSUFFICIENT':
      return '硬币不足：每天签到可以领硬币'
    case 'COIN_SELF_FORBIDDEN':
      return '不能给自己的稿件投币'
    case 'COIN_ALREADY_FULL':
      return '这条内容已经投满两枚硬币了'
    case 'COIN_NOT_SUPPORTED':
      return '图文稿件不支持投币'
    default:
      return error.status === 429
        ? `操作太频繁了，${error.retryAfterSeconds ?? 30} 秒后再试`
        : error.message
  }
}

export function useVideoInteractions(
  detail: Ref<VideoDetailVM | null>,
  author: Ref<AuthorCardVM | null> = ref(null),
): VideoInteractions {
  const busy = reactive({ like: false, coin: false, favorite: false, follow: false })
  const coinOpen = ref(false)
  const coinError = ref('')
  const coinInsufficient = ref(false)
  const coinSubmitting = ref(false)
  const favoriteOpen = ref(false)
  const favoriteError = ref('')
  const folders = ref<FavoriteFolderVM[]>([])
  const foldersLoading = ref(false)
  const creatingFolder = ref(false)
  const pendingFolderId = ref<number | null>(null)

  function interactionOf(entity: VideoDetailVM): InteractionVM {
    return entity.interaction ?? zeroInteraction()
  }

  /** 用服务端权威的计数 + 互动态覆盖本地（静默：失败不打断用户看到的数字） */
  async function refreshState(): Promise<void> {
    const entity = detail.value
    if (!entity) return
    try {
      const fresh = await fetchContentState(entity.id)
      entity.stats = fresh.stats
      entity.interaction = fresh.interaction
    } catch {
      // 刷新失败：保留本地值。下一个动作会再对齐一次。
    }
  }

  /**
   * 动作前置：已登录 + 互动态是**登录后读的**。
   *
   * @returns false = 用户取消登录 / 页面没数据 ⇒ 调用方直接返回
   */
  async function ensureReady(): Promise<boolean> {
    if (!(await requireLogin())) return false
    const entity = detail.value
    if (!entity) return false
    if (!entity.interaction) {
      // 登录前拿到的是匿名读数（整个 interaction 为 null）⇒ 先对齐再动手
      await refreshState()
    }
    return true
  }

  async function toggleLike(): Promise<void> {
    const entity = detail.value
    if (!entity || busy.like) return
    if (!(await ensureReady())) return
    busy.like = true
    const current = interactionOf(entity)
    const target = !current.liked
    entity.interaction = { ...current, liked: target }
    bump(entity.stats, 'like', target ? 1 : -1)
    try {
      if (target) await likeContent(entity.id)
      else await unlikeContent(entity.id)
      showToast(target ? '已点赞' : '已取消点赞')
    } catch (error) {
      // 回滚到"这一次动作之前"的状态（不是当前的 target）
      entity.interaction = { ...interactionOf(entity), liked: !target }
      bump(entity.stats, 'like', target ? -1 : 1)
      showToast(errorText(error), 'error')
    } finally {
      busy.like = false
      void refreshState()
    }
  }

  async function openCoin(): Promise<void> {
    if (!(await ensureReady())) return
    coinError.value = ''
    coinInsufficient.value = false
    coinOpen.value = true
  }

  function closeCoin(): void {
    coinOpen.value = false
    coinError.value = ''
    coinInsufficient.value = false
  }

  async function giveCoins(count: 1 | 2): Promise<boolean> {
    const entity = detail.value
    if (!entity || coinSubmitting.value) return false
    coinSubmitting.value = true
    coinError.value = ''
    coinInsufficient.value = false
    try {
      const result = await addCoins(entity.id, count)
      // 余额是服务端权威值：本地不做算术（签到 / 另一个标签页都会改它）
      applyCoinBalance(result?.balance)
      const current = interactionOf(entity)
      entity.interaction = { ...current, coin: Math.min(2, current.coin + count) }
      bump(entity.stats, 'coin', count)
      closeCoin()
      showToast('投币成功')
      void refreshState()
      return true
    } catch (error) {
      coinError.value = coinMessage(error)
      coinInsufficient.value = error instanceof ApiError && error.code === 'COIN_INSUFFICIENT'
      return false
    } finally {
      coinSubmitting.value = false
    }
  }

  /**
   * 硬币不足时的出口：真签到接口（**不是**前端假装加一枚）。
   *
   * <p>★ 重复签到**不是错误**：服务端回 200 + `credited=false`（幂等，见
   * `architecture/domain/coin/coin.md` §二）。所以这里没有 409 分支 —— 早先那条
   * `catch (409)` 从来没被走到过（后端从不抛 409），是注释把契约写错了留下的死代码。
   *
   * <p>用 `applySignInResult` 而不是 `applyCoinBalance`：签到同时要改
   * "今天已签"这个状态（空间页硬币卡读它），只对齐余额会让两处显示打架。
   */
  async function signInForCoins(): Promise<void> {
    // 先把上一条错误清掉：否则"硬币不足"会在签到请求在途期间继续挂着，
    // 看上去像"点了没反应"（E2E 当场撞到过这个读法）。
    coinError.value = ''
    coinInsufficient.value = false
    try {
      const result = await checkInToday()
      // credited 只说明"这次真的入了账"；数额由服务端余额体现（不自己算差值）
      applySignInResult(result?.balance)
      showToast(result?.credited ? '签到成功，硬币已到账' : '今天已经签到过了')
    } catch (error) {
      coinError.value = errorText(error)
    }
  }

  async function loadFolders(): Promise<void> {
    const entity = detail.value
    if (!entity) return
    foldersLoading.value = true
    favoriteError.value = ''
    try {
      const [all, status] = await Promise.all([
        fetchFavoriteFolders(),
        fetchFavoriteStatus(entity.id),
      ])
      const contained = new Set((status?.folders ?? []).map((folder) => folder.id ?? 0))
      folders.value = (all ?? []).map((folder) => ({
        id: folder.id ?? 0,
        name: folder.name?.trim() || '未命名收藏夹',
        itemCount: folder.itemCount ?? 0,
        isDefault: folder.isDefault === true,
        isPrivate: folder.isPrivate === true,
        contains: contained.has(folder.id ?? 0),
      }))
      // 一个夹都没有 = 新用户；真正的默认夹由后端在首次收藏时创建（不是前端编的）
      favoriteError.value = ''
    } catch (error) {
      folders.value = []
      favoriteError.value = errorText(error)
    } finally {
      foldersLoading.value = false
    }
  }

  async function openFavorite(): Promise<void> {
    if (!(await ensureReady())) return
    favoriteOpen.value = true
    await loadFolders()
  }

  function closeFavorite(): void {
    favoriteOpen.value = false
    favoriteError.value = ''
  }

  /** 勾选 / 取消勾选一个收藏夹（B 站是"改一个存一个"，不是整页提交） */
  async function toggleFolder(folderId: number): Promise<void> {
    const entity = detail.value
    const folder = folders.value.find((item) => item.id === folderId)
    if (!entity || !folder || busy.favorite) return
    const target = !folder.contains
    busy.favorite = true
    pendingFolderId.value = folderId
    folder.contains = target
    const before = interactionOf(entity).favorited
    // ★ 收藏状态 = "在任一夹里"（后端 interaction.isFavorited 的同一口径）
    entity.interaction = { ...interactionOf(entity), favorited: folderCount() > 0 }
    bump(entity.stats, 'favorite', target ? 1 : -1)
    try {
      if (target) await addFavorite(entity.id, folderId)
      else await removeFavorite(folderId, entity.id)
      showToast(target ? `已收藏到「${folder.name}」` : `已从「${folder.name}」移除`)
    } catch (error) {
      folder.contains = !target
      entity.interaction = { ...interactionOf(entity), favorited: before }
      bump(entity.stats, 'favorite', target ? -1 : 1)
      favoriteError.value = errorText(error)
    } finally {
      busy.favorite = false
      pendingFolderId.value = null
      void refreshState()
    }
  }

  /** 已勾选的夹数（决定 `isFavorited` —— 收藏状态是"在任一夹里"的并集） */
  function folderCount(): number {
    return folders.value.filter((folder) => folder.contains).length
  }

  async function createFolder(name: string): Promise<boolean> {
    const trimmed = name.trim()
    if (!trimmed) {
      favoriteError.value = '收藏夹名不能为空'
      return false
    }
    creatingFolder.value = true
    favoriteError.value = ''
    try {
      await createFavoriteFolder(trimmed)
      // 新夹的 id 只能从列表里拿回来（创建端点不回 id，见 GAP 台账）
      await loadFolders()
      showToast('已新建收藏夹，点一下勾选')
      return true
    } catch (error) {
      favoriteError.value = errorText(error)
      return false
    } finally {
      creatingFolder.value = false
    }
  }

  async function toggleFollow(): Promise<void> {
    const entity = detail.value
    const card = author.value
    if (!entity || !card || busy.follow) return
    if (!(await requireLogin())) return
    busy.follow = true
    const target = card.followed !== true
    card.followed = target
    if (typeof card.followerCount === 'number') {
      card.followerCount = Math.max(0, card.followerCount + (target ? 1 : -1))
    }
    if (entity.interaction) entity.interaction = { ...entity.interaction, followed: target }
    try {
      if (target) await followUser(card.id)
      else await unfollowUser(card.id)
      showToast(target ? '已关注' : '已取消关注')
    } catch (error) {
      card.followed = !target
      if (typeof card.followerCount === 'number') {
        card.followerCount = Math.max(0, card.followerCount + (target ? -1 : 1))
      }
      if (entity.interaction) entity.interaction = { ...entity.interaction, followed: !target }
      showToast(errorText(error), 'error')
    } finally {
      busy.follow = false
    }
  }

  return {
    busy,
    coinOpen,
    coinError,
    coinInsufficient,
    coinSubmitting,
    favoriteOpen,
    favoriteError,
    folders,
    foldersLoading,
    creatingFolder,
    pendingFolderId,
    refreshState,
    toggleLike,
    openCoin,
    closeCoin,
    giveCoins,
    signInForCoins,
    openFavorite,
    closeFavorite,
    toggleFolder,
    createFolder,
    toggleFollow,
  }
}
