package com.club.management.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.club.management.common.ErrorCode;
import com.club.management.common.Result;
import com.club.management.config.DynamicDataSource;
import com.club.management.entity.Club;
import com.club.management.mapper.ClubMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 社团服务类
 *
 * @author Club Management System
 * @since 2025-01-15
 */
@Service
public class ClubService {

    private static final Logger logger = LoggerFactory.getLogger(ClubService.class);

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private DynamicDataSource dynamicDataSource;

    /**
     * 获取所有启用的社团列表
     */
    public Result<List<Club>> getActiveClubs() {
        try {
            List<Club> clubs = clubMapper.selectActiveClubs();
            logger.info("获取社团列表成功，共 {} 个社团", clubs.size());
            return Result.success(clubs);
        } catch (Exception e) {
            logger.error("获取社团列表失败", e);
            return Result.error(ErrorCode.SYSTEM_ERROR, "获取社团列表失败");
        }
    }

    /**
     * 根据ID获取社团信息
     */
    public Result<Club> getClubById(Long id) {
        try {
            if (id == null) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "社团ID不能为空");
            }

            Club club = clubMapper.selectById(id);
            if (club == null) {
                return Result.businessError(ErrorCode.NOT_FOUND_ERROR, "社团不存在");
            }

            logger.info("获取社团信息成功: {}", club.getName());
            return Result.success(club);
        } catch (Exception e) {
            logger.error("获取社团信息失败: id={}", id, e);
            return Result.error(ErrorCode.SYSTEM_ERROR, "获取社团信息失败");
        }
    }

    /**
     * 根据社团代码获取社团信息
     */
    public Result<Club> getClubByCode(String code) {
        try {
            if (code == null || code.isEmpty()) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "社团代码不能为空");
            }

            Club club = clubMapper.selectByCode(code);
            if (club == null) {
                return Result.businessError(ErrorCode.NOT_FOUND_ERROR, "社团不存在");
            }

            return Result.success(club);
        } catch (Exception e) {
            logger.error("获取社团信息失败: code={}", code, e);
            return Result.error(ErrorCode.SYSTEM_ERROR, "获取社团信息失败");
        }
    }

    /**
     * 创建社团
     * 注意：此方法需要管理员权限
     */
    public Result<Club> createClub(Club club) {
        try {
            // 参数验证
            if (club.getName() == null || club.getName().isEmpty()) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "社团名称不能为空");
            }
            if (club.getCode() == null || club.getCode().isEmpty()) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "社团代码不能为空");
            }
            if (club.getDbName() == null || club.getDbName().isEmpty()) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "数据库名称不能为空");
            }

            // 检查社团代码是否已存在
            QueryWrapper<Club> wrapper = new QueryWrapper<>();
            wrapper.eq("code", club.getCode());
            Club existingClub = clubMapper.selectOne(wrapper);
            if (existingClub != null) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "社团代码已存在");
            }

            // 设置默认值
            if (club.getStatus() == null) {
                club.setStatus(1);  // 默认启用
            }
            if (club.getThemeColor() == null) {
                club.setThemeColor("#4167b1");  // 默认主题色
            }

            // 插入数据库
            int result = clubMapper.insert(club);
            if (result > 0) {
                logger.info("创建社团成功: {}", club.getName());
                // 立即注册数据源，无需重启即可路由到新社团数据库
                try {
                    dynamicDataSource.registerClubDataSource(club);
                } catch (Exception e) {
                    // 数据源注册失败不影响社团记录已写入，首次请求时会按需重试
                    logger.warn("社团记录已创建，但数据源预注册失败（首次请求时将自动重试）: {}", e.getMessage());
                }
                return Result.success(club);
            } else {
                logger.error("创建社团失败: 数据库插入失败");
                return Result.error(ErrorCode.SYSTEM_ERROR, "创建社团失败");
            }
        } catch (Exception e) {
            logger.error("创建社团失败", e);
            return Result.error(ErrorCode.SYSTEM_ERROR, "创建社团失败: " + e.getMessage());
        }
    }

    /**
     * 更新社团信息
     * 注意：此方法需要管理员权限
     */
    public Result<Club> updateClub(Long id, Club club) {
        try {
            if (id == null) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "社团ID不能为空");
            }

            // 检查社团是否存在
            Club existingClub = clubMapper.selectById(id);
            if (existingClub == null) {
                return Result.businessError(ErrorCode.NOT_FOUND_ERROR, "社团不存在");
            }

            // 更新信息
            club.setId(id);
            int result = clubMapper.updateById(club);
            if (result > 0) {
                logger.info("更新社团信息成功: {}", club.getName());
                return Result.success(clubMapper.selectById(id));
            } else {
                logger.error("更新社团信息失败: 数据库更新失败");
                return Result.error(ErrorCode.SYSTEM_ERROR, "更新社团信息失败");
            }
        } catch (Exception e) {
            logger.error("更新社团信息失败: id={}", id, e);
            return Result.error(ErrorCode.SYSTEM_ERROR, "更新社团信息失败: " + e.getMessage());
        }
    }

    /**
     * 启用/禁用社团
     */
    public Result<Void> updateClubStatus(Long id, Integer status) {
        try {
            if (id == null) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "社团ID不能为空");
            }
            if (status == null || (status != 0 && status != 1)) {
                return Result.businessError(ErrorCode.PARAMS_ERROR, "状态值无效");
            }

            Club club = clubMapper.selectById(id);
            if (club == null) {
                return Result.businessError(ErrorCode.NOT_FOUND_ERROR, "社团不存在");
            }

            club.setStatus(status);
            int result = clubMapper.updateById(club);
            if (result > 0) {
                logger.info("更新社团状态成功: {} - {}", club.getName(), status == 1 ? "启用" : "禁用");
                if (status == 0) {
                    // 禁用：关闭连接池，释放资源
                    dynamicDataSource.evictClubDataSource(id);
                } else {
                    // 重新启用：立即重建连接池
                    try {
                        dynamicDataSource.registerClubDataSource(club);
                    } catch (Exception e) {
                        logger.warn("社团重新启用，数据源预注册失败（首次请求时将自动重试）: {}", e.getMessage());
                    }
                }
                return Result.success(null);
            } else {
                return Result.error(ErrorCode.SYSTEM_ERROR, "更新社团状态失败");
            }
        } catch (Exception e) {
            logger.error("更新社团状态失败: id={}, status={}", id, status, e);
            return Result.error(ErrorCode.SYSTEM_ERROR, "更新社团状态失败");
        }
    }
}
