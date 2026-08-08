package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.OrganizationEmailDomainRepository;
import com.bigproject.backend.domain.member.presentation.dto.MemberProfileResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * 지금 이 토큰의 주인을 <b>서버에서 다시 읽어</b> 내려준다.
 *
 * <p><b>왜 필요한가.</b> 새로고침하면 브라우저 메모리가 비워진다. 리프레시 쿠키로 액세스 토큰은
 * 되살릴 수 있지만 재발급 응답에는 토큰만 있어 역할이 없고, 역할을 모르면 사이드바도 라우팅도
 * 그릴 수 없다. 그래서 프론트가 역할을 브라우저 저장소에 남겨 두게 되는데 대가가 둘이다 —
 * 계정이 정지되거나 역할이 바뀌어도 화면이 모르고(<b>낡은 역할</b>), 신원 정보가 저장소에 남는다.
 *
 * <p>이 API가 있으면 <b>부팅 → refresh → /me</b> 한 줄로 세션이 복원되고 역할이 서버 진실이 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberProfileService {

	private final CurrentUserResolver currentUserResolver;
	private final OrganizationEmailDomainRepository organizationEmailDomainRepository;

	public MemberProfileResponse currentMember() {
		AuthUser user = resolveCurrentUser();
		return new MemberProfileResponse(
				user.userId(),
				user.email(),
				user.name(),
				user.role(),
				user.organizationId(),
				accountStatus(user.status()),
				// 9차 Q3-④ — GET /organizations/{id}는 슈퍼어드민 전용이라 오퍼레이터가 기관 도메인을
				// 읽을 곳이 없었다. 초대 화면이 도메인 제한을 걸려면 이 값이 필요하다.
				organizationEmailDomainRepository.findEmailDomain(user.organizationId()).orElse(null)
		);
	}

	/**
	 * {@link CurrentUserResolver}는 안정 코드가 없는 {@code ResponseStatusException}을 던진다 —
	 * 그대로 두면 응답 코드가 HTTP 상태 이름({@code UNAUTHORIZED})이 되어 스펙의 코드 목록에
	 * 잡히지 않는다(코드 추출기가 밑줄 있는 토큰만 본다). 이름 있는 코드로 옮겨 적는다.
	 */
	private AuthUser resolveCurrentUser() {
		try {
			return currentUserResolver.resolveCurrentUser();
		} catch (ResponseStatusException exception) {
			throw new ApiException(MemberErrorCode.MEMBER_NOT_FOUND);
		}
	}

	/**
	 * DB {@code app_user.status}는 ('PENDING','ACTIVE','INACTIVE') 세 값이고, member 도메인은 초대 직후를
	 * {@link AccountStatus#INVITED}로 표현한다({@code InviteManagerResponse}와 같은 규칙).
	 *
	 * <p>실제로는 거의 항상 {@code ACTIVE}다 — PENDING 계정은 비밀번호가 없어 토큰을 못 받는다.
	 * {@code INACTIVE}가 나오는 경우는 <b>세션 도중에 정지된 것</b>이고, 액세스 토큰이 1시간짜리라
	 * 만료 전까지는 호출이 통과하므로 화면이 그 사이에 알아채려면 이 값이 필요하다.
	 */
	private AccountStatus accountStatus(String status) {
		if (status == null) {
			return AccountStatus.INACTIVE;
		}
		// LOCKED 분기는 9차 Q3-②로 제거했다 — ck_app_user_status가 세 값만 허용해 도달할 수 없었다.
		return switch (status.trim().toUpperCase(Locale.ROOT)) {
			case "ACTIVE" -> AccountStatus.ACTIVE;
			case "PENDING", "INVITED" -> AccountStatus.INVITED;
			default -> AccountStatus.INACTIVE;
		};
	}
}
