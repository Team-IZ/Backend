package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.organization.presentation.dto.InviteOperatorRequest;
import com.bigproject.backend.domain.organization.presentation.dto.InviteOperatorResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OperatorListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOperatorStatusRequest;

import java.util.UUID;

/**
 * 기관 상세 &gt; 오퍼레이터 탭(목업 SA-02 ②)의 비즈니스 로직.
 *
 * <p>이 탭은 <b>계정 관리이지 매니저 관리가 아니다.</b> 슈퍼어드민이 기관에 넣는 계정은 오퍼레이터뿐이고
 * (부트스트랩·복구 목적), 반 담당 매니저 초대·반 배정·정지는 기관 안에서 오퍼레이터가 한다(OP-06 ③).
 */
public interface OperatorService {

	/** 기관의 오퍼레이터 계정 목록. 정지된 계정도 포함한다(과거 기수 배정 이력에 이름이 붙어 있어 지우지 않는다). */
	OperatorListResponse findOperators(UUID organizationId);

	/**
	 * 오퍼레이터 초대. 이메일만 받고 역할은 오퍼레이터로 고정한다(기수 배정 없음 — 기관 전체를 본다).
	 *
	 * @param actorEmail 초대를 보내는 슈퍼어드민의 이메일(SecurityContext 인증 주체)
	 * @param requestId  초대 요청 추적용 식별자. null이면 서버가 생성한다.
	 */
	InviteOperatorResponse inviteOperator(
			UUID organizationId,
			InviteOperatorRequest request,
			String actorEmail,
			String requestId
	);

	/**
	 * 오퍼레이터 계정 정지/재활성.
	 *
	 * <p>마지막 활성 오퍼레이터의 정지는 차단한다(409) — 정지하면 기관에 들어갈 수 있는 사람이 아무도 없어지고
	 * 기수·명단·매니저를 손댈 방법이 사라진다(고아 기관 방지). 기관 자체를 멈추려면 계정이 아니라
	 * 운영 설정의 기관 상태를 SUSPENDED로 바꿔야 한다.
	 */
	OperatorListResponse updateOperatorStatus(
			UUID organizationId,
			UUID memberId,
			UpdateOperatorStatusRequest request
	);

	/** 아직 수락되지 않은 초대를 취소한다(토큰 무효화 + 계정 정지). */
	OperatorListResponse cancelInvitation(UUID organizationId, UUID tokenId);

	/**
	 * 오퍼레이터 초대 메일 재발송(목업 SA-02 ② case 4·5 [재발송]).
	 *
	 * <p>발송 실패로 남은 초대뿐 아니라 만료된 초대도 대상이다. 새 토큰을 발급하고 이전 토큰은 무효화한다 —
	 * 재발송 뒤에도 옛 링크가 살아 있으면 안 된다.
	 */
	OperatorListResponse resendInvitation(UUID organizationId, UUID tokenId, String actorEmail, String requestId);
}
