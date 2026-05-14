package com.club.management.service;

import com.club.management.common.Result;
import com.club.management.mapper.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

// iText PDF imports
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;

/**
 * 导出服务
 */
@Service
public class ExportService {

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private DeptMapper deptMapper;

    @Autowired
    private ActivityMapper activityMapper;

    @Autowired
    private ActivityMemberMapper activityMemberMapper;


    /**
     * 生成导出包
     */
    public Result<Map<String, Object>> generateExport(Map<String, Object> params, Object currentUser) {
        // 权限检查：只有有权限的角色才能导出数据
        if (!hasExportPermission(currentUser)) {
            return Result.businessError(400, "权限不足，无法导出数据");
        }
        try {
            List<String> types = (List<String>) params.get("types");
            String format = (String) params.get("format");
            Boolean includeFiles = (Boolean) params.get("includeFiles");
            String startDate = (String) params.get("startDate");
            String endDate = (String) params.get("endDate");
            List<Integer> deptIds = (List<Integer>) params.get("deptIds"); // 部门筛选
            
            // 验证参数
            if (types == null || types.isEmpty()) {
                return Result.businessError(400, "请选择导出类型");
            }
            
            // 验证多选类型只能选择ZIP格式
            if (types.size() > 1 && !"zip".equals(format)) {
                return Result.businessError(400, "多选类型只能导出ZIP格式");
            }

            // 生成唯一ID
            String exportId = "export_" + System.currentTimeMillis();
            
            // 根据格式生成文件
            String fileName;
            if ("excel".equals(format)) {
                fileName = generateExcelExport(exportId, types, startDate, endDate, deptIds, currentUser);
            } else {
                fileName = generateZipExport(exportId, types, startDate, endDate, includeFiles, deptIds, currentUser);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("id", exportId);
            result.put("fileName", fileName);
            result.put("format", format);
            result.put("fileSize", "1.2MB"); // 模拟文件大小
            result.put("status", "completed");
            result.put("createTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            result.put("types", types); // 存储用户选择的类型
            result.put("startDate", startDate); // 存储开始日期
            result.put("endDate", endDate); // 存储结束日期
            result.put("deptIds", deptIds); // 存储部门筛选

            // 存储到导出历史中
            exportHistory.put(exportId, new HashMap<>(result));

            return Result.success(result);
        } catch (Exception e) {
            return Result.businessError(500, "导出失败: " + e.getMessage());
        }
    }

    /**
     * 生成Excel导出
     */
    private String generateExcelExport(String exportId, List<String> types, String startDate, String endDate, List<Integer> deptIds, Object currentUser) {
        try {
            Workbook workbook = new XSSFWorkbook();
            
            // 根据选择的类型生成相应的Sheet
            for (String type : types) {
                if ("dept".equals(type)) {
                    generateDeptSheet(workbook);
                } else if ("member".equals(type)) {
                    generateMemberSheet(workbook, deptIds, currentUser);
                } else if ("activity".equals(type)) {
                    generateActivitySheet(workbook, startDate, endDate);
                }
            }

            // 根据选择的类型确定文件名
            String fileName;
            if (types.size() == 1) {
                String typeName = getTypeDisplayName(types.get(0));
                fileName = typeName + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
            } else {
                fileName = "社团档案_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
            }
            
            // 这里应该保存到文件系统或OSS
            // 为了简化，我们只返回文件名
            workbook.close();
            return fileName;
        } catch (Exception e) {
            throw new RuntimeException("生成Excel失败", e);
        }
    }
    
    /**
     * 获取类型显示名称
     */
    private String getTypeDisplayName(String type) {
        switch (type) {
            case "dept": return "部门信息";
            case "member": return "成员信息";
            case "activity": return "活动信息";
            default: return "数据";
        }
    }


    /**
     * 生成ZIP导出
     */
    private String generateZipExport(String exportId, List<String> types, String startDate, String endDate, Boolean includeFiles, List<Integer> deptIds, Object currentUser) {
        try {
            String fileName = "社团档案_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip";
            String filePath = "./exports/" + fileName;
            
            // 确保导出目录存在
            java.io.File exportDir = new java.io.File("./exports");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }
            
            // 创建ZIP文件
            java.io.FileOutputStream fos = new java.io.FileOutputStream(filePath);
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos);
            
            try {
                // 添加README.txt
                java.util.zip.ZipEntry readmeEntry = new java.util.zip.ZipEntry("README.txt");
                zos.putNextEntry(readmeEntry);
                String readmeContent = "社团管理系统数据导出包\n" +
                    "导出时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n" +
                    "导出类型: ";
                for (int i = 0; i < types.size(); i++) {
                    if (i > 0) readmeContent += "、";
                    readmeContent += getTypeDisplayName(types.get(i));
                }
                readmeContent += "\n包含文件: Excel表格\n" +
                    "使用说明: 请使用Excel或WPS等办公软件打开文件";
                zos.write(readmeContent.getBytes("UTF-8"));
                zos.closeEntry();
                
                // 为每个选择的类型生成Excel文件
                for (String type : types) {
                    // 生成Excel文件
                    String excelFileName = getTypeDisplayName(type) + ".xlsx";
                    java.util.zip.ZipEntry excelEntry = new java.util.zip.ZipEntry(excelFileName);
                    zos.putNextEntry(excelEntry);
                    
                    Workbook workbook = new XSSFWorkbook();
                    if ("dept".equals(type)) {
                        generateDeptSheet(workbook);
                    } else if ("member".equals(type)) {
                        generateMemberSheet(workbook, deptIds, currentUser);
                    } else if ("activity".equals(type)) {
                        generateActivitySheet(workbook, startDate, endDate);
                    }
                    
                    java.io.ByteArrayOutputStream excelBaos = new java.io.ByteArrayOutputStream();
                    workbook.write(excelBaos);
                    workbook.close();
                    zos.write(excelBaos.toByteArray());
                    zos.closeEntry();
                    
                }
                
            } finally {
                zos.close();
                fos.close();
            }
            
            return fileName;
        } catch (Exception e) {
            throw new RuntimeException("生成ZIP失败", e);
        }
    }

