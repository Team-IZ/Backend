package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 업로드된 교안 파일을 S3에 저장한다.
 *
 * <h2>22차 R2 — 저장 경로가 쓸 수 없으면 코드 없는 500이 났다 (로컬 디스크 저장이던 시절)</h2>
 *
 * <p>이 클래스는 원래 로컬 디스크에 저장했다. 저장 뿌리가 <b>상대 경로 {@code uploads/curricula}로
 * 박혀</b> 있었는데, 배포 환경의 파일시스템은 읽기 전용이라 {@code Files.createDirectories}가 그
 * 자리에서 실패했다. 그 예외가 {@code UncheckedIOException}으로 올라가 {@code ApiExceptionHandler}를
 * 거치지 못하고 코드 없는 500이 됐다 — 스펙에는 201·400·401만 있어 계약에도 없는 상태였다.
 *
 * <p>임시 디렉터리로 기본값을 돌려 그 문제는 넘겼지만, 근본 문제는 남아 있었다: 컨테이너가 재배포되면
 * (App Runner는 배포마다 새 컨테이너를 띄운다) 로컬에 쓴 파일이 통째로 사라진다. 실제로 이 문제로 교안
 * 2건이 파일을 잃어 {@code file:///C:/...} 경로만 DB에 남는 사고가 났다. S3로 옮기면서 이 클래스 자체가
 * 그 근본 원인을 없앤다 — 재배포와 무관하게 파일이 남는다.
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

    /** DB·S3에 남기는 값. 클라이언트가 보낸 Content-Type을 그대로 저장하면 위 검사가 무의미해진다. */
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final S3Client s3Client;
    private final String bucket;

    /** 설정이 주입되지 않은 자리(직접 생성한 테스트 등)에서 쓰는 값. 50MB. */
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 52_428_800L;

    /**
     * 교안 파일 상한. <b>톰캣의 multipart 상한(기본 60MB)보다 작아야 한다.</b>
     *
     * <p>톰캣에서 먼저 걸리면 요청이 컨트롤러에 닿지 못해 도메인 코드 없는 500이 나간다 —
     * 화면은 "왜 거부됐는지"를 말할 근거가 없다. 앱이 먼저 판정하면
     * {@link CurriculumErrorCode#CURRICULUM_FILE_TOO_LARGE}로 나가 상한을 안내할 수 있다.
     * 제출물 ZIP({@code app.submission.max-zip-bytes})과 같은 이중 상한 구조다.
     */
    private final long maxUploadBytes;

    public FileStorageService(
            S3Client s3Client,
            @Value("${curriculum.storage.bucket}") String bucket,
            @Value("${curriculum.upload.max-bytes:52428800}") long maxUploadBytes) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.maxUploadBytes = maxUploadBytes > 0 ? maxUploadBytes : DEFAULT_MAX_UPLOAD_BYTES;
    }

    public StoredFile store(MultipartFile file) {
        validateSize(file);
        validatePdfSignature(file);

        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String key = UUID.randomUUID() + (extension != null ? "." + extension : "");

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }

        // 업로드 스트림을 감싸 해시를 계산하면서 그대로 S3로 흘려보낸다 — 파일을 두 번 읽지 않는다.
        try (DigestInputStream digestStream = new DigestInputStream(file.getInputStream(), digest)) {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(PDF_CONTENT_TYPE)
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromInputStream(digestStream, file.getSize()));
        } catch (IOException | SdkException exception) {
            // 업로드 실패는 사용자가 할 수 있는 일이 재시도뿐이라 저장 실패와 같은 상태다.
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_STORE_FAILED);
        }

        return new StoredFile(
                "s3://" + bucket + "/" + key,
                file.getOriginalFilename(),
                // 클라이언트가 보낸 Content-Type이 아니라 서버가 확인한 형식을 남긴다.
                PDF_CONTENT_TYPE,
                file.getSize(),
                HexFormat.of().formatHex(digest.digest())
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
     * <p>스트림을 여기서 한 번 열고 업로드할 때 다시 여는데, {@link MultipartFile#getInputStream()}은
     * 호출할 때마다 새 스트림을 준다(톰캣은 임시 파일을, 테스트의 {@code MockMultipartFile}은
     * 바이트 배열을 다시 연다). 그래서 여기서 읽은 5바이트가 업로드 대상에서 빠지지 않는다.
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

    public record StoredFile(String fileUri, String originalFileName, String mimeType,
                             long fileSizeBytes, String contentHash) {
    }
}
