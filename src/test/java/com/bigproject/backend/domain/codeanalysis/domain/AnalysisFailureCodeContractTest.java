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
 * 하나로 합치면서(S-03·S-15) 15종으로 시작했는데, 그 합의를 DB에 반영하는 마이그레이션이 함께
 * 나가지 않았다. enum javadoc이 "DB CHECK — 15종"이라고 <b>합의를 이미 이루어진 사실처럼</b> 적어
 * 두어 아무도 다시 대조하지 않았고, 아홉 날 뒤 28차 R1에서 드러났다. 기존 시험도 잡지 못했다 —
 * {@code AnalysisJobTest}는 enum 크기만 보았고, 그 숫자는 enum 쪽 정의와 언제나 맞았다.
 * 값 집합이 Java와 SQL 문자열로 <b>따로</b> 적혀 있는 한 한쪽만 보는 시험은 어긋남을 못 본다.
 * 고친 것은 {@code docs/migration/2026-08-16_fix_analysis_job_failure_code_set.sql}이다.
 *
 * <p>대조 상대를 실 DB가 아니라 <b>DDL 정본 파일</b>로 둔 이유: 이 어긋남은 정본에도 똑같이 있었고
 * (정본과 실 DB가 둘 다 11종이었다), 파일 대조는 컨테이너 없이 매 빌드에 돈다. 실 DB가 정본보다
 * 뒤처지는 경우는 마이그레이션 적용 여부의 문제라 별개로 다룬다.
 */
class AnalysisFailureCodeContractTest {

	private static final Path DDL = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");

	/** {@code CONSTRAINT ck_analysis_job_failure_code_2 CHECK (… IN ('A', 'B', …))} 의 괄호 안. */
	private static final Pattern CHECK_VALUES = Pattern.compile(
			"ck_analysis_job_failure_code_2\\s+CHECK\\s*\\([^(]*IN\\s*\\(([^)]*)\\)");

	private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

	@Test
	@DisplayName("enum과 DDL 정본의 CHECK가 같은 값 집합을 본다")
	void enumMatchesDdlCheck() throws IOException {
		Set<String> fromEnum = Arrays.stream(AnalysisFailureCode.values())
				.map(Enum::name)
				.collect(Collectors.toCollection(LinkedHashSet::new));

		assertThat(checkValues())
				.as("AnalysisFailureCode enum과 ck_analysis_job_failure_code_2가 어긋났다. "
						+ "한쪽만 고치면 CHECK 위반으로 터지거나 사유가 조용히 MODEL_ERROR로 바뀐다 — "
						+ "enum·DDL 정본·마이그레이션을 함께 고친다.")
				.containsExactlyInAnyOrderElementsOf(fromEnum);
	}

	@Test
	@DisplayName("업로드가 먼저 거절하는 3종은 값 집합에 없다")
	void archiveLevelCodesAreNotAnalysisFailures() throws IOException {
		// ZIP 파일 자체의 문제는 SubmissionService#validateArchive가 400·413으로 거절하고
		// submission 행조차 만들지 않는다. 분석이 시작되지 않으므로 analysis_job도 없다.
		// 값 집합에 남겨 두면 재현할 수 없는 코드를 두고 "왜 안 나오나"를 반복해서 파게 된다.
		assertThat(checkValues())
				.doesNotContain("FILE_TOO_LARGE", "ARCHIVE_INVALID", "PROHIBITED_FILE");

		assertThat(Arrays.stream(AnalysisFailureCode.values()).map(Enum::name))
				.doesNotContain("FILE_TOO_LARGE", "ARCHIVE_INVALID", "PROHIBITED_FILE");
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
