package com.club.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.club.management.common.Result;
import com.club.management.entity.Member;
import com.club.management.service.MemberService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * 社员管理控制器
 */
@RestController
@RequestMapping("/member")
public class MemberController {

    @Autowired
    private MemberService memberService;

    /**
     * 分页查询社员
     */
    @GetMapping("/page")
    public Result<Page<Member>> getMemberPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String stuId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder,
            @RequestAttribute("currentUser") Object currentUser) {
        return memberService.getMemberPage(page, size, name, stuId, deptId, role, sortField, sortOrder, currentUser);
    }

    /**
     * 添加社员
     */
    @PostMapping("/add")
    public Result<String> addMember(@RequestBody Member member, @RequestAttribute("currentUser") Object currentUser) {
        return memberService.addMember(member, currentUser);
    }

    /**
     * 更新社员
     */
    @PutMapping("/update")
    public Result<String> updateMember(@RequestBody Member member, @RequestAttribute("currentUser") Object currentUser) {
        return memberService.updateMember(member, currentUser);
    }

    /**
     * 删除社员
     */
    @DeleteMapping("/delete/{id}")
    public Result<String> deleteMember(@PathVariable Long id, @RequestAttribute("currentUser") Object currentUser) {
        return memberService.deleteMember(id, currentUser);
    }

    /**
     * 获取社员详情
     */
    @GetMapping("/detail/{id}")
    public Result<Member> getMemberById(@PathVariable Long id) {
        return memberService.getMemberById(id);
    }

    /**
     * 获取社员参与的活动
     */
    @GetMapping("/activities/{id}")
    public Result<List<Map<String, Object>>> getMemberActivities(@PathVariable Long id) {
        return memberService.getMemberActivities(id);
    }

    /**
     * 下载导入模板
     */
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) {
        memberService.downloadTemplate(response);
    }

    /**
     * 预览导入数据
     */
    @PostMapping("/import/preview")
    public Result<List<Map<String, Object>>> previewImport(@RequestParam("file") MultipartFile file) {
        return memberService.previewImport(file);
    }

    /**
     * 确认导入数据
     */
    @PostMapping("/import/confirm")
    public Result<String> confirmImport(@RequestBody List<Map<String, Object>> importData) {
        return memberService.confirmImport(importData);
    }

    /**
     * 重置密码（管理员功能）
     */
    @PutMapping("/reset-password/{id}")
    public Result<String> resetPassword(@PathVariable Long id, @RequestAttribute("currentUser") Object currentUser) {
        return memberService.resetPassword(id, currentUser);
    }

}