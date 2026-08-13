package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 업로드된 교안 파일을 저장한다.
 *
 * <p>⚠ 임시: 로컬 디스크 저장이며 나중에 S3로 교체할 것이다.
 *
 * <h2>22차 R2 — 저장 경로가 쓸 수 없으면 코드 없는 500이 났다</h2>
 *
 * <p>저장 뿌리가 <b>상대 경로 {@code uploads/curricula}로 박혀</b> 있었다. 프로세스의 작업
 * 디렉터리에 쓰겠다는 뜻인데, <b>배포 환경의 파일시스템은 읽기 전용</b>이라
 * {@code Files.createDirectories}가 그 자리에서 실패한다. 그 예외가
 * {@code UncheckedIOException}으로 올라가 {@code ApiExceptionHandler}를 거치지 못하고
 * 코드 없는 500이 됐다 — 스펙에는 201·400·401만 있어 계약에도 없는 상태였다.
 *
 * <p><b>파일 크기와 무관하다.</b> 50KB든 4MB든 첫 줄에서 같은 이유로 터진다.
 *
 * <p>두 가지를 고친다. 뿌리를 설정으로 빼고 기본값을 <b>쓸 수 있는 임시 디렉터리</b>로 두어
 * 어디서 돌든 저장이 되게 하고, 그래도 실패하면 {@link CurriculumErrorCode#CURRICULUM_FILE_STORE_FAILED}로
 * 감싸 화면이 재시도를 안내할 근거를 준다.
 */
@Component
public class FileStorageService {

    /**
     * 저장 뿌리. {@code curriculum.storage.root}로 덮어쓴다.
     *
     * <p>기본값을 임시 디렉터리로 두는 이유는 <b>어느 환경에서도 쓸 수 있는 유일한 자리</b>이기
     * 때문이다. 컨테이너·서버리스 환경은 애플리케이션 디렉터리가 읽기 전용인 경우가 흔하다.
     *
     * <p>임시 디렉터리는 재시작하면 비워질 수 있다 — 등록은 성공해도 나중에 분석이 파일을 못
     * 읽을 수 있다는 뜻이다. 영구 보관이 필요한 환경은 이 설정으로 <b>지속 볼륨을 가리켜야</b>
     * 하며, 근본 해결은 S3로 옮기는 것이다.
     */
    @Value("${curriculum.storage.root:#{systemProperties['java.io.tmpdir']}/iz-get/curricula}")
    private String storageRoot;

    public StoredFile store(MultipartFile file) {
        Path targetPath;
        try {
            Path root = Paths.get(storageRoot);
            Files.createDirectories(root);

            String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
            String storedFileName = UUID.randomUUID() + (extension != null ? "." + extension : "");
            targetPath = root.resolve(storedFileName);

            Files.copy(file.getInputStream(), targetPath);
        } catch (IOException exception) {
            // 저장 자리가 읽기 전용이거나 가득 찼다. 사용자가 할 수 있는 일이 재시도뿐이라 503이다.
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_STORE_FAILED);
        }

        return new StoredFile(
                targetPath.toUri().toString(),
                file.getOriginalFilename(),
                file.getContentType() != null ? file.getContentType() : "application/octet-stream",
                file.getSize(),
                computeSha256(targetPath)
        );
    }

    private String computeSha256(Path path) {
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
        } catch (IOException e) {
            // 방금 쓴 파일을 되읽지 못한 것이라 저장 실패와 같은 상태다.
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_STORE_FAILED);
        }
    }

    public record StoredFile(String fileUri, String originalFileName, String mimeType,
                             long fileSizeBytes, String contentHash) {
    }
}
