package com.club.management.controller;

import com.club.management.common.Result;
import com.club.management.service.AuthService;
import com.club.management.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private LogService logService;

    /**
     * 用户登录（支持多社团）
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, Object> loginData, HttpServletRequest request) {
        String stuId = (String) loginData.get("stuId");
        String password = (String) loginData.get("password");

        // 从请求中获取clubId
        Long clubId = null;
        if (loginData.containsKey("clubId")) {
            Object clubIdObj = loginData.get("clubId");
            if (clubIdObj instanceof Integer) {
                clubId = ((Integer) clubIdObj).longValue();
            } else if (clubIdObj instanceof Long) {
                clubId = (Long) clubIdObj;
            } else if (clubIdObj instanceof String) {
                try {
                    clubId = Long.parseLong((String) clubIdObj);
                } catch (NumberFormatException e) {
                    // 使用默认值
                    clubId = 6L;
                }
            }
        }

        // 如果没有提供clubId，使用默认值（数智实创社）
        if (clubId == null) {
            clubId = 6L;
        }

        Result<Map<String, Object>> result = authService.login(stuId, password, clubId);

        // 记录登录日志
        if (result.getCode() == 200) {
            logService.logOperation(stuId, "用户登录", "POST /auth/login",
                "学号: " + stuId + ", 社团ID: " + clubId, request);
        } else {
            logService.logOperation(stuId, "登录失败", "POST /auth/login",
                "学号: " + stuId + ", 社团ID: " + clubId, request);
        }

        return result;
    }

    /**
     * 验证token
     */
    @GetMapping("/validate")
    public Result<?> validateToken(@RequestHeader("Authorization") String token) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return authService.validateToken(token);
    }

    /** 开发用途：直发 token（仅用于本地联调） */
    @GetMapping("/dev/token")
    public Result<java.util.Map<String, Object>> devToken(@RequestParam String stuId) {
        return authService.issueTokenForStuId(stuId);
    }
}