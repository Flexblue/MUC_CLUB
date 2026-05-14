package com.club.management.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.club.management.common.ErrorCode;
import com.club.management.common.Result;
import com.club.management.entity.Activity;
import com.club.management.entity.ActivityAttachment;
import com.club.management.entity.Member;
import com.club.management.mapper.ActivityAttachmentMapper;
import com.club.management.mapper.ActivityMapper;
import com.club.management.mapper.MemberMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 活动附件服务
 */
@Service
public class ActivityAttachmentService extends ServiceImpl<ActivityAttachmentMapper, ActivityAttachment> {

    @Autowired
    private ActivityMapper activityMapper;

    @Autowired
    private MemberMapper memberMapper;

    @Value("${file.upload.path}")
    private String uploadPath;
    
    @PostConstruct
    public void init() {
        // 确保上传路径是绝对路径
        if (uploadPath == null || uploadPath.isEmpty()) {
            uploadPath = System.getProperty("user.dir") + File.separator + "uploads";
        }
        // 移除开头的 ./
        if (uploadPath.startsWith("./")) {
            uploadPath = uploadPath.substring(2);
        }
        // 转为绝对路径
        File dir = new File(uploadPath);
        if (!dir.isAbsolute()) {
            uploadPath = dir.getAbsolutePath();
        }
        // 确保目录存在
        dir.mkdirs();
    }

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final List<String> ALLOWED_TYPES = Arrays.asList(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "jpg", "jpeg", "png", "gif", "zip", "rar"
    );

    /**
     * 上传附件
     */
    public Result<List<ActivityAttachment>> uploadAttachments(
            Long activityId,
            MultipartFile[] files,
            String description,
            Object currentUser
    ) {
        // 1. 验证活动是否存在
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return Result.businessError(ErrorCode.SYSTEM_ERROR, "活动不存在");
        }

        // 2. 验证权限
        Member member = getUserAsMember(currentUser);
        if (member == null) {
            return Result.businessError(ErrorCode.PERMISSION_DENIED, "用户信息不存在");
        }
        if (!canUpload(activity, member)) {
            return Result.businessError(ErrorCode.PERMISSION_DENIED, "无权限上传附件");
        }

        // 3. 验证文件
        for (MultipartFile file : files) {
            validateFile(file);
        }