    /**
     * 生成部门信息Sheet
     */
    private void generateDeptSheet(Workbook workbook) {
        try {
            Sheet sheet = workbook.createSheet("部门信息");
            
            // 设置列宽（删除了部门ID列）
            sheet.setColumnWidth(0, 20 * 256);  // 部门名称
            sheet.setColumnWidth(1, 50 * 256);  // 部门简介
            sheet.setColumnWidth(2, 15 * 256);  // 创建时间
            sheet.setColumnWidth(3, 10 * 256);  // 成员数量
            sheet.setColumnWidth(4, 15 * 256);  // 负责人
            sheet.setColumnWidth(5, 20 * 256);  // 联系方式
            
            // 创建表头（删除了部门ID列）
            Row headerRow = sheet.createRow(0);
            String[] headers = {"部门名称", "部门简介", "创建时间", "成员数量", "负责人", "联系方式"};
            
            // 设置表头样式
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 14);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 获取部门数据
            List<com.club.management.entity.Dept> depts = deptMapper.selectList(null);
            
            // 设置数据样式
            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setAlignment(HorizontalAlignment.LEFT);
            
            CellStyle numberStyle = workbook.createCellStyle();
            numberStyle.setAlignment(HorizontalAlignment.RIGHT);
            
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            dateStyle.setAlignment(HorizontalAlignment.LEFT);
            
            // 填充数据（删除了部门ID列）
            int rowNum = 1;
            for (com.club.management.entity.Dept dept : depts) {
                Row row = sheet.createRow(rowNum++);
                
                // 部门名称 - 文本格式，左对齐
                Cell nameCell = row.createCell(0);
                nameCell.setCellValue(dept.getName() != null ? dept.getName() : "");
                nameCell.setCellStyle(dataStyle);
                
                // 部门简介 - 文本格式，左对齐
                Cell introCell = row.createCell(1);
                introCell.setCellValue(dept.getIntro() != null ? dept.getIntro() : "");
                introCell.setCellStyle(dataStyle);
                
                // 创建时间 - 日期格式
                Cell timeCell = row.createCell(2);
                if (dept.getCreateTime() != null) {
                    timeCell.setCellValue(dept.getCreateTime());
                    timeCell.setCellStyle(dateStyle);
                } else {
                    timeCell.setCellValue("");
                    timeCell.setCellStyle(dataStyle);
                }
                
                // 成员数量 - 数字格式，右对齐（需要查询实际成员数量）
                Cell countCell = row.createCell(3);
                int memberCount = getDeptMemberCount(dept.getId() != null ? dept.getId().intValue() : null);
                countCell.setCellValue(memberCount);
                countCell.setCellStyle(numberStyle);
                
                // 负责人 - 文本格式，左对齐（查询部长）
                Cell leaderCell = row.createCell(4);
                String leader = getDeptLeader(dept.getId() != null ? dept.getId().intValue() : null);
                leaderCell.setCellValue(leader);
                leaderCell.setCellStyle(dataStyle);
                
                // 联系方式 - 文本格式，左对齐（部长的电话）
                Cell contactCell = row.createCell(5);
                String leaderPhone = getDeptLeaderPhone(dept.getId() != null ? dept.getId().intValue() : null);
                contactCell.setCellValue(leaderPhone);
                contactCell.setCellStyle(dataStyle);
            }
        } catch (Exception e) {
            System.err.println("生成部门信息Sheet失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * 获取部门成员数量
     */
    private int getDeptMemberCount(Integer deptId) {
        try {
            if (deptId == null) return 0;
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.club.management.entity.Member> queryWrapper = 
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            queryWrapper.eq("dept_id", deptId);
            return memberMapper.selectCount(queryWrapper).intValue();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 获取部门负责人（部长）
     */
    private String getDeptLeader(Integer deptId) {
        try {
            if (deptId == null) return "";
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.club.management.entity.Member> queryWrapper = 
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            queryWrapper.eq("dept_id", deptId);
            queryWrapper.eq("role", "部长");
            queryWrapper.last("LIMIT 1");
            
            List<com.club.management.entity.Member> leaders = memberMapper.selectList(queryWrapper);
            if (!leaders.isEmpty()) {
                return leaders.get(0).getName();
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 获取部门负责人电话
     */
    private String getDeptLeaderPhone(Integer deptId) {
        try {
            if (deptId == null) return "";
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.club.management.entity.Member> queryWrapper = 
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            queryWrapper.eq("dept_id", deptId);
            queryWrapper.eq("role", "部长");
            queryWrapper.last("LIMIT 1");
            
            List<com.club.management.entity.Member> leaders = memberMapper.selectList(queryWrapper);
            if (!leaders.isEmpty()) {
                return leaders.get(0).getPhone() != null ? leaders.get(0).getPhone() : "";
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 生成成员信息Sheet
     */
    private void generateMemberSheet(Workbook workbook, List<Integer> deptIds, Object currentUser) {
        try {
            Sheet sheet = workbook.createSheet("成员信息");
            
            // 设置列宽
            sheet.setColumnWidth(0, 15 * 256);  // 学号
            sheet.setColumnWidth(1, 10 * 256);  // 姓名
            sheet.setColumnWidth(2, 5 * 256);   // 性别
            sheet.setColumnWidth(3, 20 * 256);  // 学院
            sheet.setColumnWidth(4, 25 * 256);  // 专业
            sheet.setColumnWidth(5, 10 * 256);  // 年级
            sheet.setColumnWidth(6, 15 * 256);  // 手机号
            sheet.setColumnWidth(7, 30 * 256);  // 邮箱
            sheet.setColumnWidth(8, 15 * 256);  // 入社时间
            sheet.setColumnWidth(9, 20 * 256);  // 部门名称
            sheet.setColumnWidth(10, 15 * 256); // 角色
            sheet.setColumnWidth(11, 10 * 256); // 状态
            
            // 创建表头
            Row headerRow = sheet.createRow(0);
            String[] headers = {"学号", "姓名", "性别", "学院", "专业", "年级", "手机号", "邮箱", "入社时间", "部门名称", "角色", "状态"};
            
            // 设置表头样式
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 14);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 获取成员数据，支持部门筛选
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.club.management.entity.Member> queryWrapper = 
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            
            // 如果有部门筛选条件
            if (deptIds != null && !deptIds.isEmpty()) {
                queryWrapper.in("dept_id", deptIds);
            }
            
            // 如果是部长或副部长，只导出本部门成员
            if (currentUser instanceof com.club.management.entity.Member) {
                com.club.management.entity.Member member = (com.club.management.entity.Member) currentUser;
                String role = member.getRole();
                if ("部长".equals(role) || "副部长".equals(role)) {
                    if (member.getDeptId() != null) {
                        queryWrapper.eq("dept_id", member.getDeptId());
                    }
                }
            }
            
            List<com.club.management.entity.Member> members = memberMapper.selectList(queryWrapper);
            
            // 设置数据样式
            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setAlignment(HorizontalAlignment.LEFT);
            
            CellStyle numberStyle = workbook.createCellStyle();
            numberStyle.setAlignment(HorizontalAlignment.RIGHT);
            
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            dateStyle.setAlignment(HorizontalAlignment.LEFT);
            
            // 填充数据
            int rowNum = 1;
            for (com.club.management.entity.Member member : members) {
                Row row = sheet.createRow(rowNum++);
                
                // 学号 - 文本格式，左对齐
                Cell stuIdCell = row.createCell(0);
                stuIdCell.setCellValue(member.getStuId() != null ? member.getStuId() : "");
                stuIdCell.setCellStyle(dataStyle);
                
                // 姓名 - 文本格式，左对齐
                Cell nameCell = row.createCell(1);
                nameCell.setCellValue(member.getName() != null ? member.getName() : "");
                nameCell.setCellStyle(dataStyle);
                
                // 性别 - 文本格式，左对齐
                Cell genderCell = row.createCell(2);
                genderCell.setCellValue(member.getGender() != null ? member.getGender() : "");
                genderCell.setCellStyle(dataStyle);
                
                // 学院 - 文本格式，左对齐
                Cell collegeCell = row.createCell(3);
                collegeCell.setCellValue(member.getCollege() != null ? member.getCollege() : "");
                collegeCell.setCellStyle(dataStyle);
                
                // 专业 - 文本格式，左对齐
                Cell majorCell = row.createCell(4);
                majorCell.setCellValue(member.getMajor() != null ? member.getMajor() : "");
                majorCell.setCellStyle(dataStyle);
                
                // 年级 - 文本格式，左对齐
                Cell gradeCell = row.createCell(5);
                gradeCell.setCellValue(member.getGrade() != null ? member.getGrade() : "");
                gradeCell.setCellStyle(dataStyle);
                
                // 手机号 - 文本格式，左对齐
                Cell phoneCell = row.createCell(6);
                phoneCell.setCellValue(member.getPhone() != null ? member.getPhone() : "");
                phoneCell.setCellStyle(dataStyle);
                
                // 邮箱 - 文本格式，左对齐
                Cell emailCell = row.createCell(7);
                emailCell.setCellValue(member.getEmail() != null ? member.getEmail() : "");
                emailCell.setCellStyle(dataStyle);
                
                // 入社时间 - 日期格式
                Cell joinDateCell = row.createCell(8);
                if (member.getJoinDate() != null) {
                    joinDateCell.setCellValue(member.getJoinDate());
                    joinDateCell.setCellStyle(dateStyle);
                } else {
                    joinDateCell.setCellValue("");
                    joinDateCell.setCellStyle(dataStyle);
                }
                
                // 部门名称 - 文本格式，左对齐（需要查询部门名称）
                Cell deptNameCell = row.createCell(9);
                String deptName = getDeptNameById(member.getDeptId() != null ? member.getDeptId().intValue() : null);
                deptNameCell.setCellValue(deptName);
                deptNameCell.setCellStyle(dataStyle);
                
                // 角色 - 文本格式，左对齐
                Cell roleCell = row.createCell(10);
                roleCell.setCellValue(member.getRole() != null ? member.getRole() : "");
                roleCell.setCellStyle(dataStyle);
                
                // 状态 - 文本格式，左对齐（默认为在籍）
                Cell statusCell = row.createCell(11);
                statusCell.setCellValue("在籍");
                statusCell.setCellStyle(dataStyle);
            }
        } catch (Exception e) {
            System.err.println("生成成员信息Sheet失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * 根据部门ID获取部门名称
     */
    private String getDeptNameById(Integer deptId) {
        try {
            if (deptId == null) return "";
            com.club.management.entity.Dept dept = deptMapper.selectById(deptId);
            return dept != null ? dept.getName() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 生成活动信息Sheet
     */
    private void generateActivitySheet(Workbook workbook, String startDate, String endDate) {
        try {
            Sheet sheet = workbook.createSheet("活动信息");
            
            // 设置列宽
            sheet.setColumnWidth(0, 30 * 256);  // 活动名称
            sheet.setColumnWidth(1, 15 * 256);  // 活动类型
            sheet.setColumnWidth(2, 20 * 256);  // 开始时间
            sheet.setColumnWidth(3, 20 * 256);  // 结束时间
            sheet.setColumnWidth(4, 30 * 256);  // 活动地点
            sheet.setColumnWidth(5, 10 * 256);  // 活动状态
            sheet.setColumnWidth(6, 10 * 256);  // 参与人数
            sheet.setColumnWidth(7, 15 * 256);  // 负责人
            sheet.setColumnWidth(8, 50 * 256);  // 活动描述
            sheet.setColumnWidth(9, 20 * 256);  // 创建时间
            
            // 创建表头
            Row headerRow = sheet.createRow(0);
            String[] headers = {"活动名称", "活动类型", "开始时间", "结束时间", "活动地点", "活动状态", "参与人数", "负责部门", "活动描述", "创建时间"};
            
            // 设置表头样式
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 14);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 获取活动数据，支持时间范围筛选（开始时间和结束时间都在范围内）
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.club.management.entity.Activity> queryWrapper = 
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            
            if (startDate != null && !startDate.isEmpty()) {
                queryWrapper.ge("start_time", startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                queryWrapper.le("end_time", endDate);
            }
            
            List<com.club.management.entity.Activity> activities = activityMapper.selectList(queryWrapper);
            
            // 设置数据样式
            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setAlignment(HorizontalAlignment.LEFT);
            
            CellStyle numberStyle = workbook.createCellStyle();
            numberStyle.setAlignment(HorizontalAlignment.RIGHT);
            
            CellStyle dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            dateTimeStyle.setAlignment(HorizontalAlignment.LEFT);
            
            // 填充数据
            int rowNum = 1;
            for (com.club.management.entity.Activity activity : activities) {
                Row row = sheet.createRow(rowNum++);
                
                // 活动名称 - 文本格式，左对齐
                Cell nameCell = row.createCell(0);
                nameCell.setCellValue(activity.getName() != null ? activity.getName() : "");
                nameCell.setCellStyle(dataStyle);
                
                // 活动类型 - 文本格式，左对齐
                Cell typeCell = row.createCell(1);
                typeCell.setCellValue(activity.getType() != null ? activity.getType() : "");
                typeCell.setCellStyle(dataStyle);
                
                // 开始时间 - 日期时间格式
                Cell startTimeCell = row.createCell(2);
                if (activity.getStartTime() != null) {
                    startTimeCell.setCellValue(activity.getStartTime());
                    startTimeCell.setCellStyle(dateTimeStyle);
                } else {
                    startTimeCell.setCellValue("");
                    startTimeCell.setCellStyle(dataStyle);
                }
                
                // 结束时间 - 日期时间格式
                Cell endTimeCell = row.createCell(3);
                if (activity.getEndTime() != null) {
                    endTimeCell.setCellValue(activity.getEndTime());
                    endTimeCell.setCellStyle(dateTimeStyle);
                } else {
                    endTimeCell.setCellValue("");
                    endTimeCell.setCellStyle(dataStyle);
                }
                
                // 活动地点 - 文本格式，左对齐
                Cell locationCell = row.createCell(4);
                locationCell.setCellValue(activity.getLocation() != null ? activity.getLocation() : "");
                locationCell.setCellStyle(dataStyle);
                
                // 活动状态 - 文本格式，左对齐
                Cell statusCell = row.createCell(5);
                String statusText = "";
                if (activity.getStatus() != null) {
                    switch (activity.getStatus()) {
                        case 0: statusText = "待审批"; break;
                        case 1: statusText = "已通过"; break;
                        case 2: statusText = "已驳回"; break;
                        default: statusText = "未知状态"; break;
                    }
                }
                statusCell.setCellValue(statusText);
                statusCell.setCellStyle(dataStyle);
                
                // 参与人数 - 数字格式，右对齐（需要查询实际参与人数）
                Cell participantCountCell = row.createCell(6);
                int participantCount = getActivityParticipantCount(activity.getId() != null ? activity.getId().intValue() : null);
                participantCountCell.setCellValue(participantCount);
                participantCountCell.setCellStyle(numberStyle);
                
                // 负责部门 - 文本格式，左对齐（需要查询活动负责部门）
                Cell deptCell = row.createCell(7);
                String deptNames = getActivityDeptNames(activity.getId() != null ? activity.getId().intValue() : null);
                deptCell.setCellValue(deptNames);
                deptCell.setCellStyle(dataStyle);
                
                // 活动描述 - 文本格式，左对齐
                Cell descriptionCell = row.createCell(8);
                descriptionCell.setCellValue(activity.getDescription() != null ? activity.getDescription() : "");
                descriptionCell.setCellStyle(dataStyle);
                
                // 创建时间 - 日期时间格式
                Cell createTimeCell = row.createCell(9);
                if (activity.getCreateTime() != null) {
                    createTimeCell.setCellValue(activity.getCreateTime());
                    createTimeCell.setCellStyle(dateTimeStyle);
                } else {
                    createTimeCell.setCellValue("");
                    createTimeCell.setCellStyle(dataStyle);
                }
            }
        } catch (Exception e) {
            System.err.println("生成活动信息Sheet失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * 获取活动参与人数
     */
    private int getActivityParticipantCount(Integer activityId) {
        try {
            if (activityId == null) return 0;
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.club.management.entity.ActivityMember> queryWrapper = 
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            queryWrapper.eq("activity_id", activityId);
            return activityMemberMapper.selectCount(queryWrapper).intValue();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 获取活动负责部门
     */
    private String getActivityDeptNames(Integer activityId) {
        try {
            if (activityId == null) return "";
            // 查询活动的负责部门
            List<Map<String, Object>> depts = activityMapper.selectActivityDepts(Long.valueOf(activityId));
            if (depts != null && !depts.isEmpty()) {
                return depts.stream()
                    .map(dept -> (String) dept.get("deptName"))
                    .filter(name -> name != null && !name.isEmpty())
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 生成消息信息Sheet
     */
    private void generateMessageSheet(Workbook workbook, String startDate, String endDate) {
        try {
            Sheet sheet = workbook.createSheet("消息记录");
            
            // 消息功能已删除，创建空表头
            Row headerRow = sheet.createRow(0);
            String[] headers = {"消息功能已删除"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }
        } catch (Exception e) {
            System.err.println("生成消息信息Sheet失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    // 内存存储导出历史（生产环境应使用数据库）
    private static final Map<String, Map<String, Object>> exportHistory = new HashMap<>();

    /**
     * 获取导出历史
     */
    public Result<List<Map<String, Object>>> getExportHistory() {
        List<Map<String, Object>> history = new ArrayList<>(exportHistory.values());
        // 按创建时间倒序排列
        history.sort((a, b) -> {
            String timeA = (String) a.get("createTime");
            String timeB = (String) b.get("createTime");
            return timeB.compareTo(timeA);
        });
        return Result.success(history);
    }

    /**
     * 下载导出文件
     */
    public void downloadExportFile(String id, HttpServletResponse response, Object currentUser) {
        try {
            // 检查导出记录是否存在
            Map<String, Object> exportRecord = exportHistory.get(id);
            if (exportRecord == null) {
                throw new RuntimeException("找不到导出记录，请重新生成导出包");
            }
            
            String format = (String) exportRecord.get("format");
            String fileName = (String) exportRecord.get("fileName");
            
            // 根据格式设置响应头
            if ("excel".equals(format)) {
                response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            } else {
                response.setContentType("application/zip");
                response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            }
            
            // 生成文件内容
            if ("excel".equals(format)) {
                generateExcelForDownload(id, response, currentUser);
            } else {
                generateZipForDownload(id, response, currentUser);
            }
        } catch (IOException e) {
            throw new RuntimeException("下载文件失败", e);
        }
    }

    /**
     * 下载活动详情
     */
    public void downloadActivityDetail(Long activityId, HttpServletResponse response, Object currentUser) {
        try {
            // 权限检查
            if (!hasExportPermission(currentUser)) {
                throw new RuntimeException("权限不足，无法导出数据");
            }
            
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("UTF-8");
            String excelFileName = "活动详情_" + activityId + ".xlsx";
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + 
                java.net.URLEncoder.encode(excelFileName, "UTF-8"));
            
            // 生成活动详情Excel
            Workbook workbook = new XSSFWorkbook();
            generateActivityDetailSheet(workbook, activityId);
            
            workbook.write(response.getOutputStream());
            response.getOutputStream().flush();
            workbook.close();
        } catch (Exception e) {
            throw new RuntimeException("下载活动详情失败", e);
        }
    }

    /**
     * 删除导出文件
     */
    public Result<String> deleteExportFile(String id) {
        try {
            // 从导出历史中删除记录
            exportHistory.remove(id);
            return Result.success("删除成功");
        } catch (Exception e) {
            return Result.businessError(500, "删除失败: " + e.getMessage());
        }
    }

    /**
     * 生成Excel文件用于下载
     */
    private void generateExcelForDownload(String id, HttpServletResponse response, Object currentUser) throws IOException {
        // 获取导出记录中的类型信息
        Map<String, Object> exportRecord = exportHistory.get(id);
        if (exportRecord == null) {
            throw new RuntimeException("找不到导出记录");
        }
        
        // 从导出记录中获取用户选择的类型
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) exportRecord.get("types");
        String startDate = (String) exportRecord.get("startDate");
        String endDate = (String) exportRecord.get("endDate");
        @SuppressWarnings("unchecked")
        List<Integer> deptIds = (List<Integer>) exportRecord.get("deptIds");
        
        if (types == null || types.isEmpty()) {
            throw new RuntimeException("导出记录中缺少类型信息");
        }
        
        Workbook workbook = new XSSFWorkbook();
        
        // 根据用户选择的类型生成相应的Sheet
        for (String type : types) {
            if ("dept".equals(type)) {
                generateDeptSheet(workbook);
            } else if ("member".equals(type)) {
                generateMemberSheet(workbook, deptIds, currentUser);
            } else if ("activity".equals(type)) {
                generateActivitySheet(workbook, startDate, endDate);
            }
        }
        
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        workbook.write(response.getOutputStream());
        response.getOutputStream().flush();
        workbook.close();
    }


    /**
     * 生成ZIP文件用于下载
     */
    private void generateZipForDownload(String id, HttpServletResponse response, Object currentUser) throws IOException {
        // 获取导出记录中的类型信息
        Map<String, Object> exportRecord = exportHistory.get(id);
        if (exportRecord == null) {
            throw new RuntimeException("找不到导出记录");
        }
        
        // 从导出记录中获取用户选择的类型
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) exportRecord.get("types");
        String startDate = (String) exportRecord.get("startDate");
        String endDate = (String) exportRecord.get("endDate");
        @SuppressWarnings("unchecked")
        List<Integer> deptIds = (List<Integer>) exportRecord.get("deptIds");
        
        if (types == null || types.isEmpty()) {
            throw new RuntimeException("导出记录中缺少类型信息");
        }
        
        // 创建真正的ZIP文件
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos);
        
        try {
            // 添加说明文件
            java.util.zip.ZipEntry readmeEntry = new java.util.zip.ZipEntry("README.txt");
            zos.putNextEntry(readmeEntry);
            String readmeContent = "社团管理系统导出包\n";
            
            // 单选时不显示导出ID，多选时显示
            if (types.size() > 1) {
                readmeContent += "导出时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n";
            } else {
                readmeContent += "导出ID: " + id + "\n" +
                    "导出时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n";
            }
            
            readmeContent += "导出类型: ";
            for (int i = 0; i < types.size(); i++) {
                if (i > 0) readmeContent += "、";
                readmeContent += getTypeDisplayName(types.get(i));
            }
            readmeContent += "\n包含文件: Excel表格\n" +
                "使用说明: 请使用Excel或WPS等办公软件打开文件";
            zos.write(readmeContent.getBytes("UTF-8"));
            zos.closeEntry();
            
            // 为每个选择的类型生成Excel文件
            for (String type : types) {
                // 生成Excel文件
                String excelFileName = getTypeDisplayName(type) + ".xlsx";
                java.util.zip.ZipEntry excelEntry = new java.util.zip.ZipEntry(excelFileName);
                zos.putNextEntry(excelEntry);
                
                Workbook workbook = new XSSFWorkbook();
                if ("dept".equals(type)) {
                    generateDeptSheet(workbook);
                } else if ("member".equals(type)) {
                    generateMemberSheet(workbook, deptIds, currentUser);
                } else if ("activity".equals(type)) {
                    generateActivitySheet(workbook, startDate, endDate);
                }
                
                java.io.ByteArrayOutputStream excelBaos = new java.io.ByteArrayOutputStream();
                workbook.write(excelBaos);
                workbook.close();
                
                zos.write(excelBaos.toByteArray());
                zos.closeEntry();
            }
            
        } finally {
            zos.close();
        }
        
        response.setContentType("application/zip");
        response.setCharacterEncoding("UTF-8");
        String zipFileName = "社团档案_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip";
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + 
            java.net.URLEncoder.encode(zipFileName, "UTF-8"));
        
        response.getOutputStream().write(baos.toByteArray());
        response.getOutputStream().flush();
    }

    /**
     * 添加部门信息到PDF
     */
    private void addDeptToPdf(Document document) throws Exception {
        document.add(new Paragraph("部门信息")
            .setFontSize(16)
            .setMarginTop(20)
            .setMarginBottom(10));
        
        List<com.club.management.entity.Dept> depts = deptMapper.selectList(null);
        if (depts.isEmpty()) {
            document.add(new Paragraph("暂无部门信息")
                .setFontSize(12)
                .setMarginBottom(5));
        } else {
            for (com.club.management.entity.Dept dept : depts) {
                document.add(new Paragraph("部门名称: " + (dept.getName() != null ? dept.getName() : "未知"))
                    .setFontSize(12)
                    .setMarginBottom(5));
                
                if (dept.getIntro() != null && !dept.getIntro().isEmpty()) {
                    document.add(new Paragraph("部门简介: " + dept.getIntro())
                        .setFontSize(10)
                        .setMarginBottom(10));
                }
                
                document.add(new Paragraph("创建时间: " + (dept.getCreateTime() != null ? dept.getCreateTime().toString() : "未知"))
                    .setFontSize(10)
                    .setMarginBottom(10));
            }
        }
    }

    /**
     * 添加成员信息到PDF
     */
    private void addMemberToPdf(Document document) throws Exception {
        document.add(new Paragraph("成员信息")
            .setFontSize(16)
            .setMarginTop(20)
            .setMarginBottom(10));
        
        List<com.club.management.entity.Member> members = memberMapper.selectList(null);
        if (members.isEmpty()) {
            document.add(new Paragraph("暂无成员信息")
                .setFontSize(12)
                .setMarginBottom(5));
        } else {
            for (com.club.management.entity.Member member : members) {
                document.add(new Paragraph("姓名: " + (member.getName() != null ? member.getName() : "未知") + 
                    " | 学号: " + (member.getStuId() != null ? member.getStuId() : "未知") + 
                    " | 角色: " + (member.getRole() != null ? member.getRole() : "未知"))
                    .setFontSize(10)
                    .setMarginBottom(5));
                
                if (member.getPhone() != null && !member.getPhone().isEmpty()) {
                    document.add(new Paragraph("手机: " + member.getPhone())
                        .setFontSize(9)
                        .setMarginBottom(3));
                }
                
                if (member.getEmail() != null && !member.getEmail().isEmpty()) {
                    document.add(new Paragraph("邮箱: " + member.getEmail())
                        .setFontSize(9)
                        .setMarginBottom(3));
                }
                
                document.add(new Paragraph("---")
                    .setFontSize(8)
                    .setMarginBottom(5));
            }
        }
    }

    /**
     * 添加活动信息到PDF
     */
    private void addActivityToPdf(Document document, String startDate, String endDate) throws Exception {
        document.add(new Paragraph("活动信息")
            .setFontSize(16)
            .setMarginTop(20)
            .setMarginBottom(10));
        
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.club.management.entity.Activity> queryWrapper = 
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        
        if (startDate != null && !startDate.isEmpty()) {
            queryWrapper.ge("start_time", startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            queryWrapper.le("start_time", endDate);
        }
        
        List<com.club.management.entity.Activity> activities = activityMapper.selectList(queryWrapper);
        if (activities.isEmpty()) {
            document.add(new Paragraph("暂无活动信息")
                .setFontSize(12)
                .setMarginBottom(5));
        } else {
            for (com.club.management.entity.Activity activity : activities) {
                document.add(new Paragraph("活动名称: " + (activity.getName() != null ? activity.getName() : "未知") + 
                    " | 类型: " + (activity.getType() != null ? activity.getType() : "未知") + 
                    " | 时间: " + (activity.getStartTime() != null ? activity.getStartTime().toString() : "未知"))
                    .setFontSize(10)
                    .setMarginBottom(5));
                
                if (activity.getLocation() != null && !activity.getLocation().isEmpty()) {
                    document.add(new Paragraph("地点: " + activity.getLocation())
                        .setFontSize(9)
                        .setMarginBottom(3));
                }
                
                if (activity.getStatus() != null) {
                    document.add(new Paragraph("状态: " + activity.getStatus())
                        .setFontSize(9)
                        .setMarginBottom(3));
                }
                
                document.add(new Paragraph("---")
                    .setFontSize(8)
                    .setMarginBottom(5));
            }
        }
    }
    
    /**
     * 生成活动详情Excel
     */
    private void generateActivityDetailSheet(Workbook workbook, Long activityId) {
        Sheet sheet = workbook.createSheet("活动详情");
        
        // 创建标题行并设置样式
        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("活动详情");
        
        // 设置标题样式
        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleStyle.setFont(titleFont);
        titleStyle.setAlignment(HorizontalAlignment.CENTER);
        titleCell.setCellStyle(titleStyle);
        
        // 合并标题单元格
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 1));
        
        // 获取活动信息
        com.club.management.entity.Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return;
        }
        
        // 创建标签样式
        CellStyle labelStyle = workbook.createCellStyle();
        Font labelFont = workbook.createFont();
        labelFont.setBold(true);
        labelFont.setFontHeightInPoints((short) 11);
        labelStyle.setFont(labelFont);
        labelStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        labelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        labelStyle.setAlignment(HorizontalAlignment.LEFT);
        labelStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        labelStyle.setBorderBottom(BorderStyle.THIN);
        labelStyle.setBorderTop(BorderStyle.THIN);
        labelStyle.setBorderLeft(BorderStyle.THIN);
        labelStyle.setBorderRight(BorderStyle.THIN);
        
        // 创建值样式
        CellStyle valueStyle = workbook.createCellStyle();
        valueStyle.setAlignment(HorizontalAlignment.LEFT);
        valueStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        valueStyle.setBorderBottom(BorderStyle.THIN);
        valueStyle.setBorderTop(BorderStyle.THIN);
        valueStyle.setBorderLeft(BorderStyle.THIN);
        valueStyle.setBorderRight(BorderStyle.THIN);
        valueStyle.setWrapText(true);
        
        // 添加活动基本信息
        int rowNum = 2;
        
        // 设置列宽
        sheet.setColumnWidth(0, 20 * 256);  // 标签列
        sheet.setColumnWidth(1, 40 * 256);  // 值列
        
        Row nameRow = sheet.createRow(rowNum++);
        Cell nameLabelCell = nameRow.createCell(0);
        nameLabelCell.setCellValue("活动名称");
        nameLabelCell.setCellStyle(labelStyle);
        Cell nameValueCell = nameRow.createCell(1);
        nameValueCell.setCellValue(getStringValue(activity.getName()));
        nameValueCell.setCellStyle(valueStyle);
        
        Row typeRow = sheet.createRow(rowNum++);
        Cell typeLabelCell = typeRow.createCell(0);
        typeLabelCell.setCellValue("活动类型");
        typeLabelCell.setCellStyle(labelStyle);
        Cell typeValueCell = typeRow.createCell(1);
        typeValueCell.setCellValue(getStringValue(activity.getType()));
        typeValueCell.setCellStyle(valueStyle);
        
        Row startTimeRow = sheet.createRow(rowNum++);
        Cell startTimeLabelCell = startTimeRow.createCell(0);
        startTimeLabelCell.setCellValue("开始时间");
        startTimeLabelCell.setCellStyle(labelStyle);
        Cell startTimeValueCell = startTimeRow.createCell(1);
        startTimeValueCell.setCellValue(activity.getStartTime() != null ? activity.getStartTime().toString() : "");
        startTimeValueCell.setCellStyle(valueStyle);
        
        Row endTimeRow = sheet.createRow(rowNum++);
        Cell endTimeLabelCell = endTimeRow.createCell(0);
        endTimeLabelCell.setCellValue("结束时间");
        endTimeLabelCell.setCellStyle(labelStyle);
        Cell endTimeValueCell = endTimeRow.createCell(1);
        endTimeValueCell.setCellValue(activity.getEndTime() != null ? activity.getEndTime().toString() : "");
        endTimeValueCell.setCellStyle(valueStyle);
        
        Row locationRow = sheet.createRow(rowNum++);
        Cell locationLabelCell = locationRow.createCell(0);
        locationLabelCell.setCellValue("活动地点");
        locationLabelCell.setCellStyle(labelStyle);
        Cell locationValueCell = locationRow.createCell(1);
        locationValueCell.setCellValue(getStringValue(activity.getLocation()));
        locationValueCell.setCellStyle(valueStyle);
        
        Row statusRow = sheet.createRow(rowNum++);
        Cell statusLabelCell = statusRow.createCell(0);
        statusLabelCell.setCellValue("活动状态");
        statusLabelCell.setCellStyle(labelStyle);
        Cell statusValueCell = statusRow.createCell(1);
        statusValueCell.setCellValue(getStatusText(activity.getStatus()));
        statusValueCell.setCellStyle(valueStyle);
        
        Row descRow = sheet.createRow(rowNum++);
        Cell descLabelCell = descRow.createCell(0);
        descLabelCell.setCellValue("活动简介");
        descLabelCell.setCellStyle(labelStyle);
        Cell descValueCell = descRow.createCell(1);
        descValueCell.setCellValue(getStringValue(activity.getDescription()));
        descValueCell.setCellStyle(valueStyle);
        
        // 添加参与人员标题
        rowNum += 2;
        Row memberTitleRow = sheet.createRow(rowNum++);
        Cell memberTitleCell = memberTitleRow.createCell(0);
        memberTitleCell.setCellValue("参与人员");
        
        // 设置参与人员标题样式
        CellStyle memberTitleStyle = workbook.createCellStyle();
        Font memberTitleFont = workbook.createFont();
        memberTitleFont.setBold(true);
        memberTitleFont.setFontHeightInPoints((short) 14);
        memberTitleStyle.setFont(memberTitleFont);
        memberTitleStyle.setAlignment(HorizontalAlignment.LEFT);
        memberTitleCell.setCellStyle(memberTitleStyle);
        
        // 合并参与人员标题单元格
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowNum - 1, rowNum - 1, 0, 6));
        
        // 参与人员表头
        Row memberHeaderRow = sheet.createRow(rowNum++);
        String[] memberHeaders = {"姓名", "学号", "性别", "学院", "专业", "年级", "角色"};
        
        // 设置表头样式
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 11);
        headerStyle.setFont(headerFont);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);
        
        // 设置各列宽度
        int[] columnWidths = {15, 15, 8, 20, 25, 10, 12};
        for (int i = 0; i < memberHeaders.length; i++) {
            Cell headerCell = memberHeaderRow.createCell(i);
            headerCell.setCellValue(memberHeaders[i]);
            headerCell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }
        
        // 添加参与人员数据
        List<Map<String, Object>> members = activityMapper.selectActivityMembers(activityId);
        
        // 创建数据样式
        CellStyle dataStyle = workbook.createCellStyle();
        dataStyle.setAlignment(HorizontalAlignment.LEFT);
        dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        dataStyle.setBorderBottom(BorderStyle.THIN);
        dataStyle.setBorderTop(BorderStyle.THIN);
        dataStyle.setBorderLeft(BorderStyle.THIN);
        dataStyle.setBorderRight(BorderStyle.THIN);
        
        for (Map<String, Object> member : members) {
            Row memberRow = sheet.createRow(rowNum++);
            Cell cell0 = memberRow.createCell(0);
            cell0.setCellValue(getStringValue(member.get("name")));
            cell0.setCellStyle(dataStyle);
            
            Cell cell1 = memberRow.createCell(1);
            cell1.setCellValue(getStringValue(member.get("stuId")));
            cell1.setCellStyle(dataStyle);
            
            Cell cell2 = memberRow.createCell(2);
            cell2.setCellValue(getStringValue(member.get("gender")));
            cell2.setCellStyle(dataStyle);
            
            Cell cell3 = memberRow.createCell(3);
            cell3.setCellValue(getStringValue(member.get("college")));
            cell3.setCellStyle(dataStyle);
            
            Cell cell4 = memberRow.createCell(4);
            cell4.setCellValue(getStringValue(member.get("major")));
            cell4.setCellStyle(dataStyle);
            
            Cell cell5 = memberRow.createCell(5);
            cell5.setCellValue(getStringValue(member.get("grade")));
            cell5.setCellStyle(dataStyle);
            
            Cell cell6 = memberRow.createCell(6);
            cell6.setCellValue(getStringValue(member.get("role")));
            cell6.setCellStyle(dataStyle);
        }
    }
    
    private String getStatusText(Integer status) {
        if (status == null) return "未知";
        switch (status) {
            case 0: return "待审批";
            case 1: return "已通过";
            case 2: return "已驳回";
            default: return "未知";
        }
    }
    
    /**
     * 安全地获取字符串值
     */
    private String getStringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        return value.toString();
    }

    /**
     * 检查导出权限
     */
    private boolean hasExportPermission(Object currentUser) {
        if (currentUser instanceof com.club.management.entity.Member) {
            com.club.management.entity.Member member = (com.club.management.entity.Member) currentUser;
            String role = member.getRole();
            // 根据SDS.md，社长、副社长、部长、副部长、指导老师可以导出数据
            return "社长".equals(role) || "副社长".equals(role) || "部长".equals(role) || 
                   "副部长".equals(role) || "指导老师".equals(role);
        } else if (currentUser instanceof com.club.management.entity.SysUser) {
            com.club.management.entity.SysUser sysUser = (com.club.management.entity.SysUser) currentUser;
            String role = sysUser.getRole();
            return "社长".equals(role) || "副社长".equals(role) || "部长".equals(role) || 
                   "副部长".equals(role) || "指导老师".equals(role) || "系统管理员".equals(role);
        }
        return false;
    }
}
