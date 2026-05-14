package com.club.management.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * 生成JWT token (包含clubId)
     */
    public String generateToken(Long userId, String username, String role, Long clubId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);
        claims.put("clubId", clubId);  // 新增clubId
        return createToken(claims, username);
    }

    /**
     * 生成JWT token (兼容旧版本，使用默认clubId=6)
     * @deprecated 请使用包含clubId参数的方法
     */
    @Deprecated
    public String generateToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, 6L);  // 默认使用数智实创社
    }

    /**
     * 创建token
     */
    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes());

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 从token中获取用户ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims != null && claims.get("userId") != null) {
            try {
                Object userIdObj = claims.get("userId");
                if (userIdObj instanceof Integer) {
                    return ((Integer) userIdObj).longValue();
                } else if (userIdObj instanceof Long) {
                    return (Long) userIdObj;
                } else {
                    return Long.valueOf(userIdObj.toString());
                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }

    /**
     * 从token中获取用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * 从token中获取角色
     */
    public String getRoleFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        return claims != null ? claims.get("role").toString() : null;
    }

    /**
     * 从token中获取社团ID
     */
    public Long getClubIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims != null && claims.get("clubId") != null) {
            try {
                Object clubIdObj = claims.get("clubId");
                if (clubIdObj instanceof Integer) {
                    return ((Integer) clubIdObj).longValue();
                } else if (clubIdObj instanceof Long) {
                    return (Long) clubIdObj;
                } else {
                    return Long.valueOf(clubIdObj.toString());
                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }

    /**
     * 从token中获取Claims
     */
    private Claims getClaimsFromToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes());
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            e.printStackTrace(); // 添加调试信息
            return null;
        }
    }

    /**
     * 验证token是否有效
     */
    public Boolean validateToken(String token, String username) {
        String tokenUsername = getUsernameFromToken(token);
        return (username.equals(tokenUsername) && !isTokenExpired(token));
    }

    /**
     * 检查token是否过期
     */
    private Boolean isTokenExpired(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            Date expiration = claims.getExpiration();
            return expiration.before(new Date());
        } catch (Exception e) {
            return true;
        }
    }
}

