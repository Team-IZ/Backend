package com.bigproject.backend.domain.reporting.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 리포트 생성이 다루는 JSON 페이로드의 직렬화와 해시.
 *
 * <p>해시가 필요한 자리가 넷이다 — {@code report_generation_run.request_fingerprint},
 * {@code report_generation_item.request_payload_hash}·{@code response_payload_hash},
 * {@code report_snapshot.payload_hash}. 앞의 셋은 DB CHECK가 <b>소문자 hex 64자</b>를 정규식으로
 * 강제하므로({@code ~ '^[0-9a-f]{64}$'}) 형식이 어긋나면 INSERT 자체가 막힌다.
 *
 * <p>공용 유틸을 만들지 않고 이 도메인에 두는 이유: 코드베이스에 SHA-256 계산이 이미 다섯 군데
 * 흩어져 있지만({@code RefreshTokenHasher}·{@code OneTimeTokenHasher} 등) 각자 인코딩과 출력 형식이
 * 다르다. 하나로 합치는 것은 이 작업의 범위가 아니고, 여기서 필요한 것은 "hex 64자"라는 형식
 * 요구를 지키는 것뿐이다.
 */
final class ReportPayloads {

	private ReportPayloads() {
	}

	/**
	 * 객체를 JSON 문자열로 만든다.
	 *
	 * <p>같은 입력이면 같은 문자열이 나와야 한다 — 이 값이 곧 지문의 입력이라, 순서가 흔들리면
	 * 내용이 같은데도 다른 실행으로 보인다. record와 {@code ObjectNode}는 필드·삽입 순서를
	 * 그대로 지키므로 Jackson 기본 설정으로 충분하다.
	 */
	static String toJson(ObjectMapper objectMapper, Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JacksonException exception) {
			// 직렬화가 안 되면 요청을 만들 수 없다. 여기서 멈추는 편이 반쯤 만들어진 요청을
			// AI에 보내는 것보다 낫다 -- 후자는 비용을 쓰고 나서 실패한다.
			throw new IllegalStateException("리포트 페이로드를 직렬화하지 못했습니다.", exception);
		}
	}

	/** 소문자 hex 64자. DB CHECK가 요구하는 형식 그대로다. */
	static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			// SHA-256은 JDK 표준이라 실제로는 일어나지 않는다. 검사 예외를 위로 흘려
			// 호출부마다 try를 쓰게 만들 이유가 없다.
			throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
		}
	}
}
