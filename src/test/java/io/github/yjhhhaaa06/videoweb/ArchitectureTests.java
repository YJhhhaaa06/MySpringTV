package io.github.yjhhhaaa06.videoweb;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构约束测试（S6-B3）——把**模块边界**从文档约定变成**会失败的测试**。
 *
 * <h2>为什么需要它</h2>
 * S6 收口时实测出：业务模块之间是一个**近似完全图 + 3 个环**
 * （{@code content⇄comment}、{@code content⇄like}、{@code comment⇄like}），
 * 且 {@code common} 反向依赖 {@code user}。这些边在 S1–S5 期间**逐片累积**，
 * 每一片单独看都合理，合起来就成了"按模块切片必须决定要不要完整搬"的根因。
 *
 * <p>根因不是没人知道要守边界——SOP 里写了、复盘里也写了。**是没有任何机制能拦住它。**
 * 规则只写在文档里，下一次切片必然再破一次。本类把那几条规则编码成测试：
 * 破边界 = 构建失败。
 *
 * <h2>只引 ArchUnit 核心包，不用 archunit-junit5 引擎</h2>
 * 本项目跑 JUnit Platform 6（Boot 4 自带），而 archunit-junit5 的引擎是给 Platform 1.x 写的。
 * 核心包是纯库，直接在普通 {@code @Test} 里 import + check 即可——功能够用、依赖面更小。
 *
 * <h2>四条规则与它们的来历</h2>
 * <table>
 *   <caption>规则 → 治什么</caption>
 *   <tr><th>规则</th><th>治什么</th></tr>
 *   <tr><td>{@link #common_不得依赖任何业务模块()}</td>
 *       <td>S6-B1：{@code JwtAuthFilter} 曾直接注入 {@code UserService}</td></tr>
 *   <tr><td>{@link #任何模块都不得触碰别的模块的缓存实现()}</td>
 *       <td>S6-B2：缓存曾是跨域 API（用户原始痛点）</td></tr>
 *   <tr><td>{@link #跨域事件只能由自己的event包消费()}</td>
 *       <td>S6-B2d：曾出现"评论域去发内容域的事件"</td></tr>
 *   <tr><td>{@link #content不得依赖comment的服务与模型()}</td>
 *       <td>S6-B2d：{@code content⇄comment} 双向环</td></tr>
 * </table>
 *
 * <p><b>刻意不写的规则</b>：不做"全局无环"断言。
 * {@code content→like} 与 {@code comment→like} 的**双向**是真实业务需要——
 * 点赞要改对方的计数列（content-thin，同事务），对方的列表要显示"我点过赞吗"。
 * 强行消环只能靠合并域或引入异步，代价远大于收益。本测试只禁**已经证明有害**的那些边
 * （缓存实现、跨域发事件、common 倒挂），不假装架构比现实更干净。
 */
class ArchitectureTests {

    /**
     * 业务模块名（与包名一致）。
     * {@code upload} 由 S7 新增、{@code admin} 由 S8 新增，一并纳入边界约束
     * （否则新域会成为**没有任何规则约束的空洞**）。
     */
    private static final String[] DOMAINS = {"user", "content", "comment", "like", "follow", "coupon",
            "upload", "admin"};

    private static final String BASE = "io.github.yjhhhaaa06.videoweb";

    /** 只导入一次：全项目类文件扫描有成本，四个用例共用。 */
    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages(BASE);

    /**
     * 规则 1：{@code common} 是公共底座，**不得依赖任何业务模块**。
     *
     * <p>由来：{@code common.security.JwtAuthFilter} 曾直接注入 {@code user.service.UserService}
     * 来判管理员（{@code /api/admin} 需 {@code role == 1}）。倒挂的害处是具体的——
     * {@code common} 从此无法在不牵引 {@code user} 的情况下复用或测试；
     * 而且这条边会随"还有谁要问这个问题"不断加宽。
     *
     * <p>正解是**依赖倒置**：接口 {@code common.security.AdminChecker} 放在基建，
     * 实现由 {@code user} 提供（见 S6-B1）。业务 → 基建是允许的，反过来的任何一条边都不允许。
     */
    @Test
    void common_不得依赖任何业务模块() {
        for (String domain : DOMAINS) {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("..videoweb.common..")
                    .should().dependOnClassesThat().resideInAPackage("..videoweb." + domain + "..")
                    .because("common 是所有模块的公共底座，依赖业务模块会造成倒挂（S6-B1）。"
                            + "需要业务能力时，请在 common 里声明窄端口，由业务模块实现");
            rule.check(CLASSES);
        }
    }

    /**
     * 规则 2：**任何模块都不得触碰别的模块的 {@code cache} 包**。
     *
     * <p>由来（用户原始痛点）：缓存类被大量跨域 import——
     * {@code ContentService} 用 {@code comment.cache.CommentCache}、
     * {@code ContentStatusFiller}/{@code ProfileService} 用 {@code follow.cache.FollowCache}、
     * {@code LikeChangedListener} 用 {@code content.cache.ContentCache} 与
     * {@code comment.cache.CommentCache}。
     *
     * <p>后果是**缓存实现变成了跨域 API**：它的方法签名、降级语义、甚至"要不要建空标记"
     * 都成了别的域要跟着走的东西。缓存本该是模块的**私有实现细节**。
     *
     * <p>正解：跨域只走**服务契约**（如 {@code FollowService.batchIsFollowing}）
     * 或**事件**（各方订阅后失效自己的缓存），见 S6-B2a/B2b/B2c/B2d。
     */
    @Test
    void 任何模块都不得触碰别的模块的缓存实现() {
        for (String from : DOMAINS) {
            for (String to : DOMAINS) {
                if (from.equals(to)) {
                    continue;
                }
                ArchRule rule = noClasses()
                        .that().resideInAPackage("..videoweb." + from + "..")
                        .should().dependOnClassesThat().resideInAPackage("..videoweb." + to + ".cache..")
                        .because(from + " 不得触碰 " + to + " 的缓存实现（S6-B2）。"
                                + "跨域请走对方的 Service 契约，或订阅事件后失效自己的缓存");
                rule.check(CLASSES);
            }
        }
    }

    /**
     * 规则 3：**跨域事件只能由自己的 {@code event} 包消费**。
     *
     * <p>含义：{@code X} 域想响应 {@code Y} 域的事件，那个 import 必须写在
     * {@code X.event} 包（监听器）里，不能写在 {@code X.service} / {@code X.controller}。
     *
     * <p>由来：S5 时 {@code CommentService}（service 包）直接
     * {@code publishEvent(ContentCacheChangedEvent.invalidate(...))}——
     * 评论域去发**内容域**的事件，只为了失效内容的 key。这类"替别人声明事实"的写法
     * 让两个域的编译期耦合不断加深，且从包结构上完全看不出来。
     *
     * <p>正解：每个域**只声明自己发生了什么**（{@code CommentCacheChangedEvent}），
     * 需要响应的人自己订阅（{@code content.event.CommentCacheChangedContentListener}）。
     * 这样"谁依赖谁"在包结构上一眼可见。
     */
    @Test
    void 跨域事件只能由自己的event包消费() {
        for (String from : DOMAINS) {
            for (String to : DOMAINS) {
                if (from.equals(to)) {
                    continue;
                }
                ArchRule rule = noClasses()
                        .that().resideInAPackage("..videoweb." + from + "..")
                        .and().resideOutsideOfPackage("..videoweb." + from + ".event..")
                        .should().dependOnClassesThat().resideInAPackage("..videoweb." + to + ".event..")
                        .because(from + " 只能在 event 包里消费 " + to + " 的事件（S6-B2d）。"
                                + "service/controller 直接引用别域事件，会让包结构看不出依赖方向");
                rule.check(CLASSES);
            }
        }
    }

    /**
     * 规则 4：{@code content} **不得依赖 {@code comment} 的服务与模型**。
     *
     * <p>由来：{@code ContentService} 曾承载 {@code /comment/show} 的读路径
     * （TV 原样如此，S5 刻意保持同一实现位置），于是被迫 import 评论域的
     * 缓存、DAO、VO、Service 四类东西，构成 {@code content⇄comment} 双向环。
     *
     * <p>S6-B2d 把门禁收敛成 {@code ContentService.isCommentReadable}（一条返回 boolean 的窄查询），
     * 评论数据回到评论域。此后 {@code content → comment} **只剩 {@code comment.dao}**
     * ——那是 S2 就确立的 content-thin 薄依赖（删内容时级联软删评论，同事务），属**有意保留**。
     *
     * <p>故本规则只禁 service/model/cache，不禁 dao。这是"按现实划线"，不是"按理想划线"。
     */
    @Test
    void content不得依赖comment的服务与模型() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..videoweb.content..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..videoweb.comment.service..", "..videoweb.comment.model..",
                        "..videoweb.comment.cache..")
                .because("content 对 comment 只应有 content-thin 的 DAO 薄依赖（S6-B2d）。"
                        + "需要评论能否被读时，用 ContentService.isCommentReadable 这条窄查询");
        rule.check(CLASSES);
    }
}
