package com.bigproject.backend.domain.member.application;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 초대 자리(PENDING)의 {@code app_user.password_hash}에 넣는 placeholder 해시.
 * <b>애플리케이션 인스턴스당 한 번만 계산하고 모든 초대가 공유한다.</b>
 *
 * <h2>왜 매번 새로 만들지 않나</h2>
 *
 * <p>예전에는 초대 한 건마다 {@code passwordEncoder.encode(UUID.randomUUID().toString())}을 불렀다.
 * BCrypt는 <b>의도적으로 느린</b> 알고리즘이라(strength 10 기준 실측 73.5ms/회) CSV 대량 등록에서
 * 이 비용이 인원수만큼 곱해졌다 — 900명이면 순수 CPU만 66초다.
 *
 * <p>그런데 <b>이 해시는 검증에 쓰이지 않는다.</b> 평문이 즉시 버려지는 랜덤 UUID라 대조할 대상이
 * 존재하지 않고, 교육생이 초대 링크에서 비밀번호를 정하면 {@code AccountActivationService}가 진짜
 * 해시로 덮어쓴다. 초대 건마다 값이 달라야 할 이유가 없으므로 인스턴스당 하나면 충분하다.
 *
 * <h2>왜 NULL이 아닌가</h2>
 *
 * <p>{@code ck_app_user_status_2}가 {@code status='PENDING'}을 면제하므로 DDL상으로는 NULL이 가능하고,
 * 같은 제약에 묶인 {@code name}은 실제로 NULL로 두고 있다. 그런데 <b>PENDING 자리를 INACTIVE로 내리는
 * 경로가 password_hash가 이미 채워져 있다고 전제한다.</b>
 *
 * <p>오퍼레이터 초대 취소({@code OperatorServiceImpl.cancelInvitation})는 자리를 지우지 않고 INACTIVE로
 * 내린다 — 그게 재초대의 전제다. 이때 {@code JdbcOrganizationOperatorRepository}의 UPDATE는 비어 있는
 * {@code name}만 이메일 로컬파트로 메우고 {@code password_hash}는 손대지 않는다. PENDING을 벗어나면
 * 세 컬럼이 전부 NOT NULL이어야 하므로, 여기서 NULL을 넣으면 <b>초대 취소가 CHECK 위반으로 실패하고
 * 같은 주소로 재초대할 수도 없게 된다.</b>
 *
 * <p>그 UPDATE까지 함께 고치는 방법도 있지만, 다른 도메인의 쓰기 경로를 건드리면서 얻는 것이 없다 —
 * 절감 효과(초대당 BCrypt 1회 제거)는 이 방식과 동일하다.
 *
 * <h2>공유해도 안전한 이유</h2>
 *
 * <p>평문은 생성 직후 버려지는 랜덤 UUID라 아무도 모른다. 값을 소스에 박아 두지 않고 부팅할 때
 * 만들므로 저장소에도 남지 않고 인스턴스·재시작마다 달라진다. PENDING 계정은 어차피 로그인 경로의
 * {@code validateAccount}에서 막힌다.
 */
@Component
public class PendingPasswordHash {
	private final String value;

	PendingPasswordHash(PasswordEncoder passwordEncoder) {
		this.value = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public String value() {
		return value;
	}
}
