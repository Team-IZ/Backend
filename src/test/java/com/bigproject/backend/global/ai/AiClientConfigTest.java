package com.bigproject.backend.global.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-alb-cert(AiClientConfig의 {@code aiOriginRestClient} 주석 참고)를 실제 TLS
 * 핸드셰이크로 검증한다. keytool로 만든 자기서명 인증서(호스트명이 실제 접속 주소와
 * 일부러 다름 -- ALB 상황을 재현)를 쓰는 로컬 HTTPS 서버를 세워, origin 클라이언트는
 * 통과하고 나머지 클라이언트는 거부하는지 직접 확인한다. 인증서 mock이 아니라 진짜
 * 핸드셰이크라 SSLParameters.setEndpointIdentificationAlgorithm(null) 같은 설정 실수를
 * 구조적 검사로는 못 잡는 부분까지 잡는다.
 */
class AiClientConfigTest {

	private static HttpsServer server;
	private static String baseUrl;

	@BeforeAll
	static void startSelfSignedServer() throws Exception {
		// createTempFile()로 미리 만들면 0바이트 빈 파일이 생기는데, keytool -genkeypair는
		// 그 경로에 파일이 이미 있으면 "기존 키스토어를 열어서 추가"로 해석해 빈 파일을 읽다가
		// 실패한다("키 저장소 파일이 존재하지만 비어 있음", 실측) -- 경로만 계산하고 파일 생성은
		// keytool에게 맡긴다.
		File keystoreFile = new File(System.getProperty("java.io.tmpdir"),
				"ai-client-config-test-" + System.nanoTime() + ".p12");
		keystoreFile.deleteOnExit();

		// CN을 실제 접속 주소(127.0.0.1)와 다르게 둔다 -- ALB의 기본 도메인 인증서가
		// 우리가 실제로 접속하는 호스트명과 다른 상황을 그대로 재현한다.
		//
		// PATH의 keytool이 아니라 이 테스트를 실행 중인 JVM과 같은 JDK의 keytool을 쓴다 --
		// 시스템 기본 keytool(예: /usr/bin/keytool)이 다른(더 오래된) JDK일 수 있고, 그렇게
		// 만든 키스토어가 테스트 JVM의 TLS 스택과 버전이 어긋나 실패할 수 있다(실측: 이 환경엔
		// 실제로 둘이 달랐다).
		String keytoolPath = System.getProperty("java.home") + "/bin/keytool";
		Process keytool = new ProcessBuilder(
				keytoolPath, "-genkeypair",
				"-alias", "test",
				"-keyalg", "RSA", "-keysize", "2048",
				"-validity", "1",
				"-keystore", keystoreFile.getAbsolutePath(),
				"-storetype", "PKCS12",
				"-storepass", "changeit",
				"-dname", "CN=mismatched-hostname.example.invalid"
		).redirectErrorStream(true).start();
		if (!keytool.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || keytool.exitValue() != 0) {
			throw new IllegalStateException("keytool 자기서명 인증서 생성 실패 -- 로그: "
					+ new String(keytool.getInputStream().readAllBytes()));
		}

		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		try (var in = Files.newInputStream(keystoreFile.toPath())) {
			keyStore.load(in, "changeit".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(keyStore, "changeit".toCharArray());
		SSLContext serverSslContext = SSLContext.getInstance("TLS");
		serverSslContext.init(kmf.getKeyManagers(), null, null);

		server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext));
		server.createContext("/ping", AiClientConfigTest::respondOk);
		server.start();

		baseUrl = "https://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterAll
	static void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private static void respondOk(HttpExchange exchange) throws IOException {
		byte[] body = "{}".getBytes();
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}

	private static RestClient callOrigin() {
		return new AiClientConfig().aiOriginRestClient(
				baseUrl, "", Duration.ofSeconds(5), Duration.ofSeconds(5), false);
	}

	private static RestClient callProxy() {
		return new AiClientConfig().aiProxyRestClient(
				baseUrl, "", Duration.ofSeconds(5), Duration.ofSeconds(5), false);
	}

	@Test
	void originClientAcceptsAMismatchedSelfSignedCertificate() {
		// 진짜 문제(ALB 인증서 SAN이 접속 호스트명과 다름)를 그대로 재현한 서버에도
		// origin 클라이언트는 접속에 성공해야 한다.
		String body = callOrigin().get().uri("/ping").retrieve().body(String.class);
		assertThat(body).isEqualTo("{}");
	}

	@Test
	void proxyClientStillRejectsTheSameMismatchedCertificate() {
		// D-alb-cert의 COST 문장("다른 두 클라이언트는 그대로 검증한다")이 실제로
		// 지켜지는지 -- 같은 서버에 같은 방식으로 접속해도 proxy는 거부해야 한다.
		assertThatThrownBy(() -> callProxy().get().uri("/ping").retrieve().body(String.class))
				.isInstanceOfAny(ResourceAccessException.class, UncheckedIOException.class);
	}
}
