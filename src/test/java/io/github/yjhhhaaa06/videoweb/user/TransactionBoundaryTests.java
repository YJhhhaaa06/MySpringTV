package io.github.yjhhhaaa06.videoweb.user;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import io.github.yjhhhaaa06.videoweb.user.model.dto.RegisterRequest;
import io.github.yjhhhaaa06.videoweb.user.model.vo.LoginVO;
import io.github.yjhhhaaa06.videoweb.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

/**
 * 事务边界回归测试 —— 锁定《事务边界决策表》U-1 的刻意语义。
 *
 * <h2>为什么必须有这个测试</h2>
 * TV 的 {@code registerAndLogin} 把「注册」与「自动登录」拆成**两个独立事务**，
 * 并在原注释里写明理由：注册成功即视为成功，自动登录失败只返回 {@code token == null}，
 * 让前端提示"请手动登录"。
 *
 * <p>这个设计**看起来像是可以"优化"的缺陷**——把整个方法标上 {@code @Transactional}
 * 似乎更"原子"。但那会让自动登录失败**回滚掉已经成功的注册**，直接违反产品语义
 * （用户以为注册失败，实际也真的没注册成功）。
 *
 * <p>本测试刻意制造"注册成功但登录必然失败"的局面，然后断言**注册行仍然存在**。
 * 如果有人（包括未来的我）"顺手"给 registerAndLogin 加上 {@code @Transactional}，
 * 这个测试会失败——这正是它存在的意义：把一个"看起来像 bug、实际是规格"的地方钉住。
 *
 * <h2>手法</h2>
 * 用 {@code @MockitoSpyBean} 把 {@link UserDao} 换成 spy（真实执行 + 可局部打桩），
 * 只把"按 id 查用户"打桩成返回 null，使自动登录必然抛 {@code UserNotFoundException}。
 * 方法**逐一列举**（而非 any()），以保证除这一条外 DAO 全走真实路径——
 * 否则测的就不是"注册是否真的提交了"。
 */
class TransactionBoundaryTests extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private UserDao userDao;

    private static final String PHONE = "13800000099";
    private static final String USERNAME = "boundary-user";
    private static final String PASSWORD = "abc123456";

    @BeforeEach
    void resetState() {
        Mockito.reset(userDao);
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    @DisplayName("U-1：注册成功但自动登录失败 → 注册行仍在（两个独立事务，非一个原子事务）")
    void 自动登录失败不回滚注册() {
        // 打桩：注册后的"按 id 查用户"返回 null ⇒ 自动登录必然失败
        doReturn(null).when(userDao).findByIdForLogin(anyLong());

        LoginVO result = userService.registerAndLogin(
                new RegisterRequest(USERNAME, PASSWORD, PHONE));

        // 1) 对外语义：注册成功，只是没拿到 token（前端据此提示手动登录）
        assertThat(result.id()).isPositive();
        assertThat(result.username()).isEqualTo(USERNAME);
        assertThat(result.token()).isNull();

        // 2) ★核心断言★ 注册行必须已经提交落库。
        //    若 registerAndLogin 被整体标为 @Transactional，登录失败会回滚插入，
        //    此处查询将得到 0 行 —— 测试失败即说明有人误改了事务边界。
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE phone = ?", Long.class, PHONE);
        assertThat(count)
                .as("注册与自动登录必须是两个独立事务：登录失败不得回滚已提交的注册（决策表 U-1）")
                .isEqualTo(1L);

        // 3) 且这条记录是完整可用的（能查到用户名，口令已散列）
        String hashed = jdbcTemplate.queryForObject(
                "SELECT hashed_password FROM users WHERE phone = ?", String.class, PHONE);
        assertThat(hashed).startsWith("$2");
    }

    @Test
    @DisplayName("U-2：注册本身的失败要真的回滚，且不被 U-1 兜底吞掉")
    void 注册失败必须回滚且不被吞掉() {
        // 先占一个手机号
        userService.registerAsUser(new RegisterRequest(USERNAME, PASSWORD, PHONE));

        // 再用同手机号注册：应抛业务异常（不是返回 token=null 的"成功"）
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                io.github.yjhhhaaa06.videoweb.common.exception.BusinessException.class,
                () -> userService.registerAndLogin(new RegisterRequest("other", PASSWORD, PHONE)))
                .getCode())
                .as("注册失败必须照旧抛错，不能被 U-1 的手动登录兜底吞掉")
                .isEqualTo(409);

        // 且没有新增行
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE phone = ?", Long.class, PHONE);
        assertThat(count).isEqualTo(1L);
    }
}
