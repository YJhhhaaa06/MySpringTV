// ============================================================================
// 请求封装：Bearer 鉴权头 + {code,msg,data} 解包 + 401 跳转
//
// 与 TV 前端的两点差异（本项目有意决策，见《决策留痕表》D 类）：
//   - 鉴权头由 TV 的自定义 `token` 改为标准 `Authorization: Bearer <token>`
//     （决策③ 保留 JWT；新后端 JwtAuthFilter 只认 Authorization）。
//   - 新后端把 **HTTP 状态码与业务 code 对齐**（决策⑧「HTTP 状态码正确化」），
//     不再是 TV 的"协议层恒 200"。但错误响应**仍带 {code,msg} 信封**，
//     故必须**先读 body 再判**——若沿用旧写法按 `res.ok` 短路，会丢掉 msg/code，
//     且 401 无法触发清登录态跳转，各视图的 `e.code === 401/403` 分支也会失效。
//   - 唯一无信封的错误：未识别路径由 Spring 直接产出 404（D-6），此时回落到
//     `请求失败（状态码）`。
//   - request() 直接返回 data 载荷，业务错误抛 Error（携带 .code / .status）。
// ============================================================================

import { getToken, clearAuth } from './auth.js';
import { showToast } from './utils.js';

// ----------------------------------------------------------------------------
// T7 收藏域端点清单（前端在此登记；**不新增封装层** —— 各视图沿用
// `request('路径', { method, jsonBody })` 的既有调用风格，与 like / follow / comment 同款）。
//   写：POST favorite/folder/{add,update,remove} · favorite/{add,remove,move}（form 参数走 query）
//   读：GET  favorite/folder/list · favorite/folder/public · favorite/status · favorite/count · favorite/list
//   ★ 鉴权差异：folder/public、count 匿名可访问；其余需登录（后端逐端点声明，别照抄 like 的类级注解）。
//   ★ T10（R-10）：详情互动态（赞 / 藏 / 关注作者）**内联在 /search/IdSearch 响应的
//     `interaction` 子对象**里（匿名 ⇒ null），**没有新端点**；`favorite/status` 仍被收藏弹窗
//     用于取"收在哪些夹"（folders），详情按钮初始态不再单发它。
//   ★ T13（R-13）：**列表**四个面（`/start`、`/search/keywordSearch`、`/feed`、`/profile.contentPage`）
//     的条目同样是 `interaction` 子对象（与详情**同形**），且**三维三态** —— `null` = 本面不查该维，
//     前端静默忽略即可。各面带哪几维是后端常量：首页只给关注、搜索页**一维都不给**（对象仍在、
//     三维全 null）、动态流与个人主页只给赞。⚠️ 匿名 ⇒ `interaction` 为 `null`。
//   ★ T14（F-20 ②）：`/comment/replies` **改游标分页** —— 入参 `cursor`（上页末条 commentId，缺省 0）、
//     出参 `{list,total,pageSize,nextCursor}`；`nextCursor=null` 即到底 ⇒ 前端翻到它为止（旧 `page`
//     参数不再被识别）。**其余分页端点（follow/search/profile/feed、`/comment/show`）仍是 `page` 信封**，
//     不要照搬到 `chunkedList`（后者是 offset 语义）。
// ----------------------------------------------------------------------------

export async function request(path, options = {}) {
  const opts = { ...options };
  opts.headers = { ...(options.headers || {}) };

  if (options.jsonBody !== undefined) {
    opts.method = opts.method || 'POST';
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(options.jsonBody);
  }

  const token = getToken();
  if (token) opts.headers['Authorization'] = 'Bearer ' + token;

  let res;
  try {
    res = await fetch(path, opts);
  } catch (e) {
    const err = new Error('网络错误，请重试');
    err.network = true;
    throw err;
  }

  // 先尝试读信封：错误响应（4xx/5xx）也带 {code,msg}，不能按 res.ok 短路
  let body = null;
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    try { body = await res.json(); } catch (e) { body = null; }
  }

  if (body && typeof body.code === 'number') {
    if (body.code === 200) return body.data;

    if (body.code === 401) {
      clearAuth();
      showToast('登录已过期，请重新登录');
      location.hash = '#/login';
    }
    const err = new Error(body.msg || '请求失败');
    err.code = body.code;
    err.status = res.status;
    throw err;
  }

  // 非信封（未识别路径的 Spring 默认 404 / 容器 5xx 等），非常规业务通道
  if (!res.ok) {
    const err = new Error(`请求失败（${res.status}）`);
    err.status = res.status;
    throw err;
  }

  return await res.text();
}

export const api = { request };
