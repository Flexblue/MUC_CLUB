package com.club.management.service;

import com.club.management.entity.SysLog;
import com.club.management.mapper.SysLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

/**
 * 系统日志服务
 */
@Service
public class LogService {

    @Autowired
    private SysLogMapper sysLogMapper;

    /**
     * 记录操作日志
     */
    public void logOperation(String operator, String operation, String method, String params, String ip) {
        try {
            SysLog log = new SysLog();
            log.setOperator(operator);
            log.setOperation(operation);
            log.setMethod(method);
            log.setParams(params);
            log.setIp(ip);
            log.setCreateTime(LocalDateTime.now());
            
            sysLogMapper.insert(log);
        } catch (Exception e) {
            // 日志记录失败不应该影响主业务
            System.err.println("记录操作日志失败: " + e.getMessage());
        }
    }

    /**
     * 记录操作日志（从HttpServletRequest获取IP）
     */
    public void logOperation(String operator, String operation, String method, String params, HttpServletRequest request) {
        String ip = getClientIpAddress(request);
        logOperation(operator, operation, method, params, ip);
    }

    /**
     * 记录简单操作日志
     */
    public void logOperation(String operator, String operation) {
        logOperation(operator, operation, "", "", "");
    }

    /**
     * 获取客户端IP地址
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0];
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}
