package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.Role;

import java.util.List;

/**
 * 가입 화면에 뿌릴 동의 항목 목록이다.
 *
 * <p>동의 항목 마스터 테이블은 v07 스키마에 없다. 필수·선택 구성은
 * {@code AccountActivationService}가 활성화 시 실제로 기록하는 항목과 같아야 하며,
 * 한쪽만 바꾸면 화면에 뜨지 않은 항목이 필수로 검증되거나 그 반대가 된다.
 */
public final class ConsentCatalog {
	private static final ConsentItem TERMS_OF_SERVICE = new ConsentItem(
			ConsentCode.TERMS_OF_SERVICE,
			"서비스 이용약관",
			"서비스 이용 조건과 이용자·운영자의 권리·의무에 동의합니다.",
			"serviceTermsAgreed",
			true
	);
	private static final ConsentItem PRIVACY_POLICY = new ConsentItem(
			ConsentCode.PRIVACY_POLICY,
			"개인정보 수집·이용 동의",
			"계정 생성과 서비스 제공에 필요한 개인정보 수집·이용에 동의합니다.",
			"privacyCollectionAgreed",
			true
	);
	private static final ConsentItem AI_ANALYSIS = new ConsentItem(
			ConsentCode.AI_ANALYSIS,
			"AI 분석 이용 동의",
			"제출한 코드를 AI가 분석하여 문제·리포트를 생성하는 데 동의합니다.",
			"aiAnalysisAgreed",
			true
	);
	private static final ConsentItem ORGANIZATION_SHARING = new ConsentItem(
			ConsentCode.ORGANIZATION_SHARING,
			"기관 공유 동의",
			"분석 결과와 학습 이력을 소속 기관의 운영자·매니저가 열람하는 데 동의합니다.",
			"organizationSharingAgreed",
			true
	);
	private static final ConsentItem ANONYMIZED_DATA_USAGE = new ConsentItem(
			ConsentCode.ANONYMIZED_DATA_USAGE,
			"익명 데이터 서비스 개선 활용 동의",
			"개인을 식별할 수 없도록 처리한 데이터를 서비스 품질 개선에 활용하는 데 동의합니다.",
			"anonymousImprovementAgreed",
			false
	);

	private static final List<ConsentItem> STAFF_ITEMS = List.of(TERMS_OF_SERVICE, PRIVACY_POLICY);
	private static final List<ConsentItem> TRAINEE_ITEMS = List.of(
			TERMS_OF_SERVICE,
			PRIVACY_POLICY,
			AI_ANALYSIS,
			ORGANIZATION_SHARING,
			ANONYMIZED_DATA_USAGE
	);

	private ConsentCatalog() {
	}

	public static List<ConsentItem> forRole(Role role) {
		return role == Role.TRAINEE ? TRAINEE_ITEMS : STAFF_ITEMS;
	}

	/**
	 * @param requestField 화면이 이 항목의 동의 여부를 실어 보낼 가입 요청 필드명이다.
	 *                     응답에 코드만 있으면 프론트가 코드-필드 대응표를 따로 들고 있어야 한다.
	 */
	public record ConsentItem(
			ConsentCode code,
			String title,
			String description,
			String requestField,
			boolean required
	) {
	}
}
