package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.InvitationResolveRepository;
import com.bigproject.backend.domain.auth.domain.InvitationState;
import com.bigproject.backend.domain.auth.domain.InvitationStateClassifier;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveRequest;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveResponse;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitationResolveService {
	/** 초대 링크를 여는 단계라 세 초대 목적을 모두 해석한다. 비밀번호 재설정 토큰은 여기로 오면 안 된다. */
	private static final Set<String> RESOLVABLE_PURPOSES = Set.of(
			InvitationPurpose.INVITE_SUPER_ADMIN.name(),
			InvitationPurpose.INVITE_OPERATOR_MANAGER.name(),
			InvitationPurpose.INVITE_TRAINEE.name()
	);

	private final InvitationResolveRepository invitationResolveRepository;
	private final OneTimeTokenHasher tokenHasher;

	public InvitationResolveResponse resolve(InvitationResolveRequest request) {
		String tokenHash = tokenHasher.hash(request.invitationToken().trim());
		InvitationState state = invitationResolveRepository.findStateByTokenHash(tokenHash)
				.orElseThrow(InvitationStateClassifier::invalid);
		if (!RESOLVABLE_PURPOSES.contains(state.purpose())) {
			throw InvitationStateClassifier.invalid();
		}
		InvitationStateClassifier.verify(state, Instant.now());
		return new InvitationResolveResponse(state.userId(), state.email(), state.role());
	}
}
