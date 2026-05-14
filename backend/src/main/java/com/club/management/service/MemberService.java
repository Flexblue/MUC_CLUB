package com.club.management.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.club.management.entity.Dept;
import com.club.management.entity.Member;
import com.club.management.entity.SysUser;
import com.club.management.mapper.DeptMapper;
import com.club.management.mapper.MemberMapper;
import com.club.management.mapper.SysUserMapper;
import com.club.management.common.Result;
import com.club.management.common.ErrorCode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.club.management.common.ErrorCode.*;

/**
 * 社员服务
 */
@Service
public class MemberService extends ServiceImpl<MemberMapper, Member> {

    @Autowired
    private DeptMapper deptMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private com.club.management.mapper.ActivityMemberMapper activityMemberMapper;

    @Autowired
    private com.club.management.mapper.ActivityApproverMapper activityApproverMapper;

    /**
     * 分页查询社员
     */
    public Result<Page<Member>> getMemberPage(int page, int size, String name, 
                                            String stuId, Long deptId, String role, String sortField, String sortOrder, Object currentUser) {
        // 权限控制
        String userRole = null;
        Long userId = null;
        
        if (currentUser instanceof com.club.management.entity.Member) {
            com.club.management.entity.Member member = (com.club.management.entity.Member) currentUser;
            userRole = member.getRole();
            userId = member.getId();
        } else if (currentUser instanceof com.club.management.entity.SysUser) {
            com.club.management.entity.SysUser sysUser = (com.club.management.entity.SysUser) currentUser;
            userRole = sysUser.getRole();
            userId = sysUser.getId();
        }
        
        // 干事只能查看自己的档案
        if ("干事".equals(userRole)) {
            Page<Member> pageParam = new Page<>(page, size);
            QueryWrapper<Member> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("id", userId);
            Page<Member> result = page(pageParam, queryWrapper);
            return Result.success(result);
        }
        
        // 副部长只能查看本部门成员
        if ("副部长".equals(userRole)) {
            // 获取当前用户的部门ID
            com.club.management.entity.Member currentMember = getById(userId);
            if (currentMember != null && currentMember.getDeptId() != null) {
                deptId = currentMember.getDeptId(); // 强制限制为本部门
            }
        }
        
        // 部长只能查看本部门成员
        if ("部长".equals(userRole)) {
            // 获取当前用户的部门ID
            com.club.management.entity.Member currentMember = getById(userId);
            if (currentMember != null && currentMember.getDeptId() != null) {
                deptId = currentMember.getDeptId(); // 强制限制为本部门
            }
        }
        
        // 指导老师可以查看所有成员
        if ("指导老师".equals(userRole)) {
            // 不限制deptId，可以查看所有成员
        }
        
        Page<Member> pageParam = new Page<>(page, size);
        Page<Member> result = (Page<Member>) baseMapper.selectMemberPage(pageParam, name, stuId, deptId, role, sortField, sortOrder);
        return Result.success(result);
    }

