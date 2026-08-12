package com.bigproject.backend.domain.auth.domain;

import java.util.Optional;

public interface InvitationResolveRepository {
	/**
	 * 토큰 해시로 초대 상태를 통째로 읽는다. <b>쓸 수 있는지 걸러 내지 않는다</b> —
	 * 판정은 {@link InvitationStateClassifier}가 한다. 이유는 {@link InvitationState} 주석 참고.
	 *
	 * <p>빈 값은 "그 해시의 초대 토큰이 없다"만 뜻한다.
	 */
	Optional<InvitationState> findStateByTokenHash(String tokenHash);
}
