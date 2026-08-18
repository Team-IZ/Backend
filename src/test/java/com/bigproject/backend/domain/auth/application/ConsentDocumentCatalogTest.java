package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.ConsentCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동의 문서가 <b>실제로 패키징돼 있는지</b> 확인한다.
 *
 * <p>문안은 코드가 아니라 리소스 파일이라 컴파일러가 지켜 주지 않는다. 파일 하나가 빠져도 코드는
 * 그대로 컴파일되고, 확인하지 않으면 배포된 뒤 가입 화면에서 처음 드러난다.
 */
class ConsentDocumentCatalogTest {

	private final ConsentDocumentCatalog catalog = new ConsentDocumentCatalog(1);

	@ParameterizedTest
	@EnumSource(ConsentCode.class)
	void carriesRealTextAndItsFingerprintForEveryConsentItem(ConsentCode code) {
		ConsentDocumentCatalog.ConsentDocument document = catalog.find(code);

		assertThat(document.body()).isNotBlank();
		// consent_record.evidence_hash 와 같은 형식(SHA-256 hex)이어야 재료로 쓸 수 있다.
		assertThat(document.documentHash()).hasSize(64).matches("[0-9a-f]{64}");
	}

	/** 문서가 다르면 해시도 달라야 한다. 같으면 어떤 문안에 동의했는지 구분되지 않는다. */
	@Test
	void givesEachDocumentItsOwnFingerprint() {
		assertThat(Arrays.stream(ConsentCode.values())
				.map(code -> catalog.find(code).documentHash())
				.toList())
				.doesNotHaveDuplicates();
	}

	/**
	 * 개인정보 수집·이용 동의는 <b>네 요소</b>를 갖춰야 한다 —
	 * ① 수집 항목 ② 이용 목적 ③ 보유·이용 기간 ④ 거부 권리와 불이익.
	 * 문안을 다듬다가 한 절이 통째로 사라지는 것을 막는다.
	 */
	@Test
	void keepsTheFourElementsTheCollectionNoticeMustCarry() {
		String body = catalog.find(ConsentCode.PRIVACY_POLICY).body();

		assertThat(body)
				.contains("수집하는 개인정보 항목")
				.contains("수집·이용 목적")
				.contains("보유 및 이용 기간")
				.contains("거부할 권리");
	}

	/**
	 * 버전을 올렸는데 그 버전의 문안 파일을 넣지 않으면 <b>기동이 멈춰야 한다.</b>
	 * 조용히 뜨면 화면은 빈 약관을 보여 주고 동의 기록은 다시 본문 없는 해시를 남긴다 —
	 * 이 작업이 고치려던 상태로 그대로 되돌아간다.
	 */
	@Test
	void refusesToStartWhenTheTextForThatVersionIsMissing() {
		assertThatThrownBy(() -> new ConsentDocumentCatalog(99))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("consent.policy-version");
	}
}
