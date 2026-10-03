package io.github.yjhhhaaa06.videoweb.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计记录器（第三批 T2 / 账 B12）——承接 TV {@code util.AuditLog}，
 * 管理端与用户敏感变更的**成功路径**留痕，回答"谁、何时、做了什么"。
 *
 * <h2>行形态（时间与 reqId 由 pattern 前缀提供，不在本行重复）</h2>
 * <pre>{@code ts=… level=INFO logger=audit req=6a1b2c3d0f3e0001 msg=action=admin.content.hide operatorId=13 target=contentId:42 result=success}</pre>
 *
 * <h2>只落 {@code audit} 输出端</h2>
 * {@code logback-spring.xml} 把 {@code audit} logger 配成
 * {@code additivity=false} + 专属 appender ⇒ 审计行**不进 system.log、也不进控制台**，
 * 且阈值**固定 INFO、不随级别配置变**（审计是合规留痕，不得被运维把级别调高而静默）。TV 同款口径。
 *
 * <h2>三条纪律（逐条沿袭 TV，勿"顺手优化"）</h2>
 * <ol>
 *   <li><b>只记成功</b>：失败路径由既有 WARN/ERROR 记录承载，审计不重复记——
 *       避免"同一失败两条记录"。</li>
 *   <li><b>审计是旁路</b>：写入失败一律吞掉并降级，**绝不影响业务**（与"缓存失败不导致业务失败"同族）。
 *       捕获 {@code Exception} 而非 {@code Throwable}——{@code Error}（OOM 等）不属"审计写失败"该吞的范畴。</li>
 *   <li><b>不记敏感值</b>：{@code target} 只放**对象标识**（{@code contentId:42} / {@code mediaId:7} /
 *       {@code userId:13}），不放变更后的值。请求体 / query 串 / 密码 / token 一律不落盘。</li>
 * </ol>
 *
 * <h2>为什么是静态工具类而不是 {@code @Component}</h2>
 * 它不持任何可变状态（logger 由 SLF4J 单例提供），且调用点在 service/controller 内——
 * 注入一个 bean 只会让每个调用方多一个构造参数，换不来任何可测性
 * （测试读的是**输出端的行**，不是这个对象）。TV 也是静态类，形态保持一致便于对照。
 */
public final class AuditLog {

    /** 审计输出端专属 logger 名（{@code logback-spring.xml} 按此名装配 appender）。 */
    public static final String AUDIT_LOGGER_NAME = "audit";

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger(AUDIT_LOGGER_NAME);

    private AuditLog() {
    }

    /**
     * 记一条**成功**审计记录。每个操作点各调用一次、各恰好一条。
     *
     * @param action     操作名（稳定字面量）：管理端 = {@code admin.<域>.<动作>}，用户侧 = {@code user.<方法语义>}
     * @param operatorId 操作者 userId（管理端来自 {@code @CurrentUserId}；用户侧即方法入参）
     * @param target     操作对象标识，形如 {@code contentId:42} / {@code mediaId:7} / {@code userId:13}
     */
    public static void success(String action, Long operatorId, String target) {
        try {
            AUDIT_LOGGER.info(buildLine(action, operatorId, target));
        } catch (Exception ignored) {
            // 旁路降级（红线）：审计写失败不得影响业务。此处**不再补记日志**——在异常路径上
            // 再触发一次日志写入会放大故障；底层 Handler 的 ErrorManager 负责上报 IO 故障。
        }
    }

    /**
     * 审计行 msg 内容（**纯函数**，JUnit 直测）：
     * {@code action=… operatorId=… target=… result=success}。
     * {@code operatorId == null} → {@code -}（与访问日志的 {@code userId=-} 同口径）。
     */
    static String buildLine(String action, Long operatorId, String target) {
        return "action=" + action
                + " operatorId=" + (operatorId == null ? "-" : operatorId)
                + " target=" + target
                + " result=success";
    }
}
