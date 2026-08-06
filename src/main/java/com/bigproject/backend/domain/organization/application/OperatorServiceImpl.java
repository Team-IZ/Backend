package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.member.application.InvitationConflictException;
import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationException;
import com.bigproject.backend.domain.organization.domain.OrganizationOperatorRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import com.bigproject.backend.domain.organization.presentation.dto.InviteOperatorRequest;
import com.bigproject.backend.domain.organization.presentation.dto.InviteOperatorResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OperatorListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOperatorStatusRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본은 조회 트랜잭션. 쓰기가 필요한 메서드에만 @Transactional을 개별로 얹는다.
public class OperatorServiceImpl implements OperatorService {

	private static final String CANCEL_REASON = "슈퍼어드민이 오퍼레이터 초대를 취소했습니다.";

	private final OrganizationRepository organizationRepository;
	private final OrganizationOperatorRepository operatorRepository;

	/**
	 * 초대 발송(계정 자리 생성 + 토큰 발급 + 메일)은 member 도메인에 이미 구현돼 있어 그대로 호출한다.
	 * MemberInvitationService.inviteManager()가 "슈퍼어드민 → 오퍼레이터" 권한 검증과
	 * "오퍼레이터에게는 기수·반을 배정하지 않는다" 규칙까지 이미 갖고 있다.
	 * 이 클래스는 목업의 오퍼레이터 탭 계약(이메일만 받는 요청)으로 감싸는 역할만 한다.
	 */
	private final MemberInvitationService memberInvitationService;
	private final CurrentUserResolver currentUserResolver;

	@Override
	public OperatorListResponse findOperators(UUID organizationId) {
		assertOrganizationExists(organizationId);
		return buildListResponse(organizationId);
	}

	@Override
	@Transactional
	public InviteOperatorResponse inviteOperator(
			UUID organizationId,
			InviteOperatorRequest request,
			String actorEmail,
			String requestId
	) {
		assertOrganizationExists(organizationId);

		/*
		 * 목업 모달에는 이메일 입력 하나뿐이다.
		 *
		 * 대상 역할({@code Role.OPERATOR})을 <b>명시해서</b> 넘긴다. 예전에는 member 도메인이 호출자 역할로
		 * 대상을 추측했지만(슈퍼어드민이면 OPERATOR), 그러면 이 경로가 만드는 역할이 컨트롤러의 @PreAuthorize에
		 * 간접적으로 매달려 있어 권한 설정을 건드리면 조용히 다른 역할이 만들어질 수 있었다.
		 * 목업 SA-02: "슈퍼어드민은 첫 오퍼레이터 하나만 넣는다(부트스트랩)".
		 *
		 * 기수는 오퍼레이터가 기관 전체를 담당하므로 반드시 비워서 보낸다(member 도메인이 검증한다).
		 *
		 * <b>organization.email_domain 검증을 여기서 하지 않는다(의도적).</b> 목업 case 2·N1은
		 * DOMAIN_NOT_ALLOWED로 기관 도메인 밖 주소를 막게 돼 있었지만, 오퍼레이터 초대에 적용하면 모순이 생긴다 —
		 * 오퍼레이터는 기관의 <b>첫 계정(부트스트랩)</b>이라 초대를 받는 시점에는 그 기관 메일함을 가질 수 없다.
		 * 기관 도메인 주소는 오퍼레이터가 들어와 IT를 세팅한 <b>뒤에</b> 생기므로, 제한을 걸면 아무도 초대할 수 없다.
		 * 그래서 email_domain은 기관 프로필 정보로 저장만 하고, 이 제한은 오퍼레이터가 매니저·교육생을 초대하는
		 * 경로(OP-06)에 두는 것이 맞다 — 그때는 이미 기관 메일 체계가 존재한다.
		 * DOMAIN_NOT_ALLOWED 에러코드를 지우지 않고 남겨둔 이유도 이것이다.
		 */
		InviteManagerResponse invited;
		try {
			invited = memberInvitationService.inviteManager(
					organizationId,
					new InviteManagerRequest(request.email(), null),
					Role.OPERATOR,
					actorEmail,
					requestId
			);
		} catch (InvitationConflictException exception) {
			// 목업 케이스 계약은 오퍼레이터 탭 기준이라 member 도메인의 실패를 이 도메인 코드로 다시 던진다.
			throw new OrganizationException(OrganizationErrorCode.ALREADY_INVITED, exception.getMessage(), exception);
		} catch (ResponseStatusException exception) {
			// member 도메인은 메일 발송 실패를 502로 올린다. 목업 case 4·5는 메일 실패와 토큰 실패를 한 코드로 합쳤다 —
			// 서버 사정이 다를 뿐 사용자가 할 일은 `재발송` 하나이기 때문이다.
			if (exception.getStatusCode() == HttpStatus.BAD_GATEWAY) {
				throw new OrganizationException(
						OrganizationErrorCode.INVITE_MAIL_FAILED,
						OrganizationErrorCode.INVITE_MAIL_FAILED.defaultMessage(),
						exception
				);
			}
			throw exception;
		}

		return new InviteOperatorResponse(
				organizationId,
				invited.memberId(),
				invited.email(),
				// member 도메인은 초대 직후를 AccountStatus.INVITED로 표현하지만 DB CHECK 값은 PENDING이다.
				// 이 API는 DB 값을 기준으로 내려준다(OperatorAccountStatus javadoc 참고).
				OperatorAccountStatus.PENDING,
				invited.invitedAt()
		);
	}

