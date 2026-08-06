package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.InvitationRecipient;
import com.bigproject.backend.domain.auth.domain.InvitationResolveRepository;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveRequest;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveResponse;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitationResolveService {
	private static final String INVALID_INVITATION_MESSAGE = "유효하지 않거나 만료된 초대입니다.";

	private final InvitationResolveRepository invitationResolveRepository;
	private final OneTimeTokenHasher tokenHasher;

	public InvitationResolveResponse resolve(InvitationResolveRequest request) {
		String tokenHash = tokenHasher.hash(request.invitationToken().trim());
		InvitationRecipient recipient = invitationResolveRepository.findResolvableByTokenHash(tokenHash, Instant.now())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_INVITATION_MESSAGE));
		return new InvitationResolveResponse(recipient.userId(), recipient.email(), recipient.role());
	}
}
