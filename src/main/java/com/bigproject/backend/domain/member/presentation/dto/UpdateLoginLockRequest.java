package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 로그인 차단 설정·해제 요청.
 *
 * <h2>왜 시각 하나가 아니라 {@code locked} 플래그를 함께 받는가</h2>
 *
 * <p>시각만 받고 "비어 있으면 해제"로 정하면, <b>본문을 빠뜨린 요청이 조용히 계정 차단을 푼다.</b>
 * JSON에서 "키를 안 보냄"과 "null을 보냄"이 서버에서 같은 값으로 도착하기 때문에 서버는 둘을
 * 구분할 수 없다. 보안 조치를 되돌리는 동작이 <b>실수로 도달할 수 있는 기본값</b>이면 안 된다.
 *
 * <p>그래서 의사를 명시적으로 받는다 — {@code locked}는 필수이고, 켤 때는 종료 시각도 함께 와야 한다.
 */
@Schema(description = "계정의 로그인 차단 설정·해제 요청")
public record UpdateLoginLockRequest(
		@Schema(
				description = """
						**필수.** `true`면 차단, `false`면 해제다.
						해제가 실수로 도달하지 않도록 의사를 명시적으로 받는다 —
						본문을 빠뜨린 요청이 계정 차단을 푸는 일이 없어야 한다.""",
				example = "true")
		@NotNull
		Boolean locked,

		@Schema(
				description = """
						차단이 끝나는 시각(UTC). `locked=true`면 **필수**이고, `locked=false`면 무시된다.
						상태가 아니라 시각이므로 이 시각이 지나면 별도 조작 없이 차단이 풀린다.
						과거 시각은 400으로 거절한다 — 걸자마자 풀리는 차단은 건 적이 없는 것과 같다.""",
				example = "2026-08-18T09:00:00Z",
				nullable = true)
		@Future
		Instant lockedUntil,

		@Schema(
				description = """
						조치 사유. `app_user`에 사유 컬럼이 없어 **감사 로그**(`audit_log.after_snapshot`)에 남는다.
						계정 정지 사유(`inactivated_reason`)와는 다른 자리다 — 정지와 차단은 다른 조작이다.""",
				example = "비정상 로그인 시도 확인, 본인 확인 시까지 차단",
				nullable = true)
		@Size(max = 500)
		String reason
) {
}
