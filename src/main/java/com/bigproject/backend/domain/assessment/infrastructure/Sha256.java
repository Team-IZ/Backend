package com.bigproject.backend.domain.assessment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * AI 계약이 요구하는 {@code contentHash}(코드 파일 전체의 sha256 hex 64자)를 만든다.
 *
 * <p>저장된 {@code submission.code_snippets} 원소에 해시 필드가 없어(실측) 보낼 때 계산한다.
 * 대상은 AI 정의와 같은 "codeSnippet 전체"라 값이 어긋나지 않는다.
 */
final class Sha256 {

	private Sha256() {
	}

	static String hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			// SHA-256은 JDK 표준이라 여기 오지 않는다. 체크 예외를 밖으로 흘려 호출부를 더럽히지 않는다.
			throw new IllegalStateException("SHA-256을 쓸 수 없다", exception);
		}
	}
}
