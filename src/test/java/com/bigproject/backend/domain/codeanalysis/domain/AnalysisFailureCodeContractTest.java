package com.bigproject.backend.domain.codeanalysis.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link AnalysisFailureCode}와 DB CHECK가 같은 값 집합을 보는지 대조한다.
 *
 * <h2>왜 이 시험이 필요한가</h2>
 *
 * <p>둘이 어긋나면 <b>양방향으로 샌다.</b>
 *
 * <ul>
 *   <li><b>enum에만 있는 값</b> — {@link AnalysisFailureCode#parse}가 통과시키고,
 *       {@code AnalysisBatchService}의 {@code MODEL_ERROR} 대체 가드는 {@code parse}가 <b>비어
 *       돌아올 때만</b> 작동하므로 그대로 지나쳐 <b>INSERT 시점의 CHECK 위반</b>으로 터진다.
 *       enum을 도입한 목적("실패를 읽는 자리에서 바로 걸러낸다")이 정확히 무너지는 지점이다.</li>
 *   <li><b>CHECK에만 있는 값</b> — AI가 그 값을 보내면 {@code parse}가 비어 돌아와
 *       {@code MODEL_ERROR}로 대체된다. 사유가 <b>조용히 틀리게</b> 기록된다.</li>
 * </ul>
 *
 * <p>🔴 <b>둘은 한 번도 일치한 적이 없었다.</b> 2026-08-07 최초 커밋이 세 컬럼의 값 집합을 enum
 * 하나로 합치면서(S-03·S-15) 15종으로 시작했는데, 그 합의가 <b>DDL 정본 파일에는</b> 반영되지
 * 않은 채 남았다(실 DB에는 반영돼 있었다 — 아래 "이 시험이 못 보는 것"). enum javadoc의
 * "DB CHECK — 15종"은 실 DB에 대해서는 사실이었고 정본 파일에 대해서는 아니었다. 아무도 다시
 * 대조하지 않았고, 아홉 날 뒤 28차 R1에서 드러났다. 기존 시험도 잡지 못했다 —
 * {@code AnalysisJobTest}는 enum 크기만 보았고, 그 숫자는 enum 쪽 정의와 언제나 맞았다.
 * 값 집합이 Java와 SQL 문자열로 <b>따로</b> 적혀 있는 한 한쪽만 보는 시험은 어긋남을 못 본다.
 * 고친 것은 {@code docs/migration/2026-08-16_fix_analysis_job_failure_code_set.sql}이다.
 *
 * <p>대조 상대를 실 DB가 아니라 <b>DDL 정본 파일</b>로 둔 이유: 파일 대조는 컨테이너 없이 매
 * 빌드에 돈다. 그 대신 <b>이 시험이 통과해도 실 DB는 다를 수 있다.</b>
 *
 * <h2>🔴 두 집합은 크기가 다른 것이 정상이다 (2026-08-16)</h2>
 *
 * <p>운영/재현 DB 실측에서 CHECK는 <b>15종</b>이었다. 정본 파일이 11종·12종이던 동안에도 줄곧
 * 그랬다 — 어긋난 짝은 "enum ↔ 실 DB"가 아니라 <b>"enum ↔ 정본 파일"</b>이었고, 실 DB가 정본보다
 * <b>앞서</b> 있었다. 줄이는 마이그레이션은 보류하고 정본을 실 DB에 맞췄으므로, 지금 관계는
 * <b>enum(12종) ⊆ CHECK(15종)</b>이고 남는 3종은 업로드 단계에서만 쓰는 값이다.
 *
 * <p>그래서 이 시험은 두 집합이 <b>같은지</b>가 아니라 <b>포함 관계와 그 차이</b>를 본다.
 * <ul>
 *   <li>enum에 있는데 CHECK에 없으면 → 저장이 CHECK 위반으로 터진다</li>
 *   <li>CHECK에만 있는 값이 늘면 → 그 값을 쓰는 행이 생길 수 있고,
 *       {@code AnalysisJob}의 {@code @Enumerated(STRING)}이 그 행을 <b>읽지 못한다</b></li>
 * </ul>
 *
 * <p>실 DB에 무엇이 붙어 있는지는 여전히 물어봐야 안다 —
 * {@code docs/analysis_job_failure_code_제약점검.sql}가 그 질문을 대신한다(읽기 전용).
 * 폐쇄형 코드값을 정하거나 CHECK를 건드리기 전에는 이 시험이 아니라 그 점검 SQL을 본다.
 */
class AnalysisFailureCodeContractTest {

	private static final Path DDL = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");

	/** {@code CONSTRAINT ck_analysis_job_failure_code_2 CHECK (… IN ('A', 'B', …))} 의 괄호 안. */
	private static final Pattern CHECK_VALUES = Pattern.compile(
			"ck_analysis_job_failure_code_2\\s+CHECK\\s*\\([^(]*IN\\s*\\(([^)]*)\\)");

	private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

	/** CHECK에만 있어도 되는 값. 업로드가 먼저 거절해 이 컬럼에 도달할 경로가 없는 셋이다. */
	private static final Set<String> UPLOAD_ONLY_CODES =
			Set.of("FILE_TOO_LARGE", "ARCHIVE_INVALID", "PROHIBITED_FILE");

	@Test
	@DisplayName("enum의 모든 값이 DDL 정본의 CHECK 안에 있다")
	void everyEnumValueIsAllowedByCheck() throws IOException {
		Set<String> fromEnum = Arrays.stream(AnalysisFailureCode.values())
				.map(Enum::name)
				.collect(Collectors.toCollection(LinkedHashSet::new));

		assertThat(checkValues())
				.as("enum에는 있는데 ck_analysis_job_failure_code_2가 받지 않는 값이 있다. "
						+ "AI가 그 코드를 주면 저장이 CHECK 위반으로 터져 실패를 기록조차 못 한다 — "
						+ "enum·DDL 정본·실 DB를 함께 고친다.")
				.containsAll(fromEnum);
	}

	@Test
	@DisplayName("CHECK가 enum보다 넓은 것은 업로드 단계 3종뿐이다")
	void checkIsWiderOnlyByUploadOnlyCodes() throws IOException {
		Set<String> fromEnum = Arrays.stream(AnalysisFailureCode.values())
				.map(Enum::name)
				.collect(Collectors.toSet());

		Set<String> extra = new LinkedHashSet<>(checkValues());
		extra.removeAll(fromEnum);

		// 🔴 넓은 것 자체는 괜찮지만, 넓어진 만큼이 무엇인지는 고정한다. 새 값이 CHECK에만 조용히
		//    늘면 그 값을 쓰는 행이 생길 수 있고, AnalysisJob의 @Enumerated(STRING)이 그 행을
		//    읽는 순간 터진다("No enum constant"). 값이 허용되는 것과 써도 되는 것은 다르다.
		assertThat(extra)
				.as("CHECK에만 있는 값이 예상과 다르다. 늘리려면 enum에 넣거나, 이 목록에 근거와 함께 "
						+ "추가한다 — 쓰지 않을 값이라는 판단이 어딘가에 남아야 한다.")
				.containsExactlyInAnyOrderElementsOf(UPLOAD_ONLY_CODES);

		// 반대 방향. 그 셋은 enum에 들어오면 안 된다 — parse()가 통과시키면 저장까지 가고,
		// 그 행은 다시 못 읽는다.
		assertThat(fromEnum).doesNotContainAnyElementsOf(UPLOAD_ONLY_CODES);
	}

	@Test
	@DisplayName("EMPTY_CODE_EVIDENCE는 EMPTY_CODE로 합쳐졌다")
	void emptyCodeEvidenceIsGone() throws IOException {
		// 이름이 한 단어 차이라 둘 다 허용하면 같은 사실이 두 값으로 나뉘어 쌓인다(2026-08-07).
		assertThat(checkValues()).doesNotContain("EMPTY_CODE_EVIDENCE").contains("EMPTY_CODE");
		assertThat(AnalysisFailureCode.parse("EMPTY_CODE_EVIDENCE")).isEmpty();
		assertThat(AnalysisFailureCode.parse("EMPTY_CODE")).contains(AnalysisFailureCode.EMPTY_CODE);
	}

	private static Set<String> checkValues() throws IOException {
		assumeTrue(Files.exists(DDL), "정본 DDL 문서가 없어 계약 검증을 건너뜁니다.");
		String ddl = Files.readString(DDL);
		Matcher constraint = CHECK_VALUES.matcher(ddl);
		assertThat(constraint.find())
				.as("DDL 정본에서 ck_analysis_job_failure_code_2를 찾지 못했다: %s", DDL)
				.isTrue();

		Set<String> values = new LinkedHashSet<>();
		Matcher value = QUOTED.matcher(constraint.group(1));
		while (value.find()) {
			values.add(value.group(1));
		}
		return values;
	}
}
