package com.club.management.config;

import com.club.management.common.JwtUtil;
import com.club.management.common.Result;
import com.club.management.entity.SysUser;
import com.club.management.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;

/**
 * JWT认证过滤器
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    @Lazy
    private AuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {

        // 跨域预检请求直接放行
        if ("OPTIONS".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 登录前/公共接口直接放行
        String requestURI = request.getRequestURI();
        boolean isAuthApi = requestURI.startsWith("/api/auth/") || requestURI.startsWith("/auth/");
        boolean isPublicClubApi = requestURI.startsWith("/api/club/") || requestURI.startsWith("/club/");
        boolean isStaticUpload = requestURI.startsWith("/api/uploads/") || requestURI.startsWith("/uploads/");
        boolean isMemberTemplate = requestURI.equals("/api/member/template") || requestURI.equals("/member/template");
        if (isAuthApi || isPublicClubApi || isStaticUpload || isMemberTemplate) {
            filterChain.doFilter(request, response);
            return;
        }

        // 获取token
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            writeErrorResponse(response, "未提供认证token");
            return;
        }

        token = token.substring(7);

        // 从token中提取clubId并设置到上下文（修复多数据源问题）
        try {
            Long clubId = jwtUtil.getClubIdFromToken(token);
            if (clubId != null) {
                ClubContext.setClubId(clubId);
            }
        } catch (Exception e) {
            // 如果提取失败，继续执行，让validateToken处理
        }

        // 验证token
        Result<Object> result = authService.validateToken(token);
        if (result.getCode() != 200) {
            ClubContext.clear(); // 验证失败时清除上下文
            writeErrorResponse(response, result.getMessage());
            return;
        }

        // 设置Spring Security认证上下文
        Object userObj = result.getData();
        String username;
        String role;
        
        if (userObj instanceof SysUser) {
            SysUser user = (SysUser) userObj;
            username = user.getName();
            role = user.getRole();
        } else if (userObj instanceof com.club.management.entity.Member) {
            com.club.management.entity.Member member = (com.club.management.entity.Member) userObj;
            username = member.getName();
            role = member.getRole();
        } else {
            writeErrorResponse(response, "用户类型错误");
            return;
        }
        
        UserDetails userDetails = User.builder()
                .username(username)
                .password("")
                .authorities("ROLE_" + role)
                .build();
        
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 将用户信息存储到request中
        request.setAttribute("currentUser", userObj);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 清除ClubContext，防止线程池复用时的数据污染
            ClubContext.clear();
        }
    }

    private void writeErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        
        Result<?> result = Result.businessError(401, message);
        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(result));
    }
}

