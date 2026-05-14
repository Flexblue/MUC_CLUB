package com.club.management.controller;

import com.club.management.common.Result;
import com.club.management.entity.Club;
import com.club.management.service.ClubService;
import com.club.management.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 社团控制器
 *
 * @author Club Management System
 * @since 2025-01-15
 */
@RestController
@RequestMapping("/club")
public class ClubController {

    @Autowired
    private ClubService clubService;

    @Autowired
    private LogService logService;

    /**
     * 获取所有启用的社团列表
     * 注意：此接口无需认证，用于登录前的社团选择
     */
    @GetMapping("/list")
    public Result<List<Club>> getClubList() {
        return clubService.getActiveClubs();
    }

    /**
     * 根据ID获取社团信息
     */
    @GetMapping("/{id}")
    public Result<Club> getClubById(@PathVariable Long id) {
        return clubService.getClubById(id);
    }

    /**
     * 根据社团代码获取社团信息
     */
    @GetMapping("/code/{code}")
    public Result<Club> getClubByCode(@PathVariable String code) {
        return clubService.getClubByCode(code);
    }

    /**
     * 创建社团并初始化数据库
     * 注意：此接口需要超级管理员权限
     */
    @PostMapping("/create")
    public Result<Club> createClub(@RequestBody Club club, HttpServletRequest request) {
        Result<Club> result = clubService.createClub(club);

        // 记录操作日志
        if (result.getCode() == 200) {
            logService.logOperation(
                    "admin",
                    "创建社团",
                    "POST /club/create",
                    "社团名称: " + club.getName(),
                    request
            );
        }

        return result;
    }

    /**
     * 更新社团信息
     * 注意：此接口需要管理员权限
     */
    @PutMapping("/{id}")
    public Result<Club> updateClub(
            @PathVariable Long id,
            @RequestBody Club club,
            HttpServletRequest request
    ) {
        Result<Club> result = clubService.updateClub(id, club);

        // 记录操作日志
        if (result.getCode() == 200) {
            logService.logOperation(
                    "admin",
                    "更新社团信息",
                    "PUT /club/" + id,
                    "社团ID: " + id,
                    request
            );
        }

        return result;
    }

    /**
     * 启用/禁用社团
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateClubStatus(
            @PathVariable Long id,
            @RequestParam Integer status,
            HttpServletRequest request
    ) {
        Result<Void> result = clubService.updateClubStatus(id, status);

        // 记录操作日志
        if (result.getCode() == 200) {
            logService.logOperation(
                    "admin",
                    status == 1 ? "启用社团" : "禁用社团",
                    "PUT /club/" + id + "/status",
                    "社团ID: " + id + ", 状态: " + status,
                    request
            );
        }

        return result;
    }
}