        // 4. 保存文件并创建记录
        List<ActivityAttachment> attachments = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                ActivityAttachment attachment = saveAttachment(activityId, file, description, member.getId());
                attachments.add(attachment);
            } catch (IOException e) {
                return Result.businessError(ErrorCode.SYSTEM_ERROR, "文件保存失败：" + e.getMessage());
            }
        }

        return Result.success(attachments);
    }

    /**
     * 获取附件列表
     */
    public Result<List<ActivityAttachment>> getAttachmentList(Long activityId, Object currentUser) {
        List<ActivityAttachment> attachments = baseMapper.selectAttachmentListByActivityId(activityId);
        
        // 处理文件大小文本和删除权限
        Member member = getUserAsMember(currentUser);
        Activity activity = activityMapper.selectById(activityId);
        
        for (ActivityAttachment attachment : attachments) {
            attachment.setSizeText(formatFileSize(attachment.getFileSize()));
            // 根据权限设置是否可以删除
            attachment.setCanDelete(member != null && canDelete(activity, attachment, member));
        }
        
        return Result.success(attachments);
    }

    /**
     * 下载附件
     */
    public ResponseEntity<Resource> downloadAttachment(Long attachmentId, Object currentUser) {
        ActivityAttachment attachment = getById(attachmentId);
        if (attachment == null) {
            throw new RuntimeException("附件不存在");
        }

        // 验证权限
        Member member = getUserAsMember(currentUser);
        if (member == null) {
            throw new RuntimeException("用户信息不存在");
        }
        if (!canDownload(attachment.getActivityId(), member)) {
            throw new RuntimeException("无权限下载");
        }

        // 增加下载次数
        baseMapper.incrementDownloadCount(attachmentId);

        // 构建文件路径
        File file = new File(uploadPath, attachment.getFilePath());
        if (!file.exists()) {
            throw new RuntimeException("文件不存在");
        }

        Resource resource = new FileSystemResource(file);
        
        // 设置响应头
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, 
            "attachment; filename=\"" + attachment.getOriginalName() + "\"");
        headers.add(HttpHeaders.CONTENT_TYPE, "application/octet-stream");

        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .body(resource);
    }

    /**
     * 预览附件
     */
    public ResponseEntity<Resource> previewAttachment(Long attachmentId, Object currentUser) {
        ActivityAttachment attachment = getById(attachmentId);
        if (attachment == null) {
            throw new RuntimeException("附件不存在");
        }

        // 验证权限
        Member member = getUserAsMember(currentUser);
        if (member == null) {
            throw new RuntimeException("用户信息不存在");
        }
        if (!canDownload(attachment.getActivityId(), member)) {
            throw new RuntimeException("无权限预览");
        }

        // 只支持图片和PDF预览
        if (!isPreviewable(attachment.getFileExt())) {
            throw new RuntimeException("不支持预览此文件类型");
        }

        File file = new File(uploadPath, attachment.getFilePath());
        if (!file.exists()) {
            throw new RuntimeException("文件不存在");
        }

        Resource resource = new FileSystemResource(file);
        
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + attachment.getOriginalName() + "\"");
        
        MediaType mediaType = getMediaType(attachment.getFileExt());
        headers.setContentType(mediaType);

        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .body(resource);
    }

    /**
     * 删除附件
     */
    public Result<String> deleteAttachment(Long attachmentId, Object currentUser) {
        ActivityAttachment attachment = getById(attachmentId);
        if (attachment == null) {
            return Result.businessError(ErrorCode.SYSTEM_ERROR, "附件不存在");
        }

        // 验证权限
        Member member = getUserAsMember(currentUser);
        if (member == null) {
            return Result.businessError(ErrorCode.PERMISSION_DENIED, "用户信息不存在");
        }
        Activity activity = activityMapper.selectById(attachment.getActivityId());
        if (!canDelete(activity, attachment, member)) {
            return Result.businessError(ErrorCode.PERMISSION_DENIED, "无权限删除");
        }

        // 删除文件
        File file = new File(uploadPath, attachment.getFilePath());
        if (file.exists()) {
            file.delete();
        }

        // 删除数据库记录
        removeById(attachmentId);

        return Result.success("删除成功");
    }

    /**
     * 更新附件信息
     */
    public Result<String> updateAttachment(Long attachmentId, ActivityAttachment attachment, Object currentUser) {
        ActivityAttachment existing = getById(attachmentId);
        if (existing == null) {
            return Result.businessError(ErrorCode.SYSTEM_ERROR, "附件不存在");
        }

        // 验证权限
        Member member = getUserAsMember(currentUser);
        if (member == null) {
            return Result.businessError(ErrorCode.PERMISSION_DENIED, "用户信息不存在");
        }
        Activity activity = activityMapper.selectById(existing.getActivityId());
        if (!canUpload(activity, member)) {
            return Result.businessError(ErrorCode.PERMISSION_DENIED, "无权限修改");
        }

        existing.setDescription(attachment.getDescription());
        updateById(existing);

        return Result.success("更新成功");
    }

    /**
     * 保存附件
     */
    private ActivityAttachment saveAttachment(Long activityId, MultipartFile file, String description, Long uploadBy) throws IOException {
        String relativePath = generateFilePath(activityId, file.getOriginalFilename());
        File destFile = new File(uploadPath, relativePath);
        destFile.getParentFile().mkdirs();
        file.transferTo(destFile);

        ActivityAttachment attachment = new ActivityAttachment();
        attachment.setActivityId(activityId);
        attachment.setFileName(destFile.getName());
        attachment.setOriginalName(file.getOriginalFilename());
        attachment.setFilePath(relativePath);
        attachment.setFileSize(file.getSize());
        attachment.setFileType(file.getContentType());
        attachment.setFileExt(getFileExtension(file.getOriginalFilename()));
        attachment.setUploadBy(uploadBy);
        attachment.setUploadTime(LocalDateTime.now());
        attachment.setDescription(description);
        attachment.setDownloadCount(0);

        save(attachment);
        return attachment;
    }

    /**
     * 验证文件
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("文件不能为空");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new RuntimeException("文件大小超过10MB限制");
        }

        String ext = getFileExtension(file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(ext.toLowerCase())) {
            throw new RuntimeException("不支持的文件类型：" + ext);
        }
    }

    /**
     * 生成文件路径
     */
    private String generateFilePath(Long activityId, String originalName) {
        LocalDate now = LocalDate.now();
        String ext = getFileExtension(originalName);
        String fileName = UUID.randomUUID().toString() + "." + ext;
        return String.format("activity/%d/%d/%02d/%s",
            activityId, now.getYear(), now.getMonthValue(), fileName);
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot + 1);
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + "B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2fKB", size / 1024.0);
        } else {
            return String.format("%.2fMB", size / (1024.0 * 1024.0));
        }
    }

    /**
     * 判断是否可以预览
     */
    private boolean isPreviewable(String ext) {
        List<String> previewableTypes = Arrays.asList("jpg", "jpeg", "png", "gif", "pdf");
        return previewableTypes.contains(ext.toLowerCase());
    }

    /**
     * 获取MediaType
     */
    private MediaType getMediaType(String ext) {
        switch (ext.toLowerCase()) {
            case "jpg":
            case "jpeg":
                return MediaType.IMAGE_JPEG;
            case "png":
                return MediaType.IMAGE_PNG;
            case "gif":
                return MediaType.IMAGE_GIF;
            case "pdf":
                return MediaType.APPLICATION_PDF;
            default:
                return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * 检查是否有上传权限
     */
    private boolean canUpload(Activity activity, Member user) {
        // 社长、副社长可以上传所有活动的附件
        if ("社长".equals(user.getRole()) || "副社长".equals(user.getRole())) {
            return true;
        }
        
        // 部长只能上传自己创建的活动的附件
        if ("部长".equals(user.getRole()) && activity.getCreateBy().equals(user.getId())) {
            return true;
        }
        
        return false;
    }

    /**
     * 检查是否有下载权限
     */
    private boolean canDownload(Long activityId, Member user) {
        // 有权限查看活动的人都可以下载附件
        return true;
    }

    /**
     * 检查是否有删除权限
     */
    private boolean canDelete(Activity activity, ActivityAttachment attachment, Member user) {
        // 社长、副社长可以删除所有附件
        if ("社长".equals(user.getRole()) || "副社长".equals(user.getRole())) {
            return true;
        }
        
        // 上传者可以删除自己上传的附件
        if (attachment.getUploadBy().equals(user.getId())) {
            return true;
        }
        
        // 部长可以删除自己创建的活动的附件
        if ("部长".equals(user.getRole()) && activity.getCreateBy().equals(user.getId())) {
            return true;
        }
        
        return false;
    }

    /**
     * 将currentUser转换为Member对象
     */
    private Member getUserAsMember(Object currentUser) {
        if (currentUser instanceof Member) {
            return (Member) currentUser;
        } else if (currentUser instanceof com.club.management.entity.SysUser) {
            com.club.management.entity.SysUser sysUser = (com.club.management.entity.SysUser) currentUser;
            Member member = memberMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Member>()
                    .eq("stu_id", sysUser.getStuId())
            );
            return member;
        }
        return null;
    }
}

