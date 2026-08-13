package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.CommitEmail;
import com.bigproject.backend.domain.member.domain.CommitEmailRepository;
import com.bigproject.backend.domain.member.presentation.dto.CommitEmailResponse;
import com.bigproject.backend.domain.member.presentation.dto.UpdateCommitEmailRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommitEmailService {
	private final CommitEmailRepository commitEmailRepository;
	private final CurrentUserResolver currentUserResolver;

	@Transactional(readOnly = true)
	public CommitEmailResponse getMyCommitEmail() {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		CommitEmail commitEmail = commitEmailRepository.findByUserId(userId)
				.orElseThrow(() -> new CommitEmailException(CommitEmailErrorCode.MEMBER_NOT_FOUND));
		return CommitEmailResponse.from(commitEmail);
	}

	@Transactional
	public CommitEmailResponse updateMyCommitEmail(UpdateCommitEmailRequest request) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		String commitEmail = request.commitEmail().trim();
		String normalized = EmailNormalizer.normalize(commitEmail);
		Instant now = Instant.now();

		int updated;
		try {
			updated = commitEmailRepository.updateCommitEmail(userId, commitEmail, normalized, now);
		} catch (DuplicateKeyException exception) {
			// uq_app_user_commit_email_active — 같은 기관의 활성 계정끼리만 충돌한다.
			throw new CommitEmailException(CommitEmailErrorCode.COMMIT_EMAIL_ALREADY_USED, exception);
		}
		if (updated != 1) {
			throw new CommitEmailException(CommitEmailErrorCode.MEMBER_NOT_FOUND);
		}

		return new CommitEmailResponse(true, commitEmail, "PENDING", null, null, now);
	}
}
