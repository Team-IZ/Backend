package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 교육생 일괄 등록 CSV 파서.
 *
 * <h2>25차 Q1 — 받는 범위를 넓혔다</h2>
 *
 * <p>종전에는 <b>UTF-8 · `이름,이메일` 두 열 · 그 순서</b>만 받았다. 그런데 운영자가 받는 명단은
 * 대개 엑셀 파일이고, 윈도우 엑셀의 「CSV(쉼표로 분리)」 기본 저장이 <b>CP949</b>다. 그래서 사람이
 * 매번 「CSV UTF-8」을 따로 골라야 했고, 열도 딱 두 개만 남겨야 했다 — 규칙이 사람에게 있으면
 * 사람이 틀린다.
 *
 * <p>두 가지를 넓혔다.
 * <ul>
 *   <li><b>인코딩</b> — UTF-8로 못 읽으면 CP949(MS949)로 한 번 더 읽는다. 둘 다 실패해야 400이다</li>
 *   <li><b>열</b> — 머리글에서 `이름`·`이메일`을 <b>이름으로 찾는다.</b> 순서가 달라도 되고
 *       (`이메일,이름`) 다른 열이 섞여 있어도 된다(`번호,이메일,소속,이름`). 모르는 열은 무시한다</li>
 * </ul>
 *
 * <p><b>기존 파일은 그대로 통과한다</b> — UTF-8 `이름,이메일`은 넓힌 규칙의 부분집합이다.
 *
 * <p>{@code .xlsx}는 받지 않는다(프론트 제안 「다」). 파싱 라이브러리가 통째로 하나 더 붙는데,
 * 엑셀 파일은 서식·이미지 때문에 커지기 쉬워 앞단 Lambda의 6MB(base64로 부풀어 실질 4.5MB) 상한에
 * 먼저 걸린다 — 라이브러리를 넣고도 "큰 파일은 안 된다"가 남는다. 「CSV로 저장」 한 번이면
 * 위 두 완화로 그대로 올라간다.
 */
@Component
public class TraineeCsvParser {
	private static final long MAX_FILE_SIZE = 1024 * 1024;
	private static final int MAX_TRAINEE_COUNT = 1000;
	private static final String NAME_HEADER = "이름";
	private static final String EMAIL_HEADER = "이메일";

	/**
	 * 윈도우 엑셀이 「CSV(쉼표로 분리)」로 저장할 때 쓰는 한국어 코드페이지.
	 *
	 * <p>{@code MS949}는 JDK가 항상 들고 있는 표준 확장 문자셋이라 이름으로 찾아도 안전하다.
	 */
	private static final Charset CP949 = Charset.forName("MS949");

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

		HeaderLayout layout = readHeader(records.get(0));
		List<TraineeCsvRow> trainees = new ArrayList<>();
		for (int index = 1; index < records.size(); index++) {
			List<String> record = records.get(index);
			if (isBlankRecord(record)) {
				continue;
			}
			// 열을 이름으로 찾으므로 "두 열이어야 한다"가 아니라 "그 두 열이 있어야 한다"로 본다.
			if (record.size() <= layout.maxIndex()) {
				throw badRequest((index + 1) + "행에 '" + NAME_HEADER + "', '" + EMAIL_HEADER + "' 열의 값이 없습니다.");
			}
			trainees.add(new TraineeCsvRow(
					index + 1,
					record.get(layout.nameIndex()).trim(),
					record.get(layout.emailIndex()).trim()));
		}
		if (trainees.isEmpty()) {
			throw badRequest("CSV 파일에 교육생 데이터가 없습니다.");
		}
		if (trainees.size() > MAX_TRAINEE_COUNT) {
			throw badRequest("한 번에 최대 1000명의 교육생을 초대할 수 있습니다.");
		}
		return List.copyOf(trainees);
	}

	/** 머리글에서 찾아낸 두 열의 자리. {@code maxIndex}는 데이터 행이 최소 몇 칸이어야 하는지다. */
	private record HeaderLayout(int nameIndex, int emailIndex) {
		private int maxIndex() {
			return Math.max(nameIndex, emailIndex);
		}
	}

	/**
	 * UTF-8로 먼저 읽고, 실패하면 CP949로 한 번 더 읽는다(25차 Q1).
	 *
	 * <p>순서가 중요하다. CP949는 대부분의 바이트열을 오류 없이 "읽어내" 버리므로 먼저 시도하면
	 * 정상 UTF-8 한글이 깨진 글자로 통과한다. UTF-8을 엄격 모드로 먼저 걸어야 그 사고가 없다.
	 */
	private String decode(MultipartFile file) {
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException exception) {
			throw new ApiException(MemberErrorCode.CSV_FORMAT_INVALID, "CSV 파일을 읽을 수 없습니다.", exception);
		}

		String utf8 = decodeStrictly(bytes, StandardCharsets.UTF_8);
		if (utf8 != null) {
			return utf8;
		}
		String cp949 = decodeStrictly(bytes, CP949);
		if (cp949 != null) {
			return cp949;
		}
		throw badRequest("CSV 파일은 UTF-8 또는 CP949 인코딩이어야 합니다.");
	}

	/** 읽어내지 못하면 {@code null}. 예외를 흐름 제어로 쓰지 않으려고 값으로 돌려준다. */
	private String decodeStrictly(byte[] bytes, Charset charset) {
		try {
			return charset.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes))
					.toString();
		} catch (CharacterCodingException exception) {
			return null;
		}
	}

	/**
	 * 머리글에서 {@code 이름}·{@code 이메일} 열의 자리를 찾는다(25차 Q1).
	 *
	 * <p>순서·개수를 고정하지 않는다 — 엑셀에서 내보낸 명단은 `번호`·`소속` 같은 열을 달고 오고,
	 * 그것을 지우는 일이 사람 몫으로 남아 있었다. 모르는 열은 그냥 읽지 않는다.
	 */
	private HeaderLayout readHeader(List<String> header) {
		int nameIndex = -1;
		int emailIndex = -1;
		for (int index = 0; index < header.size(); index++) {
			// BOM은 첫 열에만 붙지만, 열 순서가 자유로워졌으므로 전부 벗겨 놓고 본다.
			String column = removeBom(header.get(index)).trim();
			if (NAME_HEADER.equals(column) && nameIndex < 0) {
				nameIndex = index;
			} else if (EMAIL_HEADER.equals(column) && emailIndex < 0) {
				emailIndex = index;
			}
		}
		if (nameIndex < 0 || emailIndex < 0) {
			throw badRequest("CSV 첫 행에 '" + NAME_HEADER + "', '" + EMAIL_HEADER
					+ "' 열이 있어야 합니다(순서는 상관없고 다른 열이 섞여 있어도 됩니다).");
		}
		return new HeaderLayout(nameIndex, emailIndex);
	}

	/** 파일 끝의 빈 줄이나 쉼표만 있는 줄. 열이 늘어나면 `,,,`가 흔해져 칸 수로 판정하지 않는다. */
	private boolean isBlankRecord(List<String> record) {
		return record.stream().allMatch(String::isBlank);
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

	/**
	 * CSV 형식 오류는 코드를 하나로 묶는다 — 화면이 하는 일이 "파일을 고쳐 다시 올리세요"로
	 * 모두 같기 때문이다. 어느 행이 왜 틀렸는지는 message에 담아 사람이 읽게 한다.
	 */
	private ApiException badRequest(String reason) {
		return new ApiException(MemberErrorCode.CSV_FORMAT_INVALID, reason);
	}
}
