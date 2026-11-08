import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;

/**
 * {@code GET /api/v1/users/me} 读路径的**数据库侧**基线（L2 优化前后对照）。
 *
 * <p>只测 JDBC 这一层：不含 Tomcat / 过滤器链 / Jackson / 限流。目的是把
 * "4 条窄查询 + 事务" 与 "1 条合并查询 + 无事务" 的差距量化出来，并给出单次往返的地板价
 * （{@code select 1}）与"多读一列"的对照（8 列变体）。
 *
 * <h2>为什么这个脚本要入库</h2>
 * 数字写进了 {@code UserService#getProfile} / {@code UserDao#findProfileRow} 的注释里，
 * 而仓库纪律 {@code C-2} 要求"证据可复核"。放在被 gitignore 的临时目录里，别人 clone 后复算不到。
 *
 * <h2>怎么准备库（与本仓 {@code application.yaml} 同形的连接串）</h2>
 * <pre>
 * docker run -d --name tv-perf-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=perf \
 *   -p 13306:3306 mysql:8.4 --innodb-buffer-pool-size=256M
 * # 按序应用 src/main/resources/db/migration/V1..V12（mysql 客户端重定向即可）
 * # 再灌 10 万行 users（见仓库记忆 2026-11-08）；
 * # 关键是量级：users 表 ~13MB，能被 buffer pool 全装下 —— 与真实部署的"热表"一致。
 * </pre>
 *
 * <h2>怎么跑</h2>
 * <pre>
 * java -cp ~/.m2/repository/com/mysql/mysql-connector-j/9.6.0/mysql-connector-j-9.6.0.jar \
 *      tools/perf/UserProfileBench.java
 * # 可用 -Dperf.url= -Dperf.user= -Dperf.password= 覆盖连接
 * </pre>
 *
 * <h2>读数的口径（别把它当端到端延迟）</h2>
 * <ul>
 *   <li>单连接循环、id 在 1..100000 上散列（{@code i * 7919 % 100000}），避免永远命中同一页；</li>
 *   <li>预热 2000 次、采样 5000 次，报 mean / p50 / p95 / p99；</li>
 *   <li>R 是**一次往返的地板价**；A−T 除以 4 就是"每条语句的往返成本"，
 *       与服务端执行时间（{@code performance_schema} 里 60–76 µs）相减即得网络占比；</li>
 *   <li>⚠️ Docker Desktop 的端口转发会把单次往返抬到 300–450 µs。**比值（7→1）可迁移，
 *       微秒绝对值不可迁移** —— 写文档时别把 2917 µs 当成线上数字。</li>
 * </ul>
 */
public final class UserProfileBench {

    private static final String URL = System.getProperty("perf.url",
            "jdbc:mysql://127.0.0.1:13306/perf"
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("perf.user", "root");
    private static final String PWD = System.getProperty("perf.password", "root");

    // 与 UserMapper.xml 的 findByIdForProfile 逐字一致（只把 #{id} 换成 ?）
    private static final String SQL_PROFILE =
            "select id, username, avatar_ref, phone, follower_count, follow_count, role from users where id = ?";
    private static final String SQL_COIN = "select coin_deci from users where id = ?";
    private static final String SQL_SIGN = "select last_sign_date from users where id = ?";
    private static final String SQL_HIST = "select history_recording from users where id = ?";

    /** 最终形态：与 UserMapper.xml#findProfileRow 逐字一致。 */
    private static final String SQL_MERGED =
            "select id, username, avatar_ref, role, coin_deci, last_sign_date, history_recording"
                    + " from users where id = ?";

    /** 对照：合并查询**多带一列 phone**（8 列），用来证明"多读一列的服务端代价 ≈ 0"。 */
    private static final String SQL_MERGED_WIDE =
            "select id, username, phone, avatar_ref, role, coin_deci, last_sign_date, history_recording"
                    + " from users where id = ?";

    private static final int WARMUP = 2000;
    private static final int ITERS = 5000;
    private static final int ID_SPACE = 100_000;

    public static void main(String[] args) throws Exception {
        try (Connection con = DriverManager.getConnection(URL, USER, PWD)) {
            con.setAutoCommit(true);

            // 控制组：单次往返的地板价（一条不碰任何表的语句）
            run("R  1 次往返（select 1）", con, (c, id) -> {
                try (PreparedStatement ps = c.prepareStatement("select 1")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        rs.getInt(1);
                    }
                }
            });

            // 控制组：只有事务壳（begin + commit + 恢复 autocommit），没有查询
            run("T  事务壳（begin+commit+restore）", con, (c, id) -> {
                c.setAutoCommit(false);
                c.commit();
                c.setAutoCommit(true);
            });

            // 现状：一个事务里跑 4 条窄查询
            run("A  现状：事务 + 4 条窄查询", con, (c, id) -> {
                c.setAutoCommit(false);
                select4(c, id);
                c.commit();
                c.setAutoCommit(true);
            });

            // 候选：1 条合并查询（7 列），不开事务
            run("B  合并：1 条查询（无事务）", con, (c, id) -> {
                try (PreparedStatement ps = c.prepareStatement(SQL_MERGED)) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getLong(1);
                            rs.getString(2);
                            rs.getString(3);
                            rs.getInt(4);
                            rs.getInt(5);
                            rs.getDate(6);
                            rs.getBoolean(7);
                        }
                    }
                }
            });

            // 对照：同一条合并查询多带一列 phone（8 列）
            run("C  对照：合并 8 列（含 phone）", con, (c, id) -> {
                try (PreparedStatement ps = c.prepareStatement(SQL_MERGED_WIDE)) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getLong(1);
                            rs.getString(2);
                            rs.getString(3);
                            rs.getString(4);
                            rs.getInt(5);
                            rs.getInt(6);
                            rs.getDate(7);
                            rs.getBoolean(8);
                        }
                    }
                }
            });
        }
    }

    private static void select4(Connection c, long id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(SQL_PROFILE)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getLong(1);
                    rs.getString(2);
                    rs.getString(3);
                    rs.getString(4);
                    rs.getInt(5);
                    rs.getInt(6);
                    rs.getInt(7);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(SQL_COIN)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getInt(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(SQL_SIGN)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getDate(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(SQL_HIST)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getBoolean(1);
                }
            }
        }
    }

    @FunctionalInterface
    private interface Op {
        void run(Connection c, long id) throws Exception;
    }

    private static void run(String name, Connection con, Op op) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            op.run(con, idOf(i));
        }
        long[] samples = new long[ITERS];
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            op.run(con, idOf(i));
            samples[i] = (System.nanoTime() - t0) / 1000;   // µs
        }
        Arrays.sort(samples);
        double sum = 0;
        for (long s : samples) {
            sum += s;
        }
        System.out.printf("%-40s mean=%7.1fus  p50=%6d  p95=%6d  p99=%6d  max=%6d%n",
                name, sum / ITERS, samples[ITERS / 2], samples[(int) (ITERS * 0.95)],
                samples[(int) (ITERS * 0.99)], samples[ITERS - 1]);
    }

    /** 在 1..100000 上散开，避免永远命中同一行的同一页。 */
    private static long idOf(int i) {
        return 1L + (i * 7919L) % ID_SPACE;
    }
}
