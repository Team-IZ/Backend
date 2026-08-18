package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.ConsentCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 동의 문서의 <b>본문</b>과 그 본문의 무결성 해시를 기동 시 한 번 읽어 들고 있는다.
 *
 * <h2>왜 테이블이 아니라 리소스 파일인가</h2>
 *
 * <p>동의 문서 마스터 테이블이 스키마에 없고 테이블 신설도 하지 않기로 되어 있다. 문안을 파일로 두면
 * <b>개정이 커밋으로 남아</b> 별도 관리 테이블 없이 "언제 무엇을 고쳤는가"가 그대로 증적이 된다.
 * 문안만 바꿔야 할 때 코드를 건드리지 않아도 되는 것도 같은 이유에서 이점이다.
 *
 * <h2>왜 해시를 함께 계산하는가 — {@code evidence_hash}가 증적으로 기능하지 않았다</h2>
 *
 * <p>DDL은 {@code consent_record.evidence_hash}를 <b>"정책 본문·표시 정보의 무결성 검증 해시"</b>로
 * 정의한다. 그런데 실제 해시 재료에 <b>본문이 없었다</b> — 사용자 ID·토큰·코드·버전번호·동의 여부·
 * 시각·채널·요청 ID뿐이었다. 그러면 나중에 "1버전" 문안을 조용히 고쳐도 기존 기록의 해시가 그대로
 * 유효하다. 즉 <b>"이 사용자가 무슨 내용에 동의했는가"를 증명할 수 없다</b> — 동의 증적의 존재
 * 이유가 사라진다. 설계는 제대로 됐는데 구현이 의도를 따라가지 못한 경우다.
 *
 * <p>여기서 계산한 {@code documentHash}가 그 재료에 들어가면서 DDL 주석대로 동작하게 된다.
 * 문안 파일에서 한 글자만 바뀌어도 해시가 달라지므로, 이후 발급되는 동의 기록과 기존 기록이 서로
 * 다른 문서를 가리켰다는 사실이 드러난다.
 *
 * <h2>파일이 없으면 기동을 멈춘다</h2>
 *
 * <p>본문 없이 조용히 뜨면 화면은 빈 약관을 보여 주고 동의 기록은 다시 본문 없는 해시를 남긴다 —
 * 지금 고치고 있는 상태로 되돌아가는 것이다. {@code consent.policy-version}을 올렸다면 그 버전의
 * 파일 5개를 함께 넣어야 한다는 뜻이며, 빠지면 기동 실패로 즉시 드러난다.
 */
@Component
public class ConsentDocumentCatalog {

	/** {@code consent/PRIVACY_POLICY_v1.md} 형태. 코드와 버전이 곧 파일 이름이다. */
	private static final String LOCATION_FORMAT = "consent/%s_v%d.md";

	private final int policyVersion;
	private final Map<ConsentCode, ConsentDocument> documents;

	public ConsentDocumentCatalog(@Value("${consent.policy-version:1}") int policyVersion) {
		this.policyVersion = policyVersion;
		this.documents = load(policyVersion);
	}

	public int policyVersion() {
		return policyVersion;
	}

	public ConsentDocument find(ConsentCode code) {
		ConsentDocument document = documents.get(code);
		if (document == null) {
			throw new IllegalStateException("동의 문서를 찾을 수 없습니다: " + code);
		}
		return document;
	}

	private static Map<ConsentCode, ConsentDocument> load(int policyVersion) {
		Map<ConsentCode, ConsentDocument> loaded = new EnumMap<>(ConsentCode.class);
		for (ConsentCode code : ConsentCode.values()) {
			String location = LOCATION_FORMAT.formatted(code.name(), policyVersion);
			byte[] content = read(location);
			loaded.put(code, new ConsentDocument(
					new String(content, StandardCharsets.UTF_8),
					sha256Hex(content)
			));
		}
		return Map.copyOf(loaded);
	}

	private static byte[] read(String location) {
		try {
			return new ClassPathResource(location).getContentAsByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException(
					"동의 문서를 읽을 수 없습니다: " + location
							+ " — consent.policy-version 을 올렸다면 해당 버전의 문안 파일도 함께 넣어야 합니다.",
					exception);
		}
	}

	/**
	 * 파일 <b>바이트</b>를 그대로 해시한다. 문자열로 바꾼 뒤 해시하면 줄바꿈·인코딩 처리에 따라
	 * 같은 파일이 다른 값을 낼 수 있고, 그러면 "본문이 바뀌었다"는 신호를 믿을 수 없게 된다.
	 */
	private static String sha256Hex(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	/**
	 * @param body         화면에 그대로 보여 줄 문서 본문(Markdown)
	 * @param documentHash 본문의 SHA-256(hex 64자). 동의 기록의 {@code evidence_hash} 재료로 들어간다
	 */
	public record ConsentDocument(String body, String documentHash) {
	}
}