	@Override
	@Transactional
	public OperatorListResponse updateOperatorStatus(
			UUID organizationId,
			UUID memberId,
			UpdateOperatorStatusRequest request
	) {
		assertOrganizationExists(organizationId);

		OrganizationOperatorRepository.OperatorAccount target = operatorRepository
				.findOperator(organizationId, memberId)
				.orElseThrow(() -> new OrganizationException(
						OrganizationErrorCode.OPERATOR_NOT_FOUND, "이 기관의 오퍼레이터 계정을 찾을 수 없습니다: " + memberId
				));

		if (request.status() == OperatorAccountStatus.INACTIVE) {
			assertNotLastActiveOperator(organizationId, target);
		}

		if (target.status() != request.status()) {
			operatorRepository.updateOperatorStatus(memberId, request.status());
		}

		return buildListResponse(organizationId);
	}

	@Override
	@Transactional
	public OperatorListResponse cancelInvitation(UUID organizationId, UUID tokenId) {
		assertOrganizationExists(organizationId);

		OrganizationOperatorRepository.PendingOperatorInvitation invitation = operatorRepository
				.findPendingInvitation(organizationId, tokenId)
				.orElseThrow(() -> new OrganizationException(
						OrganizationErrorCode.OPERATOR_INVITATION_NOT_FOUND, "취소할 수 있는 오퍼레이터 초대를 찾을 수 없습니다: " + tokenId
				));

		operatorRepository.invalidateInvitation(tokenId, CANCEL_REASON);

		/*
		 * 토큰만 무효화하면 초대 원장이 SENT로 남는다. 그러면 감사·통계가 발송된 초대로 계속 세고,
		 * uq_user_invitation_incomplete가 (기관, 이메일, 역할) 조합을 계속 점유해 같은 주소로
		 * 재초대할 수 없다. 원장도 함께 CANCELLED로 닫는다.
		 */
		if (invitation.invitationId() != null) {
			operatorRepository.cancelInvitationLedger(
					invitation.invitationId(), currentUserResolver.resolveCurrentMemberId()
			);
		}

		// 토큰만 무효화하면 계정 자리가 PENDING으로 남아 로그인 경로가 애매해진다. 자리는 남기되(이력 보존)
		// 로그인은 막도록 INACTIVE로 내린다 — 목업의 "퇴사한 계정도 지우지 않고 정지로 남긴다"와 같은 처리다.
		if (invitation.memberId() != null) {
			operatorRepository.updateOperatorStatus(invitation.memberId(), OperatorAccountStatus.INACTIVE);
		}

		return buildListResponse(organizationId);
	}

