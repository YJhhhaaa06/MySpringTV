package io.github.yjhhhaaa06.videoweb.common.config;

import io.github.yjhhhaaa06.videoweb.common.security.CurrentUserId;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI spec 的**唯一装配点**（`T60`）。口径见 {@code architecture/tech/api/接口契约.md} §2.10。
 *
 * <p>本类只做三件事：给 spec 注明身份（标题 / 契约版本）、剥掉"假参数"、声明扫描面
 * （扫描面在 {@code application.yaml} 的 {@code springdoc.packages-to-scan}，与
 * {@code ApiContractInventoryTests} 的 {@code BASE_PACKAGE} 同口径）。
 *
 * <h2>为什么这里不改任何控制器</h2>
 * spec 是**产物**，代码是**源**（§2.10 第一条）。给 67 个 handler 逐个加 {@code @Operation} /
 * {@code @ApiResponse} 会把"契约文档"变成第二处需要人工维护的事实源，而且 diff 会被注解淹没。
 * 因此：默认**零注解**，一切靠本类的装配 + 全局扩展点
 * （{@code OperationCustomizer} 按 {@code HandlerMethod} 推导、{@code OpenApiCustomizer} 注册公共 schema，
 * 归 `T60-2`）。允许的最小注解面只有一种：**字段单位/精度从类型上看不出来**的地方
 * （例：{@code coin} 是十分位 `15 = 1.5 枚`）—— 那种地方不加说明就是 spec 在说谎。
 *
 * <h2>契约版本（{@link #CONTRACT_VERSION}）不是 Maven 版本</h2>
 * 它描述的是**对外形状的兼容性**：破坏性变更（删字段 / 改类型 / 改路径）才 +1，
 * 加字段不加。两者是不同的事实，故意分开写 —— 用 Maven 版本会让"spec 变了一版"与
 * "包发了一版"混为一谈（本仓版本号 0.0.1-SNAPSHOT 从不递增）。
 */
@Configuration
public class OpenApiConfig {

    /** 对外契约版本。规则见类注释：**破坏性变更才 +1**。 */
    public static final String CONTRACT_VERSION = "1.0.0";

    public OpenApiConfig() {
        // ★ 把 `@CurrentUserId` 标注的参数从 spec 里**全局**剥掉。
        //
        // 它不是请求参数：userId 由 JwtAuthFilter 校验 token 后写进请求属性，再由
        // CurrentUserIdArgumentResolver 注入。springdoc 不知道这层，会把该参数位当成
        // "无法识别的入参"渲染出来（query 参数或 requestBody）⇒ spec 里凭空多一个
        // userId，前端照着传 ⇒ 被忽略（甚至触发 400）⇒ 契约说谎。
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

                        ⚠️ **中间态（T60-1 交付，T60-2 收口后删除本段）**：错误响应、
                        204 无体、multipart 媒体类型、operationId 命名、servers 尚未定稿 ——
                        本 spec 此时只可用于"端点集与入出参形状"的比对，**不要据此写前端调用**。

                        本 spec 由代码生成，**不是**手写：`./mvnw.cmd test -Dtest=OpenApiSpecExportTests -Dapi.openapi.write=true`。
                        规则的**为什么**（状态码分层、码表粒度、路径与方法判据、例外清单）见
                        `architecture/tech/api/接口契约.md`；本 spec 只描述**形状**（路径 / 方法 / 参数 / 响应）。

                        两条读法提醒：
                        1. 业务码 `code` 是稳定字符串枚举，HTTP status 只表类别；
                        2. 失败体是 `{code, msg, errors?}`，**没有** `data`；成功体是 `{code:"OK", msg, data?}`。
                        """));
    }
}
