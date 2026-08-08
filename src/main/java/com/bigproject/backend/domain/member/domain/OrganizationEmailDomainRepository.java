package com.bigproject.backend.domain.member.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 기관의 이메일 도메인을 읽는 조회 포트(9차 Q3-④).
 *
 * <p>명단·매니저 초대가 <b>기관 이메일 도메인 밖 주소를 막는데</b>, 그 값
 * ({@code OrganizationResponse.emailDomain})은 {@code GET /organizations/{organizationId}} 하나에만 있고
 * 그 API는 {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}이라 <b>오퍼레이터가 부르면 403</b>이다.
 * 그래서 {@code GET /me}가 자기 기관의 도메인을 함께 내려주도록 이 포트를 둔다.
 *
 * <p>organization 도메인의 서비스를 부르지 않고 읽기 전용 포트로 둔 이유는 자바 코드 사이의 의존을
 * 만들지 않기 위해서다 — {@code ManagerDirectoryRepository}와 같은 방식이다.
 */
public interface OrganizationEmailDomainRepository {

	/**
	 * @return 기관이 없거나 {@code email_domain}이 설정되지 않았으면 비어 있다.
	 *         컬럼 자체가 nullable이라 "설정하지 않음"이 정상 상태다
	 */
	Optional<String> findEmailDomain(UUID organizationId);
}
