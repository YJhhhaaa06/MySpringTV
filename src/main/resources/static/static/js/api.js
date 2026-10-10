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
