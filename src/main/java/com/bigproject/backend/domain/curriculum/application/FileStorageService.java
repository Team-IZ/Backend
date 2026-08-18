package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
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
     * PDF의 매직 넘버. 형식 판정의 유일한 근거다.
     *
     * <p><b>확장자와 Content-Type은 보내는 쪽이 적는 값이라 근거가 되지 못한다.</b> 실행 파일의
     * 이름만 {@code 교안.pdf}로 바꾸고 {@code Content-Type: application/pdf}를 붙이면 종전 코드는
     * 그대로 저장했다 — 비어 있는지만 봤기 때문이다. 제출물 ZIP은 원래부터
     * {@code ZipInputStream}으로 실제 엔트리를 읽어 확인했으므로, 같은 원칙을 교안에도 맞춘다.
     */
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};

    /** DB에 남기는 값. 클라이언트가 보낸 Content-Type을 그대로 저장하면 위 검사가 무의미해진다. */
    private static final String PDF_CONTENT_TYPE = "application/pdf";

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

    /** 설정이 주입되지 않은 자리(직접 생성한 테스트 등)에서 쓰는 값. 50MB. */
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 52_428_800L;

    /**
     * 교안 파일 상한. <b>톰캣의 multipart 상한(기본 60MB)보다 작아야 한다.</b>
     *
     * <p>톰캣에서 먼저 걸리면 요청이 컨트롤러에 닿지 못해 도메인 코드 없는 500이 나간다 —
     * 화면은 "왜 거부됐는지"를 말할 근거가 없다. 앱이 먼저 판정하면
     * {@link CurriculumErrorCode#CURRICULUM_FILE_TOO_LARGE}로 나가 상한을 안내할 수 있다.
     * 제출물 ZIP({@code app.submission.max-zip-bytes})과 같은 이중 상한 구조다.
     *
     * <p><b>필드 기본값을 함께 둔다.</b> {@code @Value}는 스프링이 주입할 때만 채워지므로,
     * 이 클래스를 직접 {@code new} 하는 자리(단위 테스트)에서는 0이 되어 <b>모든 파일이 상한 초과</b>가
     * 된다. 실제로 기존 테스트가 그렇게 깨졌다 — 검사 하나를 넣으면서 무관한 검사를 무너뜨리는 자리다.
     */
    @Value("${curriculum.upload.max-bytes:52428800}")
    private long maxUploadBytes = DEFAULT_MAX_UPLOAD_BYTES;

    public StoredFile store(MultipartFile file) {
        validateSize(file);
        validatePdfSignature(file);

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
                // 클라이언트가 보낸 Content-Type이 아니라 서버가 확인한 형식을 남긴다.
                PDF_CONTENT_TYPE,
                file.getSize(),
                computeSha256(targetPath)
        );
    }

    private void validateSize(MultipartFile file) {
        if (file.getSize() > maxUploadBytes) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_TOO_LARGE);
        }
    }

    /**
     * 파일 <b>내용</b>의 앞부분이 PDF인지 본다.
     *
     * <p>스트림을 여기서 한 번 열고 저장할 때 다시 여는데, {@link MultipartFile#getInputStream()}은
     * 호출할 때마다 새 스트림을 준다(톰캣은 임시 파일을, 테스트의 {@code MockMultipartFile}은
     * 바이트 배열을 다시 연다). 그래서 여기서 읽은 5바이트가 저장 대상에서 빠지지 않는다.
     */
    private void validatePdfSignature(MultipartFile file) {
        byte[] header = new byte[PDF_SIGNATURE.length];
        try (InputStream input = file.getInputStream()) {
            if (input.readNBytes(header, 0, header.length) < header.length
                    || !Arrays.equals(header, PDF_SIGNATURE)) {
                throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_TYPE_INVALID);
            }
        } catch (IOException exception) {
            // 업로드 본문을 읽지 못한 것이라 저장 실패와 같은 상태다(사용자가 할 일은 재시도뿐).
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_STORE_FAILED);
        }
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
