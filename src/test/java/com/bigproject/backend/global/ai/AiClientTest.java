package com.bigproject.backend.global.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-connection-vs-timeout(2026-08-21)의 핵심 분기(연결 자체가 안 됨 vs 응답이 느림)를
 * 실제 예외 타입으로 검증한다. {@code AiClient.translate()}(4xx·5xx 응답 해석)는 이미
 * {@code HttpAnalysisServerClientTest} 등 다른 곳에서 간접 검증되므로 건드리지 않는다.
 */
class AiClientTest {

	@Test
	void mapsConnectionRefusedToConnectionRefusedFailureCode() throws IOException {
		// 아무도 안 듣는 로컬 포트 — bind만 하고 accept 없이 즉시 닫아 "연결 거부"를 보장한다.
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		AiClient client = new AiClient(RestClient.builder()
				.baseUrl("http://127.0.0.1:" + closedPort)
				.requestFactory(new SimpleClientHttpRequestFactory())
				.build());

		assertThatThrownBy(() -> client.get("/ping", String.class, null))
				.isInstanceOf(AiCallException.class)
				.satisfies(exception -> {
					AiCallException aiCallException = (AiCallException) exception;
					assertThat(aiCallException.failureCode()).isEqualTo("CONNECTION_REFUSED");
					assertThat(aiCallException.retryable()).isTrue();
					assertThat(aiCallException.getCause()).hasCauseInstanceOf(ConnectException.class);
				});
	}

	@Test
	void mapsSocketTimeoutToTimeoutFailureCode() {
		AiClient client = new AiClient(RestClient.builder()
				.baseUrl("http://127.0.0.1:1")
				.requestFactory(new ThrowingRequestFactory(new SocketTimeoutException("read timed out")))
				.build());

		assertThatThrownBy(() -> client.get("/ping", String.class, null))
				.isInstanceOf(AiCallException.class)
				.satisfies(exception -> {
					AiCallException aiCallException = (AiCallException) exception;
					// 종전 동작 보존: 연결 거부가 아닌 IOException은 그대로 TIMEOUT이다.
					assertThat(aiCallException.failureCode()).isEqualTo("TIMEOUT");
					assertThat(aiCallException.retryable()).isTrue();
				});
	}

	/** {@code createRequest}는 성공하지만 {@code execute()}가 주어진 IOException을 던지는 가짜 팩토리. */
	private record ThrowingRequestFactory(IOException toThrow) implements ClientHttpRequestFactory {
		@Override
		public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
			return new ThrowingRequest(uri, httpMethod, toThrow);
		}
	}

	private record ThrowingRequest(URI uri, HttpMethod method, IOException toThrow)
			implements ClientHttpRequest {

		@Override
		public ClientHttpResponse execute() throws IOException {
			throw toThrow;
		}

		@Override
		public OutputStream getBody() {
			return new ByteArrayOutputStream();
		}

		@Override
		public HttpHeaders getHeaders() {
			return new HttpHeaders();
		}

		@Override
		public HttpMethod getMethod() {
			return method;
		}

		@Override
		public URI getURI() {
			return uri;
		}

		@Override
		public java.util.Map<String, Object> getAttributes() {
			return new java.util.HashMap<>();
		}
	}
}
