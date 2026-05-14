package com.club.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.club.management.common.Result;
import com.club.management.entity.Activity;
import com.club.management.service.ActivityService;
import com.club.management.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 活动管理控制器
 */
@RestController
@RequestMapping("/activity")
public class ActivityController {

    @Autowired
    private ActivityService activityService;

    @Autowired
    private LogService logService;

    /**
     * 获取当前用户学号
     */
    private String getCurrentUserStuId(Object currentUser) {
        if (currentUser instanceof com.club.management.entity.Member) {
            return ((com.club.management.entity.Member) currentUser).getStuId();
        } else if (currentUser instanceof com.club.management.entity.SysUser) {
            return ((com.club.management.entity.SysUser) currentUser).getStuId();
        }
        return "unknown";
    }

    /**
     * 分页查询活动
     */
    @GetMapping("/page")
    public Result<Page<Activity>> getActivityPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long createBy,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder) {
        
        LocalDateTime start = null;
        LocalDateTime end = null;
        
        if (startTime != null && !startTime.isEmpty()) {
            start = LocalDateTime.parse(startTime);
        }
        if (endTime != null && !endTime.isEmpty()) {
            end = LocalDateTime.parse(endTime);
        }
        
        return activityService.getActivityPage(page, size, name, type, deptId, status, createBy, start, end, sortField, sortOrder);
    }

    /**
     * 添加活动
     */
    @PostMapping("/add")
    public Result<Long> addActivity(@RequestBody Activity activity, @RequestAttribute("currentUser") Object currentUser, HttpServletRequest request) {
        Result<Long> result = activityService.addActivity(activity, currentUser);
        
        // 记录操作日志
        String operator = getCurrentUserStuId(currentUser);
        if (result.getCode() == 200) {
            logService.logOperation(operator, "创建活动", "POST /activity/add", "活动名称: " + activity.getName(), request);
        } else {
            logService.logOperation(operator, "创建活动失败", "POST /activity/add", "活动名称: " + activity.getName(), request);
        }
        
        return result;
    }

    /**
     * 更新活动
     */
    @PutMapping("/update")
    public Result<String> updateActivity(@RequestBody Activity activity, @RequestAttribute("currentUser") Object currentUser) {
        return activityService.updateActivity(activity, currentUser);
    }

    /**
     * 删除活动
     */
    @DeleteMapping("/delete/{id}")
    public Result<String> deleteActivity(@PathVariable Long id, @RequestAttribute("currentUser") Object currentUser) {
        return activityService.deleteActivity(id, currentUser);
    }

    /**
     * 审批活动
     */
    @PutMapping("/approve/{id}")
    public Result<String> approveActivity(@PathVariable Long id, 
                                        @RequestParam Integer status,
                                        @RequestParam(required = false) String rejectReason) {
        return activityService.approveActivity(id, status, rejectReason);
    }

    /**
     * 设置审批人与责任部门
     */
    @PostMapping("/setup/{id}")
    public Result<String> setupApproversAndDepts(@PathVariable Long id,
                                                 @RequestBody SetupPayload payload,
                                                 @RequestAttribute("currentUser") Object currentUser) {
        return activityService.setApproversAndDepts(id, payload.getApproverUserIds(), payload.getDeptIds(), currentUser);
    }

    /**
     * 当前用户审批（通过/驳回）
     */
    @PutMapping("/approve/{id}/self")
    public Result<String> approveSelf(@PathVariable Long id,
                                      @RequestParam boolean pass,
                                      @RequestParam(required = false) String rejectReason,
                                      @RequestAttribute("currentUser") Object currentUser) {
        Long userId = null;
        if (currentUser instanceof com.club.management.entity.Member) {
            userId = ((com.club.management.entity.Member) currentUser).getId();
        } else if (currentUser instanceof com.club.management.entity.SysUser) {
            userId = ((com.club.management.entity.SysUser) currentUser).getId();
        }
        
        if (userId == null) {
            return Result.businessError(com.club.management.common.ErrorCode.UNAUTHORIZED, "用户信息无效");
        }
        
        return activityService.approveByUser(id, userId, pass, rejectReason);
    }

    /** 承载 setup 请求体 */
    public static class SetupPayload {
        private java.util.List<Long> approverUserIds;
        private java.util.List<Long> deptIds;
        public java.util.List<Long> getApproverUserIds() { return approverUserIds; }
        public void setApproverUserIds(java.util.List<Long> ids) { this.approverUserIds = ids; }
        public java.util.List<Long> getDeptIds() { return deptIds; }
        public void setDeptIds(java.util.List<Long> ids) { this.deptIds = ids; }
    }

    /**
     * 获取活动详情
     */
    @GetMapping("/detail/{id}")
    public Result<Activity> getActivityById(@PathVariable Long id) {
        return activityService.getActivityById(id);
    }

    /**
     * 获取活动完整详情
     */
    @GetMapping("/full-detail/{id}")
    public Result<Map<String, Object>> getActivityFullDetail(@PathVariable Long id) {
        return activityService.getActivityFullDetail(id);
    }

    /**
     * 获取活动参与成员
     */
    @GetMapping("/members/{activityId}")
    public Result<List<Map<String, Object>>> getActivityMembers(@PathVariable Long activityId) {
        return activityService.getActivityMembers(activityId);
    }

    /**
     * 更新活动参与成员
     */
    @PutMapping("/members/{activityId}")
    public Result<String> updateActivityMembers(@PathVariable Long activityId, 
                                              @RequestBody List<Long> memberIds,
                                              @RequestAttribute("currentUser") Object currentUser) {
        return activityService.updateActivityMembers(activityId, memberIds, currentUser);
    }

    /**
     * 获取活动的审批人信息
     */
    @GetMapping("/approvers/{activityId}")
    public Result<List<Map<String, Object>>> getActivityApprovers(@PathVariable Long activityId) {
        return activityService.getActivityApprovers(activityId);
    }

    /**
     * 获取活动的负责部门信息
     */
    @GetMapping("/depts/{activityId}")
    public Result<List<Map<String, Object>>> getActivityDepts(@PathVariable Long activityId) {
        return activityService.getActivityDepts(activityId);
    }
    
    /**
     * 获取活动审批状态详情
     */
    @GetMapping("/approval-status/{activityId}")
    public Result<Map<String, Object>> getActivityApprovalStatus(@PathVariable Long activityId) {
        return activityService.getActivityApprovalStatus(activityId);
    }
}