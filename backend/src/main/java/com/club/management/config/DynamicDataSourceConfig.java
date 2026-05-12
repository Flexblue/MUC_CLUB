package com.club.management.config;

import com.club.management.entity.Club;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态数据源配置。
 *
 * 职责：
 *   1. 构建主库 HikariCP 连接池
 *   2. 构建 DynamicDataSource Bean，注入主库和社团库连接参数
 *   3. 启动时预加载已有社团的连接池（提升首次请求响应速度）
 *
 * 新增社团无需重启：DynamicDataSource 内部按需建池，详见该类注释。
 */
@Configuration
public class DynamicDataSourceConfig {

    private static final Logger logger = LoggerFactory.getLogger(DynamicDataSourceConfig.class);

    @Value("${spring.datasource.master.url}")
    private String masterUrl;

    @Value("${spring.datasource.master.username}")
    private String masterUsername;

    @Value("${spring.datasource.master.password}")
    private String masterPassword;

    @Value("${spring.datasource.club.url-prefix}")
    private String clubUrlPrefix;

    @Value("${spring.datasource.club.url-suffix}")
    private String clubUrlSuffix;

    @Value("${spring.datasource.club.username}")
    private String clubUsername;

    @Value("${spring.datasource.club.password}")
    private String clubPassword;

    /**
     * 构建主库连接池（访问 clubs 元数据表）。
     */
    private DataSource buildMasterDataSource() {
        logger.info("Building master datasource: {}", masterUrl);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(masterUrl);
        config.setUsername(masterUsername);
        config.setPassword(masterPassword);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMinimumIdle(5);
        config.setMaximumPoolSize(20);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setInitializationFailTimeout(-1);
        config.setPoolName("MasterPool");
        return new HikariDataSource(config);
    }

    /**
     * 从主库读取所有启用的社团（仅用于启动预热）。
     */
    private List<Club> loadActiveClubs(DataSource masterDs) {
        List<Club> clubs = new ArrayList<>();
        try (Connection conn = masterDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, code, db_name, status FROM clubs WHERE status = 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Club club = new Club();
                club.setId(rs.getLong("id"));
                club.setName(rs.getString("name"));
                club.setCode(rs.getString("code"));
                club.setDbName(rs.getString("db_name"));
                club.setStatus(rs.getInt("status"));
                clubs.add(club);
            }
            logger.info("Loaded {} active clubs for datasource pre-warming", clubs.size());
        } catch (Exception e) {
            logger.error("Failed to load clubs from master during startup", e);
        }
        return clubs;
    }

    /**
     * 主 DataSource Bean。
     *
     * 返回类型为 DynamicDataSource（而非 DataSource）使 ClubService 可以直接
     * Autowire DynamicDataSource 并调用 registerClubDataSource / evictClubDataSource。
     */
    @Bean
    @Primary
    public DynamicDataSource dynamicDataSource() {
        logger.info("Initializing DynamicDataSource...");

        DataSource masterDs = buildMasterDataSource();

        DynamicDataSource dynamicDataSource = new DynamicDataSource();

        // 注入主库和社团库连接参数
        dynamicDataSource.setMasterDataSource(masterDs);
        dynamicDataSource.setClubUrlPrefix(clubUrlPrefix);
        dynamicDataSource.setClubUrlSuffix(clubUrlSuffix);
        dynamicDataSource.setClubUsername(clubUsername);
        dynamicDataSource.setClubPassword(clubPassword);

        // AbstractRoutingDataSource.afterPropertiesSet() 要求 targetDataSources 非空，
        // 传入 master 占位即可（实际路由在 determineTargetDataSource() 中完成）。
        dynamicDataSource.setTargetDataSources(Map.of("master", masterDs));
        dynamicDataSource.setDefaultTargetDataSource(masterDs);

        // 预热：启动时把已有社团的连接池注册进来（减少首次登录延迟）
        List<Club> clubs = loadActiveClubs(masterDs);
        for (Club club : clubs) {
            try {
                dynamicDataSource.registerClubDataSource(club);
            } catch (Exception e) {
                logger.warn("Failed to pre-warm datasource for club '{}' (id={}): {}",
                        club.getName(), club.getId(), e.getMessage());
            }
        }

        logger.info("DynamicDataSource initialized with {} pre-warmed club pools", clubs.size());
        return dynamicDataSource;
    }
}
