package com.bigproject.backend.global.validation;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 데이터 보존기간 정책.
 *
 * <p>허용값을 <b>한 곳에만</b> 둔다. 요청 DTO 두 곳(기관 생성·운영 설정 변경)에 리터럴을 복붙하면
 * 나중에 한쪽만 바뀌어 화면과 서버가 어긋난다.
 */
public final class RetentionPolicy {

	/**
	 * 선택 가능한 보존기간(일).
	 *
	 * <p>화면(SA-01 기관 생성 모달 · SA-02 설정 탭)이 이 세 값만 고르게 한다. 예전에는 서버가
	 * 30~3650 사이 아무 값이나 받아, Swagger·Postman 같은 화면 밖 경로로 47 같은 값이 들어오면
	 * 설정 탭 셀렉트가 그 값을 표현하지 못했다. 그 상태로 저장을 누르면 사용자가 의도하지 않은 값으로
	 * 덮어써진다.
	 *
	 * <p>순서를 유지하려고 LinkedHashSet 을 쓴다 — 오류 메시지에 그대로 노출된다.
	 */
	public static final Set<Integer> ALLOWED_DAYS =
			java.util.Collections.unmodifiableSet(new LinkedHashSet<>(java.util.List.of(90, 180, 365)));

	private RetentionPolicy() {
	}

	public static boolean isAllowed(int days) {
		return ALLOWED_DAYS.contains(days);
	}
}