    /**
     * 添加社员
     */
    public Result<String> addMember(Member member, Object currentUser) {
        // 权限检查
        if (!hasPermission(currentUser, "add")) {
            return Result.businessError(ErrorCode.FORBIDDEN, "权限不足");
        }
        
        // 参数验证
        if (member.getStuId() == null || member.getStuId().trim().isEmpty()) {
            return Result.businessError(ErrorCode.BAD_REQUEST, "学号不能为空");
        }
        if (member.getName() == null || member.getName().trim().isEmpty()) {
            return Result.businessError(ErrorCode.BAD_REQUEST, "姓名不能为空");
        }
        if (member.getPhone() == null || member.getPhone().trim().isEmpty()) {
            return Result.businessError(ErrorCode.BAD_REQUEST, "手机号不能为空");
        }
        if (member.getEmail() == null || member.getEmail().trim().isEmpty()) {
            return Result.businessError(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        
        // 验证学号格式
        if (!member.getStuId().matches("\\d{8}")) {
            return Result.businessError(ErrorCode.BAD_REQUEST, "学号必须为8位数字");
        }
        
        // 验证手机号格式
        if (!member.getPhone().matches("^1[3-9]\\d{9}$")) {
            return Result.businessError(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }
        
        // 验证邮箱格式
        if (!member.getEmail().matches("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")) {
            return Result.businessError(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        
        // 检查学号是否已存在
        Member existingMember = getOne(new QueryWrapper<Member>().eq("stu_id", member.getStuId()));
        if (existingMember != null) {
            return Result.businessError(ErrorCode.STUDENT_ID_EXISTS, "学号已存在");
        }
        
        // 生成初始密码（学号后6位）
        String stuId = member.getStuId();
        if (stuId != null && stuId.length() == 8) {
            String initialPassword = stuId.substring(2); // 取学号后6位
            member.setPassword(passwordEncoder.encode(initialPassword));
        }
        
        // 社长、副社长、指导老师没有部门
        if ("社长".equals(member.getRole()) || "副社长".equals(member.getRole()) || "指导老师".equals(member.getRole())) {
            member.setDeptId(null);
        }
        
        // 指导老师没有专业和年级
        if ("指导老师".equals(member.getRole())) {
            member.setMajor(null);
            member.setGrade(null);
        }
        
        // 保存社员记录
        save(member);
        
        // 创建系统用户记录（用于登录）
        SysUser sysUser = new SysUser();
        sysUser.setStuId(member.getStuId());
        sysUser.setName(member.getName());
        sysUser.setRole(member.getRole());
        sysUser.setPassword(member.getPassword());
        sysUser.setStatus(1); // 启用状态
        sysUser.setCreateBy(getUserId(currentUser));
        sysUserMapper.insert(sysUser);
        
        return Result.success("添加社员成功");
    }

    /**
     * 更新社员
     */
    public Result<String> updateMember(Member member, Object currentUser) {
        // 权限检查
        if (!hasPermission(currentUser, "edit", member.getId())) {
            return Result.businessError(ErrorCode.FORBIDDEN, "权限不足");
        }
        
        // 检查部长是否试图修改部门/角色字段（根据SDS.md，部长不能修改成员的部门/角色）
        String userRole = getUserRole(currentUser);
        if ("部长".equals(userRole)) {
            Member originalMember = getById(member.getId());
            if (originalMember != null) {
                // 检查部门ID是否被修改
                if (!Objects.equals(originalMember.getDeptId(), member.getDeptId())) {
                    return Result.businessError(ErrorCode.FORBIDDEN, "部长不能修改成员的部门");
                }
                // 检查角色是否被修改
                if (!Objects.equals(originalMember.getRole(), member.getRole())) {
                    return Result.businessError(ErrorCode.FORBIDDEN, "部长不能修改成员的角色");
                }
            }
        }
        
        // 检查指导老师是否试图维护社员档案（根据SDS.md，指导老师不能维护社员档案）
        if ("指导老师".equals(userRole)) {
            return Result.businessError(ErrorCode.FORBIDDEN, "指导老师不能维护社员档案");
        }
        
        // 检查学号是否已存在（排除自己）
        Member existingMember = getOne(new QueryWrapper<Member>()
                .eq("stu_id", member.getStuId())
                .ne("id", member.getId()));
        if (existingMember != null) {
            return Result.businessError(ErrorCode.STUDENT_ID_EXISTS, "学号已存在");
        }

        // 社长、副社长、指导老师没有部门
        if ("社长".equals(member.getRole()) || "副社长".equals(member.getRole()) || "指导老师".equals(member.getRole())) {
            member.setDeptId(null);
        }
        
        // 指导老师没有专业和年级
        if ("指导老师".equals(member.getRole())) {
            member.setMajor(null);
            member.setGrade(null);
        }

        updateById(member);
        return Result.success("更新社员成功");
    }

    /**
     * 删除社员
     */
    public Result<String> deleteMember(Long id, Object currentUser) {
        // 权限检查
        if (!hasPermission(currentUser, "delete")) {
            return Result.businessError(ErrorCode.FORBIDDEN, "权限不足");
        }
        
        try {
            // 获取社员信息
            Member member = getById(id);
            if (member == null) {
                return Result.businessError(ErrorCode.NOT_FOUND, "社员不存在");
            }
            
            // 删除活动参与记录
            activityMemberMapper.delete(new QueryWrapper<com.club.management.entity.ActivityMember>()
                .eq("member_id", id));
            
            // 删除活动审批记录
            activityApproverMapper.delete(new QueryWrapper<com.club.management.entity.ActivityApprover>()
                .eq("user_id", id));
            
            // 删除系统用户记录
            sysUserMapper.delete(new QueryWrapper<SysUser>().eq("stu_id", member.getStuId()));
            
            // 删除社员记录
            removeById(id);
            
            return Result.success("删除社员成功");
        } catch (Exception e) {
            e.printStackTrace();
            return Result.businessError(ErrorCode.SYSTEM_ERROR, "删除社员失败: " + e.getMessage());
        }
    }

    /**
     * 根据ID获取社员详情
     */
    public Result<Member> getMemberById(Long id) {
        Member member = getById(id);
        if (member == null) {
            return Result.businessError(ErrorCode.SYSTEM_ERROR, "社员不存在");
        }
        
        // 设置部门名称
        if (member.getDeptId() != null) {
            Dept dept = deptMapper.selectById(member.getDeptId());
            if (dept != null) {
                member.setDeptName(dept.getName());
            }
        }
        
        return Result.success(member);
    }

    /**
     * 获取社员参与的活动
     */
    public Result<List<Map<String, Object>>> getMemberActivities(Long memberId) {
        List<Map<String, Object>> activities = baseMapper.selectMemberActivities(memberId);
        return Result.success(activities);
    }

    /**
     * 下载导入模板
     */
    public void downloadTemplate(HttpServletResponse response) {
        try {
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("社员导入模板");
            
            // 创建表头
            Row headerRow = sheet.createRow(0);
            String[] headers = {"学号", "姓名", "性别", "学院", "专业", "年级", "手机", "邮箱", "入社时间", "部门ID", "角色"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }
            
            // 添加示例数据
            String[][] sampleData = {
                {"2021001", "张三", "男", "计算机学院", "软件工程", "大一", "13800138001", "zhangsan@example.com", "2021-09-01", "1", "干事"},
                {"2021002", "李四", "女", "计算机学院", "计算机科学与技术", "大二", "13800138002", "lisi@example.com", "2021-09-01", "2", "干事"},
                {"2021003", "王五", "男", "信息学院", "网络工程", "大三", "13800138003", "wangwu@example.com", "2021-09-01", "3", "干事"}
            };
            
            for (int i = 0; i < sampleData.length; i++) {
                Row dataRow = sheet.createRow(i + 1);
                for (int j = 0; j < sampleData[i].length; j++) {
                    Cell cell = dataRow.createCell(j);
                    cell.setCellValue(sampleData[i][j]);
                }
            }
            
            // 添加说明行
            Row noteRow = sheet.createRow(sampleData.length + 2);
            Cell noteCell = noteRow.createCell(0);
            noteCell.setCellValue("说明：");
            
            Row noteRow2 = sheet.createRow(sampleData.length + 3);
            Cell noteCell2 = noteRow2.createCell(0);
            noteCell2.setCellValue("1. 学号必须唯一，不能重复");
            
            Row noteRow3 = sheet.createRow(sampleData.length + 4);
            Cell noteCell3 = noteRow3.createCell(0);
            noteCell3.setCellValue("2. 性别只能填写：男 或 女");
            
            Row noteRow4 = sheet.createRow(sampleData.length + 5);
            Cell noteCell4 = noteRow4.createCell(0);
            noteCell4.setCellValue("3. 部门ID：1-宣传部，2-技术部，3-培训服务部，4-办公室");
            
            Row noteRow5 = sheet.createRow(sampleData.length + 6);
            Cell noteCell5 = noteRow5.createCell(0);
            noteCell5.setCellValue("4. 角色：干事、副部长、部长、副社长、社长、指导老师");
            
            Row noteRow6 = sheet.createRow(sampleData.length + 7);
            Cell noteCell6 = noteRow6.createCell(0);
            noteCell6.setCellValue("5. 入社时间格式：YYYY-MM-DD");
            
            // 自动调整列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            // 设置响应头
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=member_template.xlsx");
            
            workbook.write(response.getOutputStream());
            workbook.close();
        } catch (IOException e) {
            throw new RuntimeException("生成模板失败", e);
        }
    }

    /**
     * 预览导入数据
     */
    public Result<List<Map<String, Object>>> previewImport(MultipartFile file) {
        try {
            List<Map<String, Object>> previewData = new ArrayList<>();
            Workbook workbook = WorkbookFactory.create(file.getInputStream());
            Sheet sheet = workbook.getSheetAt(0);
            
            // 跳过表头，从第二行开始读取
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                
                Map<String, Object> rowData = new HashMap<>();
                String error = validateRow(row, i + 1);
                
                rowData.put("rowIndex", i + 1);
                rowData.put("stuId", getCellValue(row.getCell(0)));
                rowData.put("name", getCellValue(row.getCell(1)));
                rowData.put("gender", getCellValue(row.getCell(2)));
                rowData.put("college", getCellValue(row.getCell(3)));
                rowData.put("major", getCellValue(row.getCell(4)));
                rowData.put("grade", getCellValue(row.getCell(5)));
                rowData.put("phone", getCellValue(row.getCell(6)));
                rowData.put("email", getCellValue(row.getCell(7)));
                rowData.put("joinDate", getCellValue(row.getCell(8)));
                rowData.put("deptId", getCellValue(row.getCell(9)));
                rowData.put("role", getCellValue(row.getCell(10)));
                rowData.put("error", error);
                rowData.put("valid", error == null);
                
                previewData.add(rowData);
            }
            
            workbook.close();
            return Result.success(previewData);
        } catch (Exception e) {
            return Result.businessError(SYSTEM_ERROR, "文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 确认导入数据
     */
    public Result<String> confirmImport(List<Map<String, Object>> importData) {
        try {
            int successCount = 0;
            int errorCount = 0;
            
            for (Map<String, Object> rowData : importData) {
                if (!Boolean.TRUE.equals(rowData.get("valid"))) {
                    errorCount++;
                    continue;
                }
                
                try {
                    Member member = new Member();
                    member.setStuId((String) rowData.get("stuId"));
                    member.setName((String) rowData.get("name"));
                    member.setGender((String) rowData.get("gender"));
                    member.setCollege((String) rowData.get("college"));
                    member.setMajor((String) rowData.get("major"));
                    member.setGrade((String) rowData.get("grade"));
                    member.setPhone((String) rowData.get("phone"));
                    member.setEmail((String) rowData.get("email"));
                    
                    // 解析入社时间
                    String joinDateStr = (String) rowData.get("joinDate");
                    if (joinDateStr != null && !joinDateStr.isEmpty()) {
                        member.setJoinDate(LocalDate.parse(joinDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                    }
                    
                    // 解析部门ID
                    String deptIdStr = (String) rowData.get("deptId");
                    String role = (String) rowData.get("role");
                    
                    // 社长、副社长、指导老师没有部门
                    if ("社长".equals(role) || "副社长".equals(role) || "指导老师".equals(role)) {
                        member.setDeptId(null);
                    } else if (deptIdStr != null && !deptIdStr.isEmpty()) {
                        member.setDeptId(Long.parseLong(deptIdStr));
                    }
                    
                    // 指导老师没有专业和年级
                    if ("指导老师".equals(role)) {
                        member.setMajor(null);
                        member.setGrade(null);
                    }
                    
                    member.setRole(role);
                    member.setCreateBy(1L); // 默认创建人
                    
                    // 生成初始密码（学号后6位）
                    String stuId = member.getStuId();
                    if (stuId != null && stuId.length() == 8) {
                        String initialPassword = stuId.substring(2); // 取学号后6位
                        member.setPassword(passwordEncoder.encode(initialPassword));
                    }
                    
                    // 检查学号是否已存在
                    Member existingMember = getOne(new QueryWrapper<Member>().eq("stu_id", member.getStuId()));
                    SysUser existingSysUser = sysUserMapper.selectOne(new QueryWrapper<SysUser>().eq("stu_id", member.getStuId()));
                    
                    if (existingMember == null && existingSysUser == null) {
                        // 保存社员记录
                        save(member);
                        
                        // 创建系统用户记录（用于登录）
                        SysUser sysUser = new SysUser();
                        sysUser.setStuId(member.getStuId());
                        sysUser.setName(member.getName());
                        sysUser.setRole(member.getRole());
                        sysUser.setPassword(member.getPassword());
                        sysUser.setStatus(1); // 启用状态
                        sysUser.setCreateBy(1L);
                        sysUserMapper.insert(sysUser);
                        
                        successCount++;
                    } else {
                        errorCount++;
                    }
                } catch (Exception e) {
                    errorCount++;
                }
            }
            
            return Result.success(String.format("导入完成，成功：%d条，失败：%d条", successCount, errorCount));
        } catch (Exception e) {
            return Result.businessError(SYSTEM_ERROR, "导入失败: " + e.getMessage());
        }
    }

    /**
     * 验证行数据
     */
    private String validateRow(Row row, int rowNum) {
        // 检查必填字段
        if (getCellValue(row.getCell(0)) == null || getCellValue(row.getCell(0)).isEmpty()) {
            return "学号不能为空";
        }
        if (getCellValue(row.getCell(1)) == null || getCellValue(row.getCell(1)).isEmpty()) {
            return "姓名不能为空";
        }
        
        // 检查学号是否已存在
        String stuId = getCellValue(row.getCell(0));
        if (stuId != null) {
            Member existingMember = getOne(new QueryWrapper<Member>().eq("stu_id", stuId));
            if (existingMember != null) {
                return "学号已存在";
            }
        }
        
        // 检查入社时间格式
        String joinDateStr = getCellValue(row.getCell(8));
        if (joinDateStr != null && !joinDateStr.isEmpty()) {
            try {
                LocalDate.parse(joinDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (Exception e) {
                return "入社时间格式错误，应为yyyy-MM-dd";
            }
        }
        
        return null;
    }

    /**
     * 获取单元格值
     */
    private String getCellValue(Cell cell) {
        if (cell == null) return null;
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
                } else {
                    return String.valueOf((long) cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return null;
        }
    }
    
    /**
     * 权限检查方法
     */
    private boolean hasPermission(Object currentUser, String action) {
        return hasPermission(currentUser, action, null);
    }
    
    /**
     * 权限检查方法
     */
    private boolean hasPermission(Object currentUser, String action, Long targetMemberId) {
        String userRole = null;
        Long userId = null;
        
        if (currentUser instanceof com.club.management.entity.Member) {
            com.club.management.entity.Member member = (com.club.management.entity.Member) currentUser;
            userRole = member.getRole();
            userId = member.getId();
        } else if (currentUser instanceof com.club.management.entity.SysUser) {
            com.club.management.entity.SysUser sysUser = (com.club.management.entity.SysUser) currentUser;
            userRole = sysUser.getRole();
            userId = sysUser.getId();
        }
        
        if (userRole == null) {
            return false;
        }
        
        switch (action) {
            case "add":
                // 只有社长、副社长可以添加成员（根据SDS.md，指导老师不能维护社员档案）
                return "社长".equals(userRole) || "副社长".equals(userRole);
                
            case "edit":
                if (targetMemberId == null) {
                    return false;
                }
                // 干事只能编辑自己的档案
                if ("干事".equals(userRole)) {
                    return targetMemberId.equals(userId);
                }
                // 副部长不能编辑档案（根据SDS.md）
                if ("副部长".equals(userRole)) {
                    return false;
                }
                // 指导老师不能编辑档案（根据SDS.md）
                if ("指导老师".equals(userRole)) {
                    return false;
                }
                // 部长只能编辑本部门成员
                if ("部长".equals(userRole)) {
                    com.club.management.entity.Member currentMember = getById(userId);
                    com.club.management.entity.Member targetMember = getById(targetMemberId);
                    if (currentMember != null && targetMember != null) {
                        return currentMember.getDeptId() != null && 
                               currentMember.getDeptId().equals(targetMember.getDeptId());
                    }
                }
                // 社长、副社长可以编辑所有成员
                return "社长".equals(userRole) || "副社长".equals(userRole);
                
            case "delete":
                // 只有社长、副社长可以删除成员
                return "社长".equals(userRole) || "副社长".equals(userRole);
                
            default:
                return false;
        }
    }
    
    
    /**
     * 获取用户角色
     */
    private String getUserRole(Object currentUser) {
        if (currentUser instanceof com.club.management.entity.Member) {
            com.club.management.entity.Member member = (com.club.management.entity.Member) currentUser;
            return member.getRole();
        } else if (currentUser instanceof com.club.management.entity.SysUser) {
            com.club.management.entity.SysUser sysUser = (com.club.management.entity.SysUser) currentUser;
            return sysUser.getRole();
        }
        return null;
    }
    
    /**
     * 获取用户ID
     */
    private Long getUserId(Object currentUser) {
        if (currentUser instanceof com.club.management.entity.Member) {
            com.club.management.entity.Member member = (com.club.management.entity.Member) currentUser;
            return member.getId();
        } else if (currentUser instanceof com.club.management.entity.SysUser) {
            com.club.management.entity.SysUser sysUser = (com.club.management.entity.SysUser) currentUser;
            return sysUser.getId();
        }
        return null;
    }
    
    /**
     * 重置密码（管理员功能）
     * 将密码重置为学号后6位
     */
    public Result<String> resetPassword(Long memberId, Object currentUser) {
        // 权限检查：只有社长和副社长可以重置密码
        String userRole = getUserRole(currentUser);
        if (!"社长".equals(userRole) && !"副社长".equals(userRole)) {
            return Result.businessError(ErrorCode.FORBIDDEN, "只有社长和副社长可以重置密码");
        }
        
        // 获取成员信息
        Member member = getById(memberId);
        if (member == null) {
            return Result.businessError(ErrorCode.NOT_FOUND, "成员不存在");
        }
        
        // 获取学号后6位作为新密码
        String stuId = member.getStuId();
        if (stuId == null || stuId.length() < 6) {
            return Result.businessError(ErrorCode.BAD_REQUEST, "学号格式不正确");
        }
        
        String newPassword = stuId.substring(stuId.length() - 6);
        
        // 加密密码
        String hashedPassword = passwordEncoder.encode(newPassword);
        
        // 更新密码
        member.setPassword(hashedPassword);
        updateById(member);
        
        return Result.success("密码已重置为学号后6位：" + newPassword);
    }
}
