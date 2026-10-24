# 接口契约-护栏与Spec

> 定位（先读这一句）：**《接口契约》的护栏机制篇** —— 只管"契约怎么被机器**守住**、怎么被机器**导出**"：
> 端点登记簿（扫真实 handler 的 TSV 快照）与 OpenAPI spec（`openapi.yaml`）。
> **规则本身**（状态码分层 / 码表粒度 / 路径与方法判据 / 载荷形状 / 分页 / 时间 / ID / 一物一名）在
> [接口契约](接口契约.md) §二 —— 本篇不重复任何一条规则，只写**产物、校验、更新纪律**。
> 由 `T54`（登记簿）/ `T60`（spec）建立，`T62` 收口时从《接口契约》§2.9 / §2.10 **原样移出**
> （编号承接见《接口契约》§2.9 末）。
>
> ⚠️ **共同前提**：两项产物都由**测试**生成与校验，**不是手写文件**。改契约的正确姿势 =
> 改代码 → 重生成 → **review diff** → 与代码同批提交。跑生成命令"把红变绿"= **迎合**，
> 仓库根 `AGENTS.md`「测试纪律」明令禁止。

---

## 一、端点登记簿（护栏机制，`T54`）

- 文件：`src/test/resources/api/endpoint-inventory.tsv`（**67 条**，`T54` 快照）。
- 校验：`ApiContractInventoryTests` **扫真实 handler** 与登记簿逐条比对；三条用例分别是
  ① 登记簿一致 ② 扫描面自检（无非限定方法的映射 / 无重复路由 / 鉴权档位三值）
  ③ 全部端点返回 `ApiResponse<T>`。
- 更新：`./mvnw.cmd -o test -Dtest=ApiContractInventoryTests -Dapi.contract.write=true`
  → **生成后必须 review diff**，与代码同批提交。
- ⚠️ 该文件由 `.gitignore` 显式放行（`/src/test/resources/*` 下的 `api/` 例外）——**必须入库**，
  否则新克隆上"漏登记"无从比对（判据 `git check-ignore -v`）。

---

## 二、机器可读 spec（`T60`）

> **定位一句话**：**代码是源，spec 是产物。** 《接口契约》写**规则与为什么**（状态码分层、码表粒度、
> 路径判据、例外清单），spec 只写**形状**（路径 / 方法 / 参数 / 响应）。
> 两者不是"同一事实写两处"：规则推不出来（"为什么不用 PATCH"），形状不该手抄（抄了必漂移）。

- **产物**：`src/test/resources/api/openapi.yaml`（与 `endpoint-inventory.tsv` 同目录，67 条 / 90 KB）。
  入库判据不是"能不能重建"（那 `target/` 都该入库），而是 **diff 有没有信息** ——
  spec 的 diff 就是"对外契约变了什么"的记录，重制期与 review 都以它为准。

- **生产不暴露 `/v3/api-docs`**（`springdoc.api-docs.enabled=false`）。理由：本仓的默认拒绝是
  「命中的处理器必须标 `@Public`」（`RequestMappingLookup`），而文档端点是 springdoc 自己的
  `@RestController` —— **加不上注解**。只剩两条路：开路径白名单（违反《安全与鉴权》§2.1 的
  "授权唯一事实源"）或干脆不暴露。取后者，换来三条连带都归零：匿名面为零、登记簿仍 **67**、
  安全契约与权重表不动。spec 只在测试里由 `OpenApiWebMvcResource` 直接生成（**不走 HTTP**），
  因此也不需要任何豁免。契约由 `OpenApiExposureTests` 钉住。

- **生成命令**（与登记簿同一套纪律：显式写入 + review diff + 不许把红变绿）：
  ```
  ./mvnw.cmd test -Dtest=OpenApiSpecExportTests -Dapi.openapi.write=true
  ```
  七条护栏（`OpenApiSpecExportTests` 7 例 + `OpenApiExposureTests` 1 例）：
  ① 重新生成与入库内容**逐字一致**；② path+method 集合 == `T54` 登记簿（两个**扫描器**独立）；
  ③ 不出现 `@CurrentUserId` 的假参数；④ 响应类别与登记簿档位对应 + 204 不留 200；
  ⑤ 错误信封三 schema + 9 个响应组件同指 `ApiError`；⑥ multipart 四条 + 必填字段；
  ⑦ 503 只在 `report`、`servers` 不钉 localhost；另加 `OpenApiExposureTests` 钉"生产不注册文档端点"。
  ⚠️ **判别力边界（别当成独立 oracle）**：登记簿的 AUTH 列**也是** `RequestMappingLookup` 算的
  ⇒ 它能抓"customizer 漏挂 401"，抓不到"判定本身跑偏"；`ErrorCode` 字典断言只证明"字典在且形状对"，
  不证明"码就是运行期会出现的码"（那是 `ErrorCodeContractTests#无死码` 的职责）。

