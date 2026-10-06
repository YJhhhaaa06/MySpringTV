package io.github.yjhhhaaa06.videoweb.user.service;

import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.DuplicatePhoneException;
import io.github.yjhhhaaa06.videoweb.common.exception.InvalidPasswordException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.exception.PasswordIncorrectException;
import io.github.yjhhhaaa06.videoweb.common.exception.UserNotFoundException;
import io.github.yjhhhaaa06.videoweb.common.security.AdminChecker;
import io.github.yjhhhaaa06.videoweb.common.security.JwtService;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import io.github.yjhhhaaa06.videoweb.user.event.UserRenamedEvent;
import io.github.yjhhhaaa06.videoweb.user.model.dto.ChangePasswordRequest;
import io.github.yjhhhaaa06.videoweb.user.model.dto.RegisterRequest;
import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import io.github.yjhhhaaa06.videoweb.user.model.vo.LoginVO;
import io.github.yjhhhaaa06.videoweb.user.model.vo.UserInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 用户业务。
 *
 * <p>迁移自 TV {@code com.itheima.user.service.UserService}。
 * 骨架（{@code transactionTemplate.execute} / {@code conn} 穿透 / {@code catch SQLException} 包装）已删除，
 * 业务语义（校验顺序、异常类型、事务拆分）逐条保留——对照表见《事务边界决策表》U 系列。
 */
@Slf4j
@Service
public class UserService implements AdminChecker {

    /** 密码规则，从 TV {@code PasswordUtil.isPasswordLegal} 承接：6-16 位字母或数字。 */
    private static final Pattern PWD_PATTERN = Pattern.compile("^[a-zA-Z0-9]{6,16}$");

