package com.bigproject.backend.domain.member.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 교육생 계정 상태 변경 요청이 받을 수 있는 값.
 *
 * <p>{@link AccountStatus}를 그대로 쓰지 않는 이유는 <b>요청이 받을 수 있는 값이 두 개뿐</b>이기 때문이다.
 * {@code AccountStatus}를 받으면 스키마에 {@code INVITED}까지 노출돼 화면이 보내도 되는 값으로 오해하고,
 * 걸러내는 책임이 별도 검증 코드로 넘어간다. 타입을 좁히면 역직렬화 단계에서 거절되므로
 * 스키마와 실제 허용값이 어긋날 수 없다.
 */
@Schema(name = "TraineeStatusUpdate",
		description = "교육생 계정 상태 변경 대상. ACTIVE(활성화) · INACTIVE(비활성화)",
		enumAsRef = true)
public enum TraineeStatusUpdate {
	ACTIVE,
	INACTIVE;

	public AccountStatus toAccountStatus() {
		return this == ACTIVE ? AccountStatus.ACTIVE : AccountStatus.INACTIVE;
	}
}
