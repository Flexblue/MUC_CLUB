package com.club.management.controller;

import com.club.management.common.Result;
import com.club.management.service.ExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * 导出管理控制器
 */
@RestController
@RequestMapping("/export")
public class ExportController {

    @Autowired
    private ExportService exportService;

    /**
     * 生成导出包
     */
    @PostMapping("/generate")
    public Result<Map<String, Object>> generateExport(@RequestBody Map<String, Object> params, 
                                                      @RequestAttribute("currentUser") Object currentUser) {
        return exportService.generateExport(params, currentUser);
    }

    /**
     * 获取导出历史
     */
    @GetMapping("/history")
    public Result<List<Map<String, Object>>> getExportHistory() {
        return exportService.getExportHistory();
    }

    /**
     * 下载导出文件
     */
    @GetMapping("/download/{id}")
    public void downloadExportFile(@PathVariable String id, HttpServletResponse response,
                                   @RequestAttribute("currentUser") Object currentUser) {
        exportService.downloadExportFile(id, response, currentUser);
    }

    /**
     * 下载活动详情
     */
    @GetMapping("/download-activity/{activityId}")
    public void downloadActivityDetail(@PathVariable Long activityId, HttpServletResponse response,
                                      @RequestAttribute("currentUser") Object currentUser) {
        exportService.downloadActivityDetail(activityId, response, currentUser);
    }

    /**
     * 删除导出文件
     */
    @DeleteMapping("/delete/{id}")
    public Result<String> deleteExportFile(@PathVariable String id) {
        return exportService.deleteExportFile(id);
    }
}
