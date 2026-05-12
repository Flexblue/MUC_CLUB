package com.club.management.config;

import com.club.management.entity.Club;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态数据源 - 按需创建并缓存每个社团的连接池。
 *
 * 与旧实现的区别：
 *   旧：启动时把所有社团数据源放入 AbstractRoutingDataSource 的静态 Map，
 *       新增社团必须重启。
 *   新：维护自己的 ConcurrentHashMap，重写 determineTargetDataSource()；
 *       首次请求某个 clubId 时从主库查社团信息、按需建连接池并缓存，
 *       无需重启即可路由到新社团数据库。
 */
public class DynamicDataSource extends AbstractRoutingDataSource {

    private static final Logger logger = LoggerFactory.getLogger(DynamicDataSource.class);

    /** 社团连接池缓存；key = clubId */
    private final ConcurrentHashMap<Long, DataSource> clubDataSources = new ConcurrentHashMap<>();

    /** 主库数据源（单独持有，不走路由） */
    private DataSource masterDataSource;

    // 社团库连接配置（与 application.yml spring.datasource.club.* 对应）
    private String clubUrlPrefix;
    private String clubUrlSuffix;
    private String clubUsername;
    private String clubPassword;

    // ── 路由核心 ────────────────────────────────────────────────────────────────

    /**
     * 父类 afterPropertiesSet() 需要此方法，实际路由由 determineTargetDataSource() 完成。
     */
    @Override
    protected Object determineCurrentLookupKey() {
        return ClubContext.getClubId();
    }

    /**
     * 完全接管数据源解析逻辑：
     *   - clubId == null → 主库
     *   - clubId 已缓存  → 直接返回缓存连接池
     *   - clubId 未缓存  → 去主库查社团信息，创建连接池并写入缓存（按需加载）
     */
    @Override
    protected DataSource determineTargetDataSource() {
        Long clubId = ClubContext.getClubId();
        if (clubId == null) {
            return masterDataSource;
        }

        // computeIfAbsent 是原子操作，并发安全，同一个 clubId 只会建一次连接池
        return clubDataSources.computeIfAbsent(clubId, id -> {
            logger.info("Club datasource cache miss for clubId={}, loading on demand...", id);
            Club club = queryClubFromMaster(id);
            if (club == null) {
                throw new IllegalStateException(
                        "Club not found or disabled in master database: clubId=" + id);
            }
            DataSource ds = buildClubDataSource(club);
            logger.info("On-demand datasource created for club '{}' (id={})", club.getName(), id);
            return ds;
        });
    }

    // ── 公开管理接口（供 ClubService 调用）────────────────────────────────────

    /**
     * 主动注册新社团数据源（createClub 成功后立即调用，避免第一次登录触发按需加载）。
     */
    public void registerClubDataSource(Club club) {
        DataSource ds = buildClubDataSource(club);
        clubDataSources.put(club.getId(), ds);
        logger.info("Registered datasource for new club '{}' (id={})", club.getName(), club.getId());
    }

    /**
     * 移除并关闭某社团的连接池（禁用社团时调用，释放数据库连接）。
     */
    public void evictClubDataSource(Long clubId) {
        DataSource ds = clubDataSources.remove(clubId);
        if (ds instanceof HikariDataSource) {
            ((HikariDataSource) ds).close();
            logger.info("Closed and evicted datasource for clubId={}", clubId);
        }
    }

    // ── 私有辅助方法 ─────────────────────────────────────────────────────────

    /** 直接走主库 JDBC，不经过路由，避免 ClubContext 干扰。 */
    private Club queryClubFromMaster(Long clubId) {
        String sql = "SELECT id, name, code, db_name, status FROM clubs WHERE id = ? AND status = 1";
        try (Connection conn = masterDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, clubId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Club club = new Club();
                    club.setId(rs.getLong("id"));
                    club.setName(rs.getString("name"));
                    club.setCode(rs.getString("code"));
                    club.setDbName(rs.getString("db_name"));
                    club.setStatus(rs.getInt("status"));
                    return club;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query club from master, clubId={}", clubId, e);
        }
        return null;
    }

    private DataSource buildClubDataSource(Club club) {
        String url = clubUrlPrefix + club.getDbName() + clubUrlSuffix;
        logger.info("Building HikariCP pool for club '{}': {}", club.getName(), url);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(clubUsername);
        config.setPassword(clubPassword);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMinimumIdle(3);
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setPoolName("ClubPool-" + club.getId());
        return new HikariDataSource(config);
    }

    // ── Setters（由 DynamicDataSourceConfig 在 Bean 初始化时注入）─────────────

    public void setMasterDataSource(DataSource masterDataSource) {
        this.masterDataSource = masterDataSource;
    }

    public void setClubUrlPrefix(String clubUrlPrefix) {
        this.clubUrlPrefix = clubUrlPrefix;
    }

    public void setClubUrlSuffix(String clubUrlSuffix) {
        this.clubUrlSuffix = clubUrlSuffix;
    }

    public void setClubUsername(String clubUsername) {
        this.clubUsername = clubUsername;
    }

    public void setClubPassword(String clubPassword) {
        this.clubPassword = clubPassword;
    }
}