	@Override
	@Transactional
	public OperatorListResponse resendInvitation(
			UUID organizationId,
			UUID tokenId,
			String actorEmail,
			String requestId
	) {
		assertOrganizationExists(organizationId);

		/*
		 * 이 토큰이 정말 이 기관의 오퍼레이터 초대인지 먼저 본다. member 도메인은 역할 축(누가 재발송할 수
		 * 있는가)만 검증하므로, 기관 경계는 여기서 세운다 — 다른 기관 토큰 ID를 넣어 남의 초대를 건드리는
		 * 경로를 막는다.
		 */
		operatorRepository.findPendingInvitation(organizationId, tokenId)
				.orElseThrow(() -> new OrganizationException(
						OrganizationErrorCode.OPERATOR_INVITATION_NOT_FOUND,
						"재발송할 수 있는 오퍼레이터 초대를 찾을 수 없습니다: " + tokenId
				));

		try {
			memberInvitationService.resendInvitation(tokenId, actorEmail, requestId);
		} catch (ResponseStatusException exception) {
			// 목업 case 4·5는 메일 실패와 토큰 실패를 한 코드로 합쳤다 — 사용자가 할 일은 [재발송] 하나다.
			if (exception.getStatusCode() == HttpStatus.BAD_GATEWAY) {
				throw new OrganizationException(
						OrganizationErrorCode.INVITE_MAIL_FAILED,
						OrganizationErrorCode.INVITE_MAIL_FAILED.defaultMessage(),
						exception
				);
			}
			throw exception;
		}

		return buildListResponse(organizationId);
	}

	/**
	 * 마지막 활성 오퍼레이터 정지 차단(고아 기관 방지).
	 * 이미 활성이 아닌 계정을 정지하는 것은 활성 수를 줄이지 않으므로 차단 대상이 아니다.
	 */
	private void assertNotLastActiveOperator(
			UUID organizationId,
			OrganizationOperatorRepository.OperatorAccount target
	) {
		if (target.status() != OperatorAccountStatus.ACTIVE) {
			return;
		}
		if (operatorRepository.countActiveOperators(organizationId) <= 1) {
			throw new OrganizationException(
					OrganizationErrorCode.LAST_OPERATOR,
					"이 기관의 마지막 오퍼레이터입니다. 정지하면 기관에 들어갈 수 있는 사람이 아무도 없어집니다. "
							+ "새 오퍼레이터를 먼저 초대해 활성화한 뒤에 정지할 수 있습니다. "
							+ "기관 자체를 멈추려면 운영 설정의 기관 상태를 SUSPENDED로 변경하세요."
			);
		}
	}

	private OperatorListResponse buildListResponse(UUID organizationId) {
		List<OrganizationOperatorRepository.OperatorAccount> operators =
				operatorRepository.findOperators(organizationId);
		int activeCount = (int) operators.stream()
				.filter(operator -> operator.status() == OperatorAccountStatus.ACTIVE)
				.count();

		List<OperatorListResponse.Operator> content = operators.stream()
				.map(operator -> new OperatorListResponse.Operator(
						operator.memberId(),
						operator.name(),
						operator.email(),
						operator.status(),
						operator.invitedAt(),
						operator.lastLoginAt(),
						operator.pendingInvitationTokenId(),
						// 활성 계정이면서 마지막 1인이 아닐 때만 정지 버튼이 살아 있다.
						operator.status() == OperatorAccountStatus.ACTIVE && activeCount > 1,
						operator.invitationDeliveryFailed()
				))
				.toList();

		return new OperatorListResponse(organizationId, activeCount, content);
	}

	private void assertOrganizationExists(UUID organizationId) {
		if (!organizationRepository.existsById(organizationId)) {
			throw new OrganizationException(OrganizationErrorCode.ORG_NOT_FOUND, "기관을 찾을 수 없습니다: " + organizationId);
		}
	}
}
