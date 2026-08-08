package com.bigproject.backend.domain.curriculum.application;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@Component
public class FileStorageService {

    // ⚠ 임시: 로컬 디스크 저장. 나중에 S3 등 외부 스토리지로 교체할 것.
    private static final String STORAGE_ROOT = "uploads/curricula";

    public StoredFile store(MultipartFile file) {
        try {
            Path root = Paths.get(STORAGE_ROOT);
            Files.createDirectories(root);

            String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
            String storedFileName = UUID.randomUUID() + (extension != null ? "." + extension : "");
            Path targetPath = root.resolve(storedFileName);

            Files.copy(file.getInputStream(), targetPath);

            String contentHash = computeSha256(targetPath);

            return new StoredFile(
                    targetPath.toUri().toString(),
                    file.getOriginalFilename(),
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream",
                    file.getSize(),
                    contentHash
            );
        } catch (IOException e) {
            throw new UncheckedIOException("파일 저장에 실패했습니다.", e);
        }
    }

    private String computeSha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    public record StoredFile(String fileUri, String originalFileName, String mimeType,
                             long fileSizeBytes, String contentHash) {
    }
}