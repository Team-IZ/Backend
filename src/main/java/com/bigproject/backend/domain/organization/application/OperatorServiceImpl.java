package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.member.application.InvitationConflictException;
import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import com.bigproject.backend.domain.organization.domain.Organization;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationException;
import com.bigproject.backend.domain.organization.domain.OrganizationOperatorRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import com.bigproject.backend.domain.organization.presentation.dto.InviteOperatorRequest;
import com.bigproject.backend.domain.organization.presentation.dto.InviteOperatorResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OperatorListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOperatorStatusRequest;
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

		assertEmailDomainAllowed(organizationId, request.email());

		/*
		 * 목업 모달에는 이메일 입력 하나뿐이다.
		 *
		 * v06 이후 member 도메인이 대상 역할을 <b>호출자 역할로 서버가 결정</b>하도록 바뀌었다 —
		 * 슈퍼어드민이 호출하면 OPERATOR, 오퍼레이터가 호출하면 MANAGER다. 이 API는 슈퍼어드민 전용
		 * (컨트롤러 @PreAuthorize)이라 역할을 따로 넘기지 않아도 오퍼레이터 초대가 된다.
		 * 목업 SA-02: "슈퍼어드민은 첫 오퍼레이터 하나만 넣는다(부트스트랩)".
		 *
		 * 기수·반은 오퍼레이터가 기관 전체를 담당하므로 반드시 비워서 보낸다(member 도메인이 검증한다).
		 */
		InviteManagerResponse invited;
		try {
			invited = memberInvitationService.inviteManager(
					organizationId,
					new InviteManagerRequest(request.email(), null, null),
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

		// 토큰만 무효화하면 계정 자리가 PENDING으로 남아 로그인 경로가 애매해진다. 자리는 남기되(이력 보존)
		// 로그인은 막도록 INACTIVE로 내린다 — 목업의 "퇴사한 계정도 지우지 않고 정지로 남긴다"와 같은 처리다.
		if (invitation.memberId() != null) {
			operatorRepository.updateOperatorStatus(invitation.memberId(), OperatorAccountStatus.INACTIVE);
		}

		return buildListResponse(organizationId);
	}

	/**
	 * 목업 case 2·N1 · DOMAIN_NOT_ALLOWED — 기관 도메인 밖 주소로는 초대할 수 없다.
	 *
	 * <p>v06에서 organization.email_domain 컬럼이 생겨 <b>이 검증이 실제로 동작한다</b>
	 * (이전에는 상수가 항상 null이라 아무것도 막지 못했다).
	 * 도메인이 비어 있으면 제한을 두지 않는다는 뜻이므로 통과시킨다(정의서: "NULL이면 도메인 제한을 적용하지 않는다").
	 */
	private void assertEmailDomainAllowed(UUID organizationId, String email) {
		String allowedDomain = organizationRepository.findById(organizationId)
				.map(Organization::getEmailDomain)
				.orElse(null);
		if (allowedDomain == null || allowedDomain.isBlank()) {
			return;
		}
		int at = email.lastIndexOf('@');
		String domain = at < 0 ? "" : email.substring(at + 1);
		if (!allowedDomain.equalsIgnoreCase(domain)) {
			throw new OrganizationException(
					OrganizationErrorCode.DOMAIN_NOT_ALLOWED,
					"이 기관은 " + allowedDomain + " 주소로만 초대할 수 있습니다."
			);
		}
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
						operator.status() == OperatorAccountStatus.ACTIVE && activeCount > 1
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
