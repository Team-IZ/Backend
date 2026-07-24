package com.bigproject.backend.domain.member.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class TraineeCsvParser {
	private static final long MAX_FILE_SIZE = 1024 * 1024;
	private static final int MAX_TRAINEE_COUNT = 1000;
	private static final String NAME_HEADER = "이름";
	private static final String EMAIL_HEADER = "이메일";

	public List<TraineeCsvRow> parse(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw badRequest("CSV 파일이 비어 있습니다.");
		}
		if (file.getSize() > MAX_FILE_SIZE) {
			throw badRequest("CSV 파일은 1MB 이하만 업로드할 수 있습니다.");
		}

		String csv = decode(file);
		List<List<String>> records = parseRecords(csv);
		if (records.isEmpty()) {
			throw badRequest("CSV 파일에 헤더가 없습니다.");
		}

		validateHeader(records.get(0));
		List<TraineeCsvRow> trainees = new ArrayList<>();
		for (int index = 1; index < records.size(); index++) {
			List<String> record = records.get(index);
			if (record.size() == 1 && record.get(0).isBlank()) {
				continue;
			}
			if (record.size() != 2) {
				throw badRequest((index + 1) + "행은 '이름', '이메일' 두 열로 작성해야 합니다.");
			}
			trainees.add(new TraineeCsvRow(index + 1, record.get(0).trim(), record.get(1).trim()));
		}
		if (trainees.isEmpty()) {
			throw badRequest("CSV 파일에 교육생 데이터가 없습니다.");
		}
		if (trainees.size() > MAX_TRAINEE_COUNT) {
			throw badRequest("한 번에 최대 1000명의 교육생을 초대할 수 있습니다.");
		}
		return List.copyOf(trainees);
	}

	private String decode(MultipartFile file) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(file.getBytes()))
					.toString();
		} catch (CharacterCodingException exception) {
			throw badRequest("CSV 파일은 UTF-8 인코딩이어야 합니다.");
		} catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV 파일을 읽을 수 없습니다.", exception);
		}
	}

	private void validateHeader(List<String> header) {
		if (header.size() != 2) {
			throw badRequest("CSV 헤더는 '이름', '이메일' 두 열이어야 합니다.");
		}
		String first = removeBom(header.get(0)).trim();
		String second = header.get(1).trim();
		if (!NAME_HEADER.equals(first) || !EMAIL_HEADER.equals(second)) {
			throw badRequest("CSV 헤더는 첫 행에 '이름,이메일' 순서로 작성해야 합니다.");
		}
	}

	private List<List<String>> parseRecords(String csv) {
		List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		boolean quoteClosed = false;

		for (int index = 0; index < csv.length(); index++) {
			char character = csv.charAt(index);
			if (quoted) {
				if (character == '"') {
					if (index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
						field.append('"');
						index++;
					} else {
						quoted = false;
						quoteClosed = true;
					}
				} else {
					field.append(character);
				}
				continue;
			}

			if (quoteClosed && character != ',' && character != '\r' && character != '\n') {
				throw badRequest("CSV 따옴표 뒤에는 쉼표 또는 줄바꿈만 올 수 있습니다.");
			}
			if (character == '"' && field.isEmpty()) {
				quoted = true;
				quoteClosed = false;
			} else if (character == ',') {
				record.add(field.toString());
				field.setLength(0);
				quoteClosed = false;
			} else if (character == '\r' || character == '\n') {
				record.add(field.toString());
				records.add(List.copyOf(record));
				record.clear();
				field.setLength(0);
				quoteClosed = false;
				if (character == '\r' && index + 1 < csv.length() && csv.charAt(index + 1) == '\n') {
					index++;
				}
			} else {
				field.append(character);
			}
		}

		if (quoted) {
			throw badRequest("CSV의 따옴표가 닫히지 않았습니다.");
		}
		if (!record.isEmpty() || !field.isEmpty() || quoteClosed) {
			record.add(field.toString());
			records.add(List.copyOf(record));
		}
		return records;
	}

	private String removeBom(String value) {
		return value.startsWith("\uFEFF") ? value.substring(1) : value;
	}

	private ResponseStatusException badRequest(String reason) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
	}
}
