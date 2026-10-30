package io.github.yjhhhaaa06.videoweb.common.config;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.github.yjhhhaaa06.videoweb.common.security.RequestMappingLookup;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI spec 的**唯一装配点**（`T60`）。口径见 {@code architecture/tech/api/接口契约-护栏与Spec.md} §二。
 *
 * <h2>本类负责什么</h2>
 * <ol>
 *   <li>spec 的身份：标题 / 契约版本 / 读法说明（{@link #CONTRACT_VERSION}）；</li>
 *   <li>剥掉"假参数"：{@code @CurrentUserId} 不是请求参数（见构造函数）；</li>
 *   <li>公共组件：失败体 {@code ApiError} / 字段级错误 {@code ApiFieldError} / 码字典 {@code ErrorCode}；</li>
 *   <li>逐端点的错误类别与 204 / multipart：由 {@link OperationCustomizer} 按 {@link HandlerMethod} 推导。</li>
 * </ol>
 *
 * <h2>为什么这里不改任何控制器（零注解）</h2>
 * spec 是**产物**，代码是**源**。给 67 个 handler 逐个加 {@code @Operation} / {@code @ApiResponse}
 * 会把"契约文档"变成第二处需要人工维护的事实源，且 diff 会被注解淹没。
 * 唯一允许的注解面是"**单位/精度从类型看不出来**"的字段（例：{@code coin} 是十分位 {@code 15 = 1.5 枚}）——
 * 那种地方不加说明，spec 就是在说谎。
 *
 * <h2>错误类别怎么推（不逐端点手写）</h2>
 * <ul>
 *   <li><b>401 / 403</b>：走 {@link RequestMappingLookup#resolve(HandlerMethod)} —— 与运行期**同一份判定**
 *       （两套口径迟早漂移，而文档漂移不会报错）。</li>
 *   <li><b>404</b>：路径里有 {@code {var}}（资源寻址才可能"不存在"）。</li>
 *   <li><b>400</b>：有请求体或有任一参数（含 path 变量，非法 id 也是 400）。</li>
 *   <li><b>409</b>：非 GET（写端点的"状态/唯一性冲突"）。</li>
 *   <li><b>415</b>：有请求体。</li>
 *   <li><b>429 / 500</b>：全站（限流在 DispatcherServlet 之前，对每个请求过闸）。</li>
 * </ul>
 * ⚠️ <b>这些是"类别级可能域"，不是穷举、也不承诺"一定会发生"</b>。依据是 §2.1 的分层：
 * HTTP status 只表类别，前端据此做通用分支；**精确分支靠 body 的 {@code code}**（见码字典 schema）。
 * 宁可多声明一个类别，也不要漏声明 —— 漏了前端就没法写兜底分支。
 *
 * <h2>契约版本（{@link #CONTRACT_VERSION}）不是 Maven 版本</h2>
 * 它描述**对外形状的兼容性**：破坏性变更（删字段 / 改类型 / 改路径）才 +1，加字段不加。
 * 两者是不同的事实：用 Maven 版本会让"spec 变了一版"与"包发了一版"混为一谈。
 */
@Configuration
public class OpenApiConfig {

    /** 对外契约版本。规则见类注释：**破坏性变更才 +1**。 */
    public static final String CONTRACT_VERSION = "1.0.0";

    private static final String JSON = "application/json";
    private static final String MULTIPART = "multipart/form-data";
    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ApiError";

    /**
     * **唯一**会回 503 的端点（`HistoryService#report` 抛 `ServiceBusyException`）。
     *
     * <p>为什么要显式登记：503 来自"依赖暂不可用"，无法从注解或形状推导；
     * 而全站挂 503 会把"这条路径存在且可重试"变成一句空话（`flush` 那条路径是**吞异常返 200**）。
     * ⚠️ 端点改名后这里会失配 ⇒ `OpenApiSpecExportTests` 有一条"登记项必须存在于 spec"的断言兜住。
     */
    private static final Set<String> RETRYABLE_ENDPOINTS = Set.of(
            "PUT /api/v1/contents/{id}/progress");

    /**
     * 代码用 `@RequestParam(required = false)` + 手写校验的**必填**字段（`T57` 起手写校验的已知形态）。
     *
     * <p>不覆盖的话 spec 会如实抄 `required: false` ⇒ 前端以为可以不传，实际必然 400。
     * ⚠️ 同上：改名/删端点会让登记失配，由同一组断言兜住。
     */
    private static final Map<String, Set<String>> REQUIRED_QUERY_PARAMS = Map.of(
            "POST /api/v1/contents/videos", Set.of("title", "categoryId"),
            "POST /api/v1/contents/posts", Set.of("title", "categoryId"));

    /**
     * 同上，但必填的是 **multipart part**（`@RequestPart(required = false)` + 手写判空）。
     *
     * <p>`videos` 的 `video` / `cover` 在代码里是必填（无 `required = false`），
     * springdoc 在媒体类型改写后没保住 `required` ⇒ 这里一并声明。
     */
    private static final Map<String, List<String>> REQUIRED_BODY_PARTS = Map.of(
            "POST /api/v1/contents/videos", List.of("video", "cover"),
            "POST /api/v1/uploads/{type}", List.of("file"),
            "PUT /api/v1/contents/{id}/media", List.of("file"),
            "PUT /api/v1/admin/media/{id}", List.of("file"));

    /**
     * GAP-07：**运行期真的会返回显式 {@code null}** 的响应字段（schema 名 → 属性名）。
     *
     * <p>spec 的默认行为只声明"可缺省"（非 required），生成 TypeScript 后是
     * {@code string | undefined}；但这些字段在运行期**一定有这个键、值为 {@code null}**
     * （Jackson 默认包含 null）⇒ 前端必须按 {@code | null} 兜底，否则 TS 类型在说谎。
     *
     * <p>★ 为什么不"把所有非原生类型都标 nullable"：{@code title} / {@code createTime} /
     * {@code username} 这类字段虽然 Java 类型可空，但契约上**承诺非空**（响应构造保证）。
     * 一律标 null 会让生成类型退化成"什么都不能直接用"，反而掩盖真正的运行时分支。
     * 故这里只登记**已确认存在 null 分支**的字段；新增时按同一判据补行。
     *
     * <h2>为什么 `$ref` 字段要用 anyOf</h2>
     * OpenAPI 3.1 表达可空 `$ref` 的标准形态是 {@code anyOf: [$ref, {type: 'null'}]}
     * （{@code nullable: true} 是 3.0 的写法，3.1 已移除）。
     */
    private static final Map<String, Set<String>> NULLABLE_VO_FIELDS = Map.of(
            // 互动态三态：null = 本面不查 / 匿名；这是 R-13 的核心契约
            "InteractionStatusVO", Set.of("isLiked", "isFavorited", "isFollowed", "coin"),
            // 列表按面裁剪的三个计数 + 图文无封面 / 时长未知 + 匿名无 interaction
            "ContentVO", Set.of("coverUrl", "durationSeconds", "interaction",
                    "likeCount", "commentCount", "playCount"),
            // 详情：图文无 videoUrl、视频无 imageUrls、图文无时长、匿名无 interaction
            "ContentDetailVO", Set.of("coverUrl", "durationSeconds", "videoUrl", "imageUrls", "interaction"),
            // 匿名 / 看自己 ⇒ null（三态，不是 false）
            "ProfileVO", Set.of("isFollowed"),
            // 主楼没有 parent / replyTo；叶子没有 children（CommentVO 与 children 的 item 类型各一份）
            // createTime（GAP-12）：DB 列可空 + 存量 Redis 元素没有该键 ⇒ 运行期会显式 null
            "CommentVO", Set.of("parentId", "replyToUserId", "replyToUsername", "children", "createTime"),
            "CommentCacheDTO", Set.of("parentId", "replyToUserId", "replyToUsername", "children", "createTime"),
            // 失效占位条目的展示字段确实为 null
            "FavoriteItemVO", Set.of("coverUrl", "authorName"),
            "WatchLaterItemVO", Set.of("coverUrl", "authorName"),
            "HistoryItemVO", Set.of("coverUrl", "authorName", "durationSeconds"),
            // 单文件上传：只有视频有值；图片 / 封面恒 null（"未知" ≠ 0）
            "UploadedAssetVO", Set.of("durationSeconds"));

    /**
     * 供 spec 护栏测试遍历（{@code OpenApiSpecExportTests}）——**不是业务 API**，运行期不要读它。
     *
     * <p>暴露出来的理由：测试若手抄一份字段清单，注册表改名时两边会一起漂；遍历同一份表，
     * 才能让"注册了但没生效"变红。
     */
    public static Map<String, Set<String>> nullableVoFields() {
        return NULLABLE_VO_FIELDS;
    }


    public OpenApiConfig() {
        // ★ 把 `@CurrentUserId` 标注的参数从 spec 里**全局**剥掉。
        //
        // 它不是请求参数：userId 由 JwtAuthFilter 校验 token 后写进请求属性，再由
        // CurrentUserIdArgumentResolver 注入。springdoc 不知道这层，会把该参数位当成
        // "无法识别的入参"渲染出来 ⇒ spec 里凭空多一个 userId（实测 41 个端点中招），
        // 前端照着传 ⇒ 被忽略（甚至触发 400）⇒ 契约说谎。
        //
        // 放这里而不是逐个 `@Parameter(hidden = true)`：那要改 67 个 handler，
        // 正是本节要避免的"注解污染"。全局注册只此一行。
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class);
    }

    @Bean
    public OpenAPI mySpringTvOpenApi() {
        return new OpenAPI().info(new Info()
                .title("MySpringTV API")
                .version(CONTRACT_VERSION)
                .description("""
                        MySpringTV 对外 HTTP 契约（机器可读形态）。

                        本 spec 由代码生成，**不是**手写：
                        `./mvnw.cmd test -Dtest=OpenApiSpecExportTests -Dapi.openapi.write=true`。
                        规则的**为什么**（状态码分层、码表粒度、路径与方法判据、例外清单）见
                        `architecture/tech/api/接口契约.md`；本 spec 只描述**形状**。

                        三条读法：
                        1. 业务码 `code` 是稳定字符串枚举（字典见 `ErrorCode` schema），HTTP status 只表类别；
                        2. 失败体是 `{code, msg, errors?}`，**没有** `data`；成功体是 `{code:"OK", msg, data?}`；
                        3. 每个端点列出的错误类别是**可能域**（不是穷举），精确分支一律看 `code`。

                        ⚠️ `operationId` 由方法名自动生成（同名方法会带 `_2` 后缀），**不作为契约的一部分**：
                        它会随同名方法增减而变，请按 path + method 调用。
                        """));
    }

    // ==================== 组件：错误信封 + 码字典 ====================

    /**
     * 公共 schema 与错误响应组件。
     *
     * <p>逐端点的错误响应只写一行 `$ref`（{@code #/components/responses/...}），
     * 否则 67 个端点 × 6 个类别会把 spec 撑爆，且改一处文案要改 402 处。
     */
    @Bean
    public OpenApiCustomizer errorComponentsCustomizer() {
        return openApi -> {
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            openApi.setComponents(components);

            components.addSchemas("ErrorCode", errorCodeSchema());
            components.addSchemas("ApiFieldError", apiFieldErrorSchema());
            components.addSchemas("ApiError", apiErrorSchema());

            components.addResponses("ParamInvalid", errorResponse(
                    "请求参数不合法（校验族失败时带 `errors[]`；业务拒绝只有 `msg`）"));
            components.addResponses("Unauthorized", errorResponse(
                    "未登录 / 令牌无效或过期（`code` ∈ `AUTH_UNAUTHORIZED` / `AUTH_TOKEN_EXPIRED`）"));
            components.addResponses("Forbidden", errorResponse("已登录但无权限（`AUTH_FORBIDDEN`）"));
            components.addResponses("NotFound", errorResponse(
                    "资源不存在（`code` 指明是哪类资源，如 `CONTENT_NOT_FOUND`）"));
            components.addResponses("Conflict", errorResponse(
                    "唯一性占用 / 资源状态不允许该操作（`code` 指明具体原因）"));
            components.addResponses("UnsupportedMediaType", errorResponse(
                    "不支持的 `Content-Type`（`PARAM_MEDIA_TYPE_UNSUPPORTED`）"));
            components.addResponses("RateLimited", errorResponse("触发限流（`RATE_LIMITED`）")
                    .addHeaderObject("Retry-After", new Header()
                            .description("建议退避的秒数")
                            .schema(new IntegerSchema())));
            components.addResponses("ServiceUnavailable", errorResponse(
                            "依赖暂不可用（`SYSTEM_UNAVAILABLE`）—— **可重试**，按 `Retry-After` 退避")
                    .addHeaderObject("Retry-After", new Header()
                            .description("建议退避的秒数")
                            .schema(new IntegerSchema())));
            components.addResponses("InternalError", errorResponse(
                    "服务器内部错误（`SYSTEM_INTERNAL` / `SYSTEM_DATABASE`）"));

            // ★ 覆盖 springdoc 按请求 URL 反推的 `servers`（离线生成时会变成 http://localhost）。
            //   `OpenApiCustomizer` 在 `OpenAPIService#updateServers` **之后**执行，所以这里能盖掉
            //   （T60-1 时怀疑相反，T60-2 用实测纠正：`getOpenApi` 里 updateServers 在前、customizer 在后）。
            openApi.setServers(List.of(new Server()
                    .url("/")
                    .description("同源部署：按调用方当前的 origin 拼路径")));
        };
    }

    /**
     * GAP-07：把 {@link #NULLABLE_VO_FIELDS} 里登记的属性改成 OpenAPI 3.1 的可空表达。
     *
     * <p>运行在 springdoc 生成完 schema 之后（{@link OpenApiCustomizer} 的时点），
     * 只改已存在的组件；schema / 属性改名时这里会**静默失配**——故由
     * {@code OpenApiSpecExportTests} 里的一条断言兜住（见 {@code 可空字段确实标了null}）。
     */
    @Bean
    public OpenApiCustomizer nullableVoFieldsCustomizer() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null || components.getSchemas() == null) {
                return;
            }
            NULLABLE_VO_FIELDS.forEach((schemaName, fields) -> {
                Schema<?> schema = components.getSchemas().get(schemaName);
                if (schema == null || schema.getProperties() == null) {
                    return;
                }
                for (String field : fields) {
                    Schema<?> property = (Schema<?>) schema.getProperties().get(field);
                    if (property != null) {
                        schema.getProperties().put(field, nullable(property));
                    }
                }
            });
        };
    }

    /**
     * 把一个属性 schema 改成 3.1 的可空形态：统一用 {@code anyOf: [原 schema, {type: "null"}]}。
     *
     * <p>★ 为什么不用 {@code type: [原类型, "null"]}（3.1 的另一标准写法）：
     * swagger-core 2.2.55 的 YAML 解析器把 {@code type} 当文本读，遇到类型数组会抛
     * {@code ArrayNode cannot be cast to TextNode}（本仓的 spec 快照测试就是用它解析的）。
     * {@code anyOf} 两种解析器都认，且 openapi-typescript 会生成 {@code T | null}。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        Schema wrapper = new Schema<>();
        wrapper.addAnyOfItem(property);
        Schema nullSchema = new Schema<>();
        // ★ type 与 types 同时设置：`type("null")` 只写 types，YAML 序列化会漏掉（实测输出 `{}`）
        nullSchema.setType("null");
        nullSchema.setTypes(new LinkedHashSet<>(Set.of("null")));
        wrapper.addAnyOfItem(nullSchema);
        return wrapper;
    }

    /**
     * 码字典：41 个业务码的**全量**枚举。
     *
     * <p>它是"字典"而不是"该端点会返回的码"—— 后者无法从代码静态推导（同一个端点在不同入参下
     * 可以走到不同的域），硬推会得到一份假的精确性。前端按 `code` 翻字典即可。
     */
    private static Schema<?> errorCodeSchema() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::getCode).toList();
        StringSchema schema = new StringSchema();
        schema.setDescription("业务码字典（= `ErrorCode` 枚举名）。HTTP status 只表类别，精确分支看本字段。");
        schema.setEnum(codes);
        return schema;
    }

    private static Schema<?> apiFieldErrorSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setDescription("`errors[]` 元素：把错误挂到具体输入框上。");
        schema.addProperty("field", new StringSchema()
                .description("**请求里的字段名**；定位不到时该键不出现（不编名）"));
        schema.addProperty("code", schemaRef("#/components/schemas/ErrorCode"));
        schema.addProperty("message", new StringSchema()
                .description("稳定文案；**不回显**异常 message（那里面可能有客户端发来的值）"));
        schema.addRequiredItem("code");
        schema.addRequiredItem("message");
        return schema;
    }

    private static Schema<?> apiErrorSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setDescription("失败体：`{code, msg, errors?}`。**没有 `data`** —— 成功体才是 `{code:\"OK\", msg, data?}`。");
        schema.addProperty("code", schemaRef("#/components/schemas/ErrorCode"));
        schema.addProperty("msg", new StringSchema()
                .description("给人读的兜底文案（前端可覆盖；不要按它的中文做分支）"));
        schema.addProperty("errors", new ArraySchema()
                .items(schemaRef("#/components/schemas/ApiFieldError"))
                .description("字段级错误；只在**校验族**（`@Valid` / 缺参 / 类型不可转换 / 请求体不可解析）出现"));
        schema.addRequiredItem("code");
        schema.addRequiredItem("msg");
        return schema;
    }

    private static ApiResponse errorResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(JSON, new MediaType().schema(schemaRef(ERROR_SCHEMA_REF))));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema schemaRef(String ref) {
        return new Schema().$ref(ref);
    }

    // ==================== 逐端点：错误类别 + 204 + multipart ====================

    /**
     * 按处理器推导每个端点的响应类别（理由见类注释的"错误类别怎么推"）。
     *
     * <p>⚠️ 不写 {@code @ApiResponse}、不改控制器 —— 这个 bean 是"零注解"的兑现方式。
     */
    @Bean
    public OperationCustomizer endpointResponseCustomizer(RequestMappingLookup mappingLookup) {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();

            if (returnsNoContent(handlerMethod)) {
                // springdoc 对 ResponseEntity<Void> 仍给 200 + 空 content ⇒ 事实是 204（T59-1 定稿）。
                // 留着 200 就是 spec 与运行期不一致，前端会按"有 body"去解析空响应。
                responses.remove("200");
                responses.addApiResponse("204", new ApiResponse().description("已生效，无响应体"));
            }
            rewriteMultipart(operation, handlerMethod);
            applyRequiredFlags(operation, endpointKey(handlerMethod));

            RequestMappingLookup.HandlerAuthz authz = mappingLookup.resolve(handlerMethod);
            if (authz.loginRequired()) {
                responses.addApiResponse("401", refResponse("Unauthorized"));
                // ★ 403 的**第二个来源**是"所有权校验"（各域 Service 抛 `ForbiddenException`），
                //   它无法从注解推导（`@RequiresLogin` 只说"要登录"，不说"只许动自己的"）。
                //   ⇒ 按《接口契约-护栏与Spec》§二 的"类别级可能域"口径：只要需要身份就可能因所有权 403，一律声明。
                //   代价 = 少数端点会多一个永不触发的分支；换来"不会漏"（漏了前端没兜底可写）。
                responses.addApiResponse("403", refResponse("Forbidden"));
            }
            if (operation.getRequestBody() != null) {
                responses.addApiResponse("415", refResponse("UnsupportedMediaType"));
            }
            if (operation.getRequestBody() != null || hasParameters(operation)) {
                responses.addApiResponse("400", refResponse("ParamInvalid"));
                // ★ 404 不能只看"路径里有 `{var}`"：本仓有一批"目标资源 id 在 **body / query**"的端点
                //   （`POST /comments` 的 contentId/parentId、`POST /favorites` 的 contentId/folderId、
                //   `GET|DELETE /favorites` 的 folderId）—— 它们同样会回 404。
                responses.addApiResponse("404", refResponse("NotFound"));
            }
            if (isWrite(handlerMethod)) {
                responses.addApiResponse("409", refResponse("Conflict"));
            }
            if (RETRYABLE_ENDPOINTS.contains(endpointKey(handlerMethod))) {
                // ★ 503 只有 `HistoryService#report` 会抛（`ServiceBusyException` → 503 + Retry-After）；
                //   `flush` 路径是**吞掉异常返回 200**（数据留缓冲等兜底），所以不能全站挂。
                responses.addApiResponse("503", refResponse("ServiceUnavailable"));
            }
            responses.addApiResponse("429", refResponse("RateLimited"));
            responses.addApiResponse("500", refResponse("InternalError"));
            return operation;
        };
    }

    private static ApiResponse refResponse(String componentName) {
        return new ApiResponse().$ref("#/components/responses/" + componentName);
    }

    /** `ResponseEntity<Void>` ⇒ 204（本仓 DELETE 与部分 PUT 的定稿形状，见 §2.4 / §2.5）。 */
    private static boolean returnsNoContent(HandlerMethod handlerMethod) {
        ResolvableType type = ResolvableType.forMethodReturnType(handlerMethod.getMethod());
        Class<?> raw = type.resolve();
        if (raw == null || !ResponseEntity.class.isAssignableFrom(raw)) {
            return false;
        }
        return type.getGeneric(0).resolve() == Void.class;
    }

    /** `@RequestPart` / `MultipartFile` 入参 ⇒ 请求体媒体类型必须是 multipart，否则前端会按 JSON 传文件。 */
    private static void rewriteMultipart(Operation operation, HandlerMethod handlerMethod) {
        if (!hasMultipartParam(handlerMethod)) {
            return;
        }
        RequestBody body = operation.getRequestBody();
        if (body == null || body.getContent() == null || body.getContent().isEmpty()) {
            return;
        }
        MediaType mediaType = body.getContent().get(JSON);
        if (mediaType == null) {
            mediaType = body.getContent().values().iterator().next();
        }
        body.getContent().clear();
        body.getContent().addMediaType(MULTIPART, mediaType);
    }

    private static boolean hasMultipartParam(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethodParameters())
                .anyMatch(p -> MultipartFile.class.isAssignableFrom(p.getParameterType())
                        || p.hasParameterAnnotation(RequestPart.class));
    }

    private static boolean hasParameters(Operation operation) {
        return operation.getParameters() != null && !operation.getParameters().isEmpty();
    }

    /** 把 {@link #REQUIRED_QUERY_PARAMS} / {@link #REQUIRED_BODY_PARTS} 的登记套到 operation 上。 */
    private static void applyRequiredFlags(Operation operation, String key) {
        Set<String> requiredQuery = REQUIRED_QUERY_PARAMS.getOrDefault(key, Set.of());
        if (operation.getParameters() != null && !requiredQuery.isEmpty()) {
            operation.getParameters().stream()
                    .filter(p -> requiredQuery.contains(p.getName()))
                    .forEach(p -> p.setRequired(true));
        }
        List<String> requiredParts = REQUIRED_BODY_PARTS.get(key);
        if (requiredParts == null || operation.getRequestBody() == null
                || operation.getRequestBody().getContent() == null
                || operation.getRequestBody().getContent().isEmpty()) {
            return;
        }
        Schema<?> schema = operation.getRequestBody().getContent().values().iterator().next().getSchema();
        if (schema != null) {
            requiredParts.forEach(schema::addRequiredItem);
        }
    }

    /**
     * `"METHOD /path"` —— 与登记簿 / spec 同一个键形，用来查上面那几张显式登记表。
     *
     * <p>只取第一个路径与方法：本仓 67 条端点都是单路径单方法（`ApiContractInventoryTests` 的
     * 扫描面自检也断言了"无非限定方法的映射"）⇒ 多路径映射出现时会先在那里红。
     */
    private static String endpointKey(HandlerMethod handlerMethod) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequestMapping.class);
        if (mapping == null || mapping.value().length == 0) {
            return "";
        }
        if (mapping.method().length == 0) {
            return "ANY " + mapping.value()[0];
        }
        return mapping.method()[0].name() + " " + mapping.value()[0];
    }

    /** 非 GET ⇒ 写端点（`409` 的"状态/唯一性冲突"只对写有意义）。 */
    private static boolean isWrite(HandlerMethod handlerMethod) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequestMapping.class);
        if (mapping == null || mapping.method().length == 0) {
            return true;
        }
        return Arrays.stream(mapping.method()).anyMatch(m -> m != RequestMethod.GET);
    }
}
