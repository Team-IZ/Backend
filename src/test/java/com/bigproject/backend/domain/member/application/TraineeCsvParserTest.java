package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraineeCsvParserTest {
	private final TraineeCsvParser parser = new TraineeCsvParser();

	@Test
	void parsesUtf8BomAndQuotedFieldsWithCsvRowNumbers() {
		MockMultipartFile file = csv("\uFEFF이름,이메일\r\n\"홍,길동\",hong@example.com\r\n김교육,k@example.com\r\n");

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
				.hasMessageContaining("이름,이메일");
	}

	@Test
	void rejectsMalformedColumnCountWithRowNumber() {
		MockMultipartFile file = csv("이름,이메일\n홍길동,trainee@example.com,extra\n");

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
