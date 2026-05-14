package com.club.management.config;

import com.club.management.common.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 数据源拦截器
 * 用于从JWT token中提取clubId并设置到ClubContext，实现数据源自动切换
 *
 * @author Club Management System
 * @since 2025-01-15
 */
@Component
public class DataSourceInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceInterceptor.class);

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 请求处理前执行
     * 从JWT token中提取clubId并设置到ClubContext
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestURI = request.getRequestURI();

        // club接口始终走master库（存放clubs表）
        if (requestURI.startsWith("/api/club")) {
            ClubContext.setClubId(null);
            logger.debug("club接口使用master数据源: URI={}", requestURI);
            return true;
        }

        // 获取token
        String token = extractToken(request);

        if (token != null && !token.isEmpty()) {
            try {
                // 从token中提取clubId
                Long clubId = jwtUtil.getClubIdFromToken(token);

                if (clubId != null) {
                    // 设置到上下文
                    ClubContext.setClubId(clubId);
                    logger.debug("设置数据源上下文: clubId={}, URI={}", clubId, request.getRequestURI());
                } else {
                    // 如果token中没有clubId，使用默认值
                    logger.warn("Token中未包含clubId，使用默认值: URI={}", request.getRequestURI());
                    ClubContext.setClubId(6L);  // 默认数智实创社
                }
            } catch (Exception e) {
                logger.error("解析token失败: {}", e.getMessage());
                ClubContext.setClubId(6L);  // 出错时使用默认值
            }
        } else {
            // 没有token的请求（如登录、社团列表），使用默认数据源或不设置
            logger.debug("请求无token: URI={}", requestURI);
            // 对于某些无需认证的接口，不设置clubId
        }

        return true;
    }

    /**
     * 请求完成后执行
     * 清除ClubContext，防止内存泄漏
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        // 清除上下文
        ClubContext.clear();
        logger.debug("清除数据源上下文: URI={}", request.getRequestURI());
    }

    /**
     * 从请求中提取JWT token
     */
    private String extractToken(HttpServletRequest request) {
        // 从Authorization header中提取
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        // 从请求参数中提取（备用）
        String tokenParam = request.getParameter("token");
        if (tokenParam != null && !tokenParam.isEmpty()) {
            return tokenParam;
        }

        return null;
    }
}