- **两个成立条件**（都不是美化项，缺一就退回不入库）：
  ① **键序稳定**：`springdoc.writer-with-order-by-keys: true` 后，同一份代码两次生成**逐字相同**、
  只改一处时 diff 恰好 1 行（不排序则键序跟随扫描顺序，整文件噪声会让 review 失效）；
  ② **扫描面同口径**：`springdoc.packages-to-scan` = `ApiContractInventoryTests.BASE_PACKAGE`
  （不限定会扫进 Boot 的 `/error` 与 springdoc 自己的端点 ⇒ "集合相等"这条验收作废）。

- **响应类别怎么来的（`T60-2` 定稿、`T60-3` 按 review 修正，零注解）**：

  | 类别 | 判据 |
  |---|---|
  | 401 | `RequestMappingLookup#resolve(HandlerMethod)` —— 与运行期**同一份判定**（在文档侧另写一套注解规则 = 两套口径，且跑偏不报错） |
  | 403 | **只要需身份就声明**。403 有第二个来源：各域 Service 的所有权校验（`ForbiddenException`）—— 它**推不出来**（`@RequiresLogin` 只说"要登录"，不说"只许动自己的"）⇒ 宽口径防漏，代价是少数端点带一个永不触发的分支 |
  | 404 | **有入参就声明**（body 或任一参数）。⚠️ 不能只看路径里有没有 `{var}`：本仓有一批"目标资源 id 在 body / query"的端点（`POST /comments` 的 contentId、`POST /favorites` 的 contentId/folderId、`GET\|DELETE /favorites` 的 folderId） |
  | 400 | 有请求体，或有任一参数（非法 id 也是 400） |
  | 409 | 非 GET（写端点的"状态 / 唯一性冲突"） |
  | 415 | 有请求体 |
  | 429 / 500 | 全站（限流在 `DispatcherServlet` 之前对每个请求过闸） |
  | 503 | **只有 `PUT /contents/{id}/progress`**（`HistoryService#report` 抛 `ServiceBusyException`，带 `Retry-After`）。⚠️ **不挂全站**：`/history/flush` 那条路径是**吞异常返 200**（数据留缓冲等兜底），挂上去等于谎报"可重试" |

  ⚠️ 这些是**类别级可能域，不是穷举**：宁可多声明一个类别，也不漏 —— 漏了前端没兜底可写；
  精确分支一律看 `code`。**403 / 404 用宽口径**（推不出来的原因就宽），**503 用窄口径**（能推出来就必须准）。

- **必填字段要覆盖手写校验**：`title` / `categoryId` 在代码里是 `@RequestParam(required = false)` + 手写拒绝
  （缺一即 400），`@RequestPart(required = false)` + 判空的 `file` 同理 ⇒ 若照抄 `required` 标志，
  spec 会说"可以不传"。`OpenApiConfig` 用**显式登记表**覆盖（`REQUIRED_QUERY_PARAMS` / `REQUIRED_BODY_PARTS`），
  并有断言钉住"登记的端点必须存在于 spec"（改名即红，不静默脱保）。

- **其它形状（`T60-2`）**：`ResponseEntity<Void>` ⇒ **204，且不留 200**；`@RequestPart` / `MultipartFile`
  端点 ⇒ requestBody **必须是 `multipart/form-data`**（声明成 JSON，前端就会按 JSON 传文件）；
  错误体由 `ApiError` / `ApiFieldError` / **`ErrorCode`（41 项枚举）** 三个组件承担，逐端点只写一行
  `$ref`（否则 67 端点 × 6 类别会把 spec 撑爆，且改一处文案要改 400 处）；
  `servers` 固定 `/`（离线产物不能钉 `localhost`）；`operationId` 由方法名自动生成（同名带 `_2` 后缀），
  **不作为契约**（会随同名方法增减而变，按 path + method 调用）。

- **推翻条件**：重制期需要"在线试调"（Swagger UI / 活的 `/v3/api-docs`）⇒ 先解决
  "匿名面如何用 `@Public` 表达"（目前无解），否则只能接受"多一个匿名静态面"并登记为已知代价。

---

## 三、改一个端点时的三步（本篇与《接口契约》如何配合）

1. **对规则** → [接口契约](接口契约.md) §二（方法定性 §2.4 / 路径 §2.3 / 载荷 §2.5 / 状态码 §2.1）。
2. **改代码** → Controller + `cost.base` 权重表 + `SecurityContractTests`（三处同一个 commit）。
3. **重生成两件产物** → 登记簿（§一 命令）+ spec（§二 命令），**review diff** 后同批提交。
   漏了第 3 步不会"报错"，只会让下一次改契约的人看不到"这次到底改了什么"。