    private final UserDao userDao;
    private final JwtService jwtService;
    private final ApplicationEventPublisher events;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserDao userDao, JwtService jwtService, ApplicationEventPublisher events) {
        this.userDao = userDao;
        this.jwtService = jwtService;
        this.events = events;
    }

    // ========================================================================
    // U-1：registerAndLogin —— 刻意**不**加 @Transactional
    // ========================================================================

    /**
     * 注册并自动登录。**注册成功即视为成功**（沿袭 TV 池 U-16 兜底语义）。
     *
     * <p><b>⚠️ 本方法不得标 {@code @Transactional}</b>。理由见《事务边界决策表》U-1：
     * 注册与自动登录是**两个独立事务**（注册事务先提交，再做登录查询）。
     * 若整体纳入一个事务，"自动登录失败"会连带回滚掉已成功的注册——
     * 直接违反产品语义（旧行为是注册成功、返回 token=null、提示手动登录）。
     *
     * <p>自动登录失败时不再抛异常，而是返回 {@code token == null} 的 {@link LoginVO}，token 由前端提示。
     * 但**注册本身失败**（手机号/用户名占用）仍照旧抛错，不被兜底吞掉。
     */
    public LoginVO registerAndLogin(RegisterRequest request) {
        long id = registerAsUser(request);
        // T2 里程碑（B12）：注册成功。**放在这里而不是 registerAsUser 内**——
        // 本方法刻意无事务（U-1），故这一行执行时内层插入已提交/落库 ⇒ 不会"未提交先宣告成功"。
        // 将来若 U-2 被拍板修好（registerAsUser 成为真事务），本行仍在其**之外**，语义不变。
        // 只记 userId：账号（手机号）与密码一律不落盘（TV LOG_CONVENTION）。
        log.info("用户注册成功, userId={}", id);
        try {
            return login(id, request.password());
        } catch (BusinessException e) {
            // 注册已提交：不能把自动登录失败报成注册失败；留痕不静默
            log.warn("注册后自动登录失败, userId={}, 改为提示手动登录", id);
            return new LoginVO(id, request.username(), null);
        }
    }

    /**
     * 用户注册，返回新用户 id。
     *
     * <p>决策表 U-2：校验占用与插入必须原子 ⇒ {@code @Transactional}（REQUIRED）。
     * 并发下唯一键仍在 DB 层兜底，由 {@code GlobalExceptionHandler} 把
     * {@code DuplicateKeyException} 映射为 409，使"先查后插"的竞态对客户端不可见。
     */
    @Transactional
    public long registerAsUser(RegisterRequest request) {
        String username = request.username();
        String phone = request.phone();

        if (!isPasswordLegal(request.password())) {
            throw new InvalidPasswordException();
        }
        if (userDao.isPhoneUsed(phone)) {
            throw new DuplicatePhoneException();
        }
        if (userDao.isUsernameUsed(username)) {
            throw new ConflictException("用户名已被占用");
        }

        User user = new User();
        user.setUsername(username);
        user.setPhone(phone);
        user.setHashedPassword(passwordEncoder.encode(request.password()));
        userDao.insert(user);   // useGeneratedKeys 回填 id
        return user.getId();
    }

    // ========================================================================
    // 登录
    // ========================================================================

    /** 按手机号登录（对外 HTTP 入口）。 */
    public LoginVO login(String phone, String rawPassword) {
        User dbUser = userDao.findByPhoneForLogin(phone);
        String token = doLogin(dbUser, rawPassword);
        return new LoginVO(dbUser.getId(), dbUser.getUsername(), token);
    }

    /**
     * 按 id 登录。仅供 {@link #registerAndLogin} 内部调用（与 TV 一致——
     * TV 的 login(id, pwd) 是 registerAndLogin 的内部路径，无独立 HTTP 入口）。
     */
    public LoginVO login(long id, String rawPassword) {
        User dbUser = userDao.findByIdForLogin(id);
        String token = doLogin(dbUser, rawPassword);
        return new LoginVO(dbUser.getId(), dbUser.getUsername(), token);
    }

    /**
     * 执行登录校验并签发 token。
     *
     * <p>决策表 U-3/U-4：TV 这里包了 {@code transactionTemplate.execute}，但**纯读无写**——
     * 那个事务只是"取连接的手段"，没有原子性需求，故新实现不加事务注解。
     * 校验顺序与异常类型与 TV 逐条一致。
     */
    private String doLogin(User user, String rawPassword) {
        if (user == null) {
            throw new UserNotFoundException();
        }
        if (!isPasswordCorrect(rawPassword, user.getHashedPassword())) {
            throw new PasswordIncorrectException();
        }
        String token = jwtService.generateToken(user.getId());
        // 里程碑：登录成功。只记 userId——账号（手机号）与 token 一律不落盘（沿袭 TV LOG_CONVENTION）
        log.info("登录成功, userId={}", user.getId());
        return token;
    }

    // ========================================================================
    // 资料与修改
    // ========================================================================

    /** 当前用户信息（新增端点，见 UserInfoVO 说明）。 */
    @Transactional(readOnly = true)
    public UserInfoVO getProfile(long userId) {
        User user = userDao.findByIdForProfile(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        return new UserInfoVO(user.getId(), user.getUsername(), user.getPhone(), user.isAdmin());
    }

    /**
     * 修改密码。
     *
     * <p>校验口径沿袭 TV：先查用户存在 → 再校验手机号是否**本人** → 再校验原密码 → 再校验新密码合法性 → 落库。
     *
     * <p>⭐ **校验顺序不是随意的**：TV 的 {@code doChangePassword} 里，"手机号比对"**严格先于**"旧密码比对"
     * （{@code UserService.java:188} 早于 {@code :193}）。这意味着"错手机号 + 错旧密码"只会回 400（手机号不匹配），
     * 不会回 401。本顺序由 {@code UserFlowTests.手机号比对先于旧密码_错号且错密码回400} 钉住。
     *
     * <p>⭐ **手机号比对只判"是否等于本人"，不判格式**：老侧口径在这两级上本来就不同
     * （格式在 {@code CommandConverter} 用**宽** {@code phoneCheck}，本处只比对）。
     * 若在此加严到注册的 {@code ^1[3-9]\d{9}$}，库里存在的宽口径历史号（《遗留台账》E-6）
     * 将**连自己的密码都改不了**。详见 {@link ChangePasswordRequest} 的类注释。
     */
    @Transactional
    public void changePassword(long userId, ChangePasswordRequest request) {
        User user = userDao.findByIdForLogin(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        if (!request.phone().equals(user.getPhone())) {
            // TV 同位置抛 ParamException("手机号不匹配") ⇒ 400（先于旧密码判定，顺序即冻结契约）
            throw new ParamException("手机号不匹配");
        }
        if (!isPasswordCorrect(request.oldPassword(), user.getHashedPassword())) {
            throw new PasswordIncorrectException();
        }
        if (!isPasswordLegal(request.newPassword())) {
            throw new InvalidPasswordException();
        }
        userDao.updatePassword(userId, passwordEncoder.encode(request.newPassword()));
    }

    /** 修改用户名。占用校验与更新同事务（同 U-2 口径）。 */
    @Transactional
    public void changeUserName(long userId, String newUsername) {
        if (newUsername == null || newUsername.isBlank()) {
            throw new io.github.yjhhhaaa06.videoweb.common.exception.ParamException("用户名不能为空");
        }
        if (userDao.isUsernameUsed(newUsername)) {
            throw new ConflictException("用户名已被占用");
        }
        userDao.updateUsername(userId, newUsername);
        // S5：改名必须级联失效该作者的内容缓存（内容详情/主页里的 authorName 是从 users JOIN 来的
        // 冗余快照）。TV 在这里直接调 contentCache.invalidateAuthorContentKeys(userId)，
        // 本实现改为**发事件**（提交后由 ContentCacheChangedListener 处理）——user 域不必知道
        // content 域有缓存。旧 pytest 的 test_change_user_name.py 对这条级联有逐字断言。
        events.publishEvent(new UserRenamedEvent(userId, newUsername));
    }

    /**
     * 是否管理员（承接 TV {@code UserService.isAdmin}，供 admin 路径鉴权用）。
     *
     * <p>本方法同时是 {@link AdminChecker} 端口的实现（S6-B1）——由本类提供实现、
     * 由 {@code common.security.JwtAuthFilter} 按接口消费，避免 {@code common} 反向依赖 {@code user}。
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isAdmin(long userId) {
        return userDao.findRoleById(userId) == 1;
    }

    // ========================================================================
    // 密码工具（从 TV PasswordUtil 内联——原本就是 Spring BCrypt，无需移植成本）
    // ========================================================================

    private boolean isPasswordCorrect(String rawPassword, String hashedPassword) {
        return rawPassword != null && hashedPassword != null
                && passwordEncoder.matches(rawPassword, hashedPassword);
    }

    private boolean isPasswordLegal(String password) {
        return password != null && PWD_PATTERN.matcher(password).matches();
    }
}
