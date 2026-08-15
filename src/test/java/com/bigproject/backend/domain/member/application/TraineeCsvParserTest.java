package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraineeCsvParserTest {
	private static final Charset CP949 = Charset.forName("MS949");
	/** 엑셀이 「CSV UTF-8」로 저장할 때 파일 앞에 붙이는 바이트 순서 표시. */
	private static final String BOM = "﻿";

	private final TraineeCsvParser parser = new TraineeCsvParser();

	@Test
	void parsesUtf8BomAndQuotedFieldsWithCsvRowNumbers() {
		MockMultipartFile file = csv(BOM + "이름,이메일\r\n\"홍,길동\",hong@example.com\r\n김교육,k@example.com\r\n");

		var rows = parser.parse(file);

		assertThat(rows).containsExactly(
				new TraineeCsvRow(2, "홍,길동", "hong@example.com"),
				new TraineeCsvRow(3, "김교육", "k@example.com")
		);
	}

	@Test
	void rejectsUnexpectedHeader() {
		MockMultipartFile file = csv("name,email\nTrainee,trainee@example.com\n");

		assertThatThrownBy(() -> parser.parse(file))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("이름")
				.hasMessageContaining("이메일");
	}

	/**
	 * 25차 Q1 — 윈도우 엑셀의 「CSV(쉼표로 분리)」 기본 저장이 CP949다. 종전에는 그 파일이
	 * "UTF-8이어야 합니다"로 거절돼, 사람이 매번 「CSV UTF-8」을 골라야 했다.
	 */
	@Test
	void readsCp949FileSavedByWindowsExcel() {
		MockMultipartFile file = new MockMultipartFile(
				"file", "trainees.csv", "text/csv",
				"이름,이메일\n홍길동,hong@example.com\n".getBytes(CP949));

		assertThat(parser.parse(file))
				.containsExactly(new TraineeCsvRow(2, "홍길동", "hong@example.com"));
	}

	/**
	 * CP949를 먼저 시도하면 정상 UTF-8 한글이 깨진 글자로 "읽혀" 통과한다.
	 * UTF-8을 엄격 모드로 먼저 걸어 두었는지 확인한다.
	 */
	@Test
	void prefersUtf8OverCp949SoKoreanIsNotMangled() {
		MockMultipartFile file = csv("이름,이메일\n홍길동,hong@example.com\n");

		assertThat(parser.parse(file))
				.containsExactly(new TraineeCsvRow(2, "홍길동", "hong@example.com"));
	}

	/** 25차 Q1 — 열 순서가 달라도, 모르는 열이 섞여 있어도 이름으로 찾아 읽는다. */
	@Test
	void findsColumnsByHeaderNameRegardlessOfOrderAndExtraColumns() {
		MockMultipartFile file = csv("""
				번호,이메일,소속,이름
				1,hong@example.com,1팀,홍길동
				2,kim@example.com,2팀,김교육
				""");

		assertThat(parser.parse(file)).containsExactly(
				new TraineeCsvRow(2, "홍길동", "hong@example.com"),
				new TraineeCsvRow(3, "김교육", "kim@example.com")
		);
	}

	/** 열을 이름으로 찾으므로 남는 열은 허용한다 — 값이 있는 두 열만 읽는다. */
	@Test
	void allowsTrailingExtraColumnInDataRow() {
		MockMultipartFile file = csv("이름,이메일\n홍길동,trainee@example.com,extra\n");

		assertThat(parser.parse(file))
				.containsExactly(new TraineeCsvRow(2, "홍길동", "trainee@example.com"));
	}

	/** 찾은 열의 값이 아예 없는 행은 몇 행인지 알려 준다. */
	@Test
	void rejectsRowMissingMappedColumnWithRowNumber() {
		MockMultipartFile file = csv("번호,이메일,소속,이름\n1,hong@example.com\n");

		assertThatThrownBy(() -> parser.parse(file))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("2행");
	}

	private MockMultipartFile csv(String content) {
		return new MockMultipartFile(
				"file",
				"trainees.csv",
				"text/csv",
				content.getBytes(StandardCharsets.UTF_8)
		);
	}
}
