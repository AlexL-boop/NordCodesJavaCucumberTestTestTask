package utils;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class WireMockManager {
    private static WireMockServer wireMockServer;
    private static final int MOCK_PORT = 8888;

    public static void startServer() {
        if (wireMockServer == null || !wireMockServer.isRunning()) {
            wireMockServer = new WireMockServer(
                    WireMockConfiguration.options().port(MOCK_PORT)
            );
            wireMockServer.start();
            WireMock.configureFor("localhost", MOCK_PORT);
            System.out.println("WireMock сервер запущен на порту: " + MOCK_PORT);
        }
    }

    public static void stopServer() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
            System.out.println("WireMock сервер остановлен");
        }
    }

    public static void resetAll() {
        if (wireMockServer != null) {
            wireMockServer.resetAll();
        }
    }

    public static void setupMockAuthSuccess(String token) {
        stubFor(post(urlEqualTo("/auth"))
                .withRequestBody(equalTo("token=" + token))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"OK\"}")));
    }

    public static void setupMockAuthError(String token) {
        stubFor(post(urlEqualTo("/auth"))
                .withRequestBody(equalTo("token=" + token))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Internal Server Error\"}")));
    }

    public static void setupMockActionSuccess(String token) {
        stubFor(post(urlEqualTo("/doAction"))
                .withRequestBody(equalTo("token=" + token))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"OK\", \"action\":\"completed\"}")));
    }

    public static void setupMockActionError(String token) {
        stubFor(post(urlEqualTo("/doAction"))
                .withRequestBody(equalTo("token=" + token))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Forbidden\"}")));
    }

    public static String getMockUrl() {
        return "http://localhost:" + MOCK_PORT;
    }
}
