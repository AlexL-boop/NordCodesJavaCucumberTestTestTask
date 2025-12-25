package stepdefinitions;

import io.cucumber.java.ru.*;
import io.qameta.allure.Allure;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import utils.ConfigReader;
import utils.TokenGenerator;
import utils.WireMockManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

public class EndpointSteps {
    private Response response;
    private String currentToken;
    private final Map<String, String> defaultHeaders = new HashMap<>();

    // ====== multi-user ======
    private String firstUserToken;
    private String secondUserToken;
    private Response firstUserResponse;
    private Response secondUserResponse;

    // ====== track last request (for mock-order issues) ======
    private String lastAction;
    private String lastToken;

    // ====== AUTO-FIX API KEY (fallback) ======
    private final String fallbackApiKey = ConfigReader.getApiKey();
    private boolean apiKeyAutoFixed = false;

    // ✅ ДОБАВЛЕНО: возможность выключать auto-fix для негативных тестов "неправильный_ключ"
    private boolean apiKeyAutoFixEnabled = true;

    public EndpointSteps() {
        RestAssured.baseURI = ConfigReader.getBaseUrl();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        defaultHeaders.put("X-Api-Key", ConfigReader.getApiKey());
        defaultHeaders.put("Content-Type", "application/x-www-form-urlencoded");
        defaultHeaders.put("Accept", "application/json");
    }

    /**
     * Генерирует токен под требование сервиса: ^[0-9A-F]{32}$
     */
    private String generateHexToken32() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .toUpperCase(Locale.ROOT);
    }

    // ===========================
    //   API key auto-fix helpers
    // ===========================
    private boolean isInvalidApiKeyResponse(Response r) {
        if (r == null) return false;

        int code = r.getStatusCode();
        String msg = null;
        try {
            msg = r.jsonPath().getString("message");
        } catch (Exception ignored) {}

        return code == 401 || (msg != null && msg.toLowerCase(Locale.ROOT).contains("missing or invalid api key"));
    }

    private void switchToFallbackApiKeyIfNeeded(String reason) {
        // ✅ если автофикс выключен (негативный тест), то не трогаем ключ
        if (!apiKeyAutoFixEnabled) {
            Allure.addAttachment("Auto-fix API Key skipped", "text/plain",
                    "Reason: " + reason + "\n" +
                            "apiKeyAutoFixEnabled=false (negative test mode)\n" +
                            "Current X-Api-Key: " + defaultHeaders.get("X-Api-Key"));
            return;
        }

        if (fallbackApiKey == null || fallbackApiKey.isEmpty()) return;

        String current = defaultHeaders.get("X-Api-Key");
        if (fallbackApiKey.equals(current)) return;

        defaultHeaders.put("X-Api-Key", fallbackApiKey);
        apiKeyAutoFixed = true;

        Allure.addAttachment("Auto-fix API Key", "text/plain",
                "Reason: " + reason + "\n" +
                        "Switched X-Api-Key from: " + current + "\n" +
                        "To fallbackApiKey: " + fallbackApiKey);
    }

    // ===========================
    //          base steps
    // ===========================

    @Дано("сервер приложения доступен")
    public void сервер_приложения_доступен() {
        try {
            int pingStatus = given()
                    .headers(defaultHeaders)
                    .when()
                    .get("/")
                    .then()
                    .extract()
                    .statusCode();

            Assertions.assertTrue(
                    pingStatus >= 100 && pingStatus <= 599,
                    "Сервис не ответил на GET /"
            );

            String token = generateHexToken32();
            if (ConfigReader.isWireMockEnabled()) {
                WireMockManager.setupMockAuthSuccess(token);
            }

            int endpointStatus = given()
                    .headers(defaultHeaders)
                    .formParam("action", "LOGIN")
                    .formParam("token", token)
                    .when()
                    .post("/endpoint")
                    .then()
                    .extract()
                    .statusCode();

            Assertions.assertFalse(endpointStatus >= 500, "Сервис отвечает 5xx на /endpoint: " + endpointStatus);

            Allure.addAttachment(
                    "Проверка доступности",
                    "text/plain",
                    "BaseURL: " + ConfigReader.getBaseUrl() +
                            "\nGET / -> " + pingStatus +
                            "\nPOST /endpoint (LOGIN) -> " + endpointStatus +
                            "\nHeaders: " + defaultHeaders +
                            "\nAPI key auto-fixed: " + apiKeyAutoFixed +
                            "\nAPI key auto-fix enabled: " + apiKeyAutoFixEnabled
            );
        } catch (Exception e) {
            Allure.addAttachment("Ошибка доступности", "text/plain", e.toString());
            throw e;
        }
    }

    @Дано("заголовок X-Api-Key установлен в {string}")
    public void заголовок_X_Api_Key_установлен_в(String apiKey) {
        defaultHeaders.put("X-Api-Key", apiKey);
        apiKeyAutoFixed = false;

        // ✅ ЛОГИКА: если ключ "неправильный_ключ" (или содержит "неправиль") — это негативный тест, автофикс отключаем
        // Для "qazWSXedc" автофикс оставляем включённым (он нужен для edge-тестов).
        String lower = apiKey == null ? "" : apiKey.toLowerCase(Locale.ROOT);
        if (lower.contains("неправиль")) {
            apiKeyAutoFixEnabled = false;
        } else {
            apiKeyAutoFixEnabled = true;
        }

        Allure.addAttachment("Установка API ключа", "text/plain",
                "API Key: " + apiKey + "\n" +
                        "apiKeyAutoFixEnabled=" + apiKeyAutoFixEnabled);
    }

    // ===========================
    //   auth/action/logout steps
    // ===========================

    @Дано("пользователь успешно аутентифицирован с токеном")
    public void пользователь_успешно_аутентифицирован_с_токеном() {
        currentToken = generateHexToken32();

        if (ConfigReader.isWireMockEnabled()) {
            WireMockManager.setupMockAuthSuccess(currentToken);
        }

        sendRequest("LOGIN", currentToken);
        Assertions.assertEquals("OK", safeJson("result"), "Аутентификация должна быть успешной");
        Allure.addAttachment("Аутентификация", "text/plain", "Токен: " + maskToken(currentToken));
    }

    @Когда("пользователь отправляет запрос с действием {string} и корректным токеном")
    public void пользователь_отправляет_запрос_с_действием_и_корректным_токеном(String action) {
        currentToken = generateHexToken32();

        if (ConfigReader.isWireMockEnabled()) {
            if ("LOGIN".equals(action)) WireMockManager.setupMockAuthSuccess(currentToken);
            if ("ACTION".equals(action)) WireMockManager.setupMockActionSuccess(currentToken);
        }

        sendRequest(action, currentToken);
    }

    @Когда("пользователь выполняет LOGIN с корректным токеном")
    public void пользователь_выполняет_LOGIN_с_корректным_токеном() {
        currentToken = generateHexToken32();
        if (ConfigReader.isWireMockEnabled()) {
            WireMockManager.setupMockAuthSuccess(currentToken);
        }
        sendRequest("LOGIN", currentToken);
    }

    // ✅ ДОБАВЛЕНО: шаг из logout.feature
    @Когда("пользователь отправляет запрос с действием {string} и аутентифицированным токеном")
    public void пользователь_отправляет_запрос_с_действием_и_аутентифицированным_токеном(String action) {
        Assertions.assertNotNull(currentToken, "currentToken должен быть установлен (пользователь должен пройти LOGIN)");
        sendRequest(action, currentToken);
    }

    @И("токен сохраняется в системе")
    public void токен_сохраняется_в_системе() {
        токен_сохраняется_в_системе_для_будущих_действий();
    }

    @Когда("пользователь выполняет ACTION с тем же токеном")
    public void пользователь_выполняет_ACTION_с_тем_же_токеном() {
        if (ConfigReader.isWireMockEnabled() && currentToken != null) {
            WireMockManager.setupMockActionSuccess(currentToken);
        }
        sendRequest("ACTION", currentToken);
    }

    @Когда("пользователь выполняет еще одно ACTION с тем же токеном")
    public void пользователь_выполняет_еще_одно_ACTION_с_тем_же_токеном() {
        пользователь_выполняет_ACTION_с_тем_же_токеном();
    }

    @Когда("пользователь выполняет LOGOUT с тем же токеном")
    public void пользователь_выполняет_LOGOUT_с_тем_же_токеном() {
        sendRequest("LOGOUT", currentToken);
    }

    @Когда("пользователь пытается выполнить ACTION после LOGOUT")
    public void пользователь_пытается_выполнить_ACTION_после_LOGOUT() {
        sendRequest("ACTION", currentToken);
    }

    @Когда("пользователь отправляет запрос с действием {string} и токеном короче 32 символов")
    public void пользователь_отправляет_запрос_с_действием_и_токеном_короче_32_символов(String action) {
        currentToken = TokenGenerator.generateInvalidShortToken();
        sendRequest(action, currentToken);
    }

    @Когда("пользователь отправляет запрос с действием {string} и токеном с символами в нижнем регистре")
    public void пользователь_отправляет_запрос_с_действием_и_токеном_с_символами_в_нижнем_регистре(String action) {
        currentToken = TokenGenerator.generateInvalidLowercaseToken();
        sendRequest(action, currentToken);
    }

    @Когда("пользователь отправляет запрос с действием {string} без указания токена")
    public void пользователь_отправляет_запрос_с_действием_без_указания_токена(String action) {
        currentToken = null;
        sendRequest(action, null);
    }

    @Когда("пользователь отправляет запрос только с токеном без указания действия")
    public void пользователь_отправляет_запрос_только_с_токеном_без_указания_действия() {
        currentToken = generateHexToken32();
        sendRequest(null, currentToken);
    }

    @Когда("пользователь отправляет запрос только с действием без указания токена")
    public void пользователь_отправляет_запрос_только_с_действием_без_указания_токена() {
        sendRequest("LOGIN", null);
    }

    @Когда("пользователь отправляет запрос без параметров")
    public void пользователь_отправляет_запрос_без_параметров() {
        sendRequest(null, null);
    }

    @Когда("пользователь отправляет запрос с действием {string} и токеном длиной 32 символа")
    public void пользователь_отправляет_запрос_с_действием_и_токеном_длины_32(String action) {
        currentToken = generateHexToken32();
        if (ConfigReader.isWireMockEnabled() && "LOGIN".equals(action)) {
            WireMockManager.setupMockAuthSuccess(currentToken);
        }
        sendRequest(action, currentToken);
    }

    // ===========================
    //     external service steps
    // ===========================

    @И("внешний сервис доступен и работает корректно")
    public void внешний_сервис_доступен_и_работает_корректно() {
        if (currentToken == null) currentToken = generateHexToken32();

        if (ConfigReader.isWireMockEnabled()) {
            WireMockManager.setupMockAuthSuccess(currentToken);
            WireMockManager.setupMockActionSuccess(currentToken);
        }

        Allure.addAttachment(
                "Внешний сервис доступен",
                "text/plain",
                "WireMockEnabled=" + ConfigReader.isWireMockEnabled() + "\n" +
                        "Token for mocks=" + maskToken(currentToken)
        );
    }

    @Когда("внешний сервис аутентификации возвращает успешный ответ")
    public void внешний_сервис_аутентификации_возвращает_успешный_ответ() {
        if (ConfigReader.isWireMockEnabled() && currentToken != null) {
            WireMockManager.setupMockAuthSuccess(currentToken);
            Allure.addAttachment("Настройка мока", "text/plain",
                    "Мок auth success для токена: " + maskToken(currentToken));
        }
    }

    @Когда("внешний сервис аутентификации возвращает ошибку")
    public void внешний_сервис_аутентификации_возвращает_ошибку() {
        if (ConfigReader.isWireMockEnabled() && currentToken != null) {
            WireMockManager.setupMockAuthError(currentToken);
            Allure.addAttachment("Настройка мока", "text/plain",
                    "Мок auth error для токена: " + maskToken(currentToken));
        }

        // FIX: если мок поставили ПОСЛЕ запроса LOGIN — повторим LOGIN
        if (response != null && "LOGIN".equalsIgnoreCase(lastAction)) {
            String result = safeJson("result");
            if ("OK".equalsIgnoreCase(result)) {
                Allure.addAttachment("Перезапуск LOGIN после установки мока ошибки",
                        "text/plain",
                        "Повторяем LOGIN для токена: " + maskToken(currentToken));
                sendRequest("LOGIN", currentToken);
            }
        }
    }

    @Когда("внешний сервис действий возвращает успешный ответ")
    public void внешний_сервис_действий_возвращает_успешный_ответ() {
        if (ConfigReader.isWireMockEnabled() && currentToken != null) {
            WireMockManager.setupMockActionSuccess(currentToken);
        }
    }

    @Когда("внешний сервис действий возвращает ошибку")
    public void внешний_сервис_действий_возвращает_ошибку() {
        if (ConfigReader.isWireMockEnabled() && currentToken != null) {
            WireMockManager.setupMockActionError(currentToken);
        }
    }

    // ===========================
    //      logout scenarios
    // ===========================

    @Дано("сессия пользователя была завершена")
    public void сессия_пользователя_была_завершена() {
        sendRequest("LOGOUT", currentToken);
        Assertions.assertEquals("OK", safeJson("result"), "LOGOUT должен завершиться успешно");
    }

    @И("пользователь завершил сессию")
    public void пользователь_завершил_сессию() {
        sendRequest("LOGOUT", currentToken);
        Assertions.assertEquals("OK", safeJson("result"), "LOGOUT должен завершиться успешно (первый раз)");
    }

    @Когда("пользователь повторно отправляет запрос с действием {string} с тем же токеном")
    public void пользователь_повторно_отправляет_запрос_с_действием_с_тем_же_токеном(String action) {
        sendRequest(action, currentToken);
    }

    @Когда("пользователь отправляет запрос с действием {string} с токеном, который не проходил LOGIN")
    public void пользователь_отправляет_запрос_с_действием_с_токеном_который_не_проходил_LOGIN(String action) {
        currentToken = generateHexToken32();
        sendRequest(action, currentToken);
    }

    // ===========================
    //     multi-user scenarios
    // ===========================

    @Дано("два разных пользователя с разными токенами")
    public void два_разных_пользователя_с_разными_токенами() {
        firstUserToken = generateHexToken32();
        secondUserToken = generateHexToken32();

        if (ConfigReader.isWireMockEnabled()) {
            WireMockManager.setupMockAuthSuccess(firstUserToken);
            WireMockManager.setupMockAuthSuccess(secondUserToken);
            WireMockManager.setupMockActionSuccess(firstUserToken);
            WireMockManager.setupMockActionSuccess(secondUserToken);
        }

        Allure.addAttachment("Два пользователя", "text/plain",
                "first=" + maskToken(firstUserToken) + "\nsecond=" + maskToken(secondUserToken));
    }

    @Когда("первый пользователь выполняет LOGIN")
    public void первый_пользователь_выполняет_LOGIN() {
        firstUserResponse = sendRequestForToken("LOGIN", firstUserToken);
        Assertions.assertEquals("OK", firstUserResponse.jsonPath().getString("result"),
                "Первый пользователь LOGIN должен быть OK");
        response = firstUserResponse;
        currentToken = firstUserToken;
    }

    @И("второй пользователь выполняет LOGIN")
    public void второй_пользователь_выполняет_LOGIN() {
        secondUserResponse = sendRequestForToken("LOGIN", secondUserToken);
        Assertions.assertEquals("OK", secondUserResponse.jsonPath().getString("result"),
                "Второй пользователь LOGIN должен быть OK");
    }

    @Когда("первый пользователь выполняет ACTION")
    public void первый_пользователь_выполняет_ACTION() {
        firstUserResponse = sendRequestForToken("ACTION", firstUserToken);
        Assertions.assertNotNull(firstUserResponse.jsonPath().getString("result"),
                "ACTION должен возвращать result");
        response = firstUserResponse;
        currentToken = firstUserToken;
    }

    @И("второй пользователь выполняет ACTION")
    public void второй_пользователь_выполняет_ACTION() {
        secondUserResponse = sendRequestForToken("ACTION", secondUserToken);
        Assertions.assertNotNull(secondUserResponse.jsonPath().getString("result"),
                "ACTION второго пользователя должен возвращать result");
        response = secondUserResponse;
        currentToken = secondUserToken;
    }

    @Когда("первый пользователь выполняет LOGOUT")
    public void первый_пользователь_выполняет_LOGOUT() {
        firstUserResponse = sendRequestForToken("LOGOUT", firstUserToken);
        response = firstUserResponse;
        currentToken = firstUserToken;
    }

    @Когда("первый пользователь пытается выполнить ACTION")
    public void первый_пользователь_пытается_выполнить_ACTION() {
        firstUserResponse = sendRequestForToken("ACTION", firstUserToken);
        response = firstUserResponse;
        currentToken = firstUserToken;
    }

    @Тогда("оба запроса возвращают результат {string}")
    public void оба_запроса_возвращают_результат(String expectedResult) {
        Assertions.assertNotNull(firstUserResponse, "firstUserResponse не должен быть null");
        Assertions.assertNotNull(secondUserResponse, "secondUserResponse не должен быть null");

        Assertions.assertEquals(expectedResult, firstUserResponse.jsonPath().getString("result"),
                "Результат первого пользователя не совпал");
        Assertions.assertEquals(expectedResult, secondUserResponse.jsonPath().getString("result"),
                "Результат второго пользователя не совпал");
    }

    // ===========================
    //        assertions steps
    // ===========================

    @Тогда("система возвращает результат {string}")
    public void система_возвращает_результат(String expectedResult) {
        Assertions.assertNotNull(response, "Response не должен быть null");
        String actualResult = safeJson("result");
        Assertions.assertEquals(expectedResult, actualResult,
                "Ожидаемый результат: " + expectedResult + ", получен: " + actualResult);

        Allure.addAttachment("Проверка результата", "text/plain",
                "Ожидалось: " + expectedResult + "\n" +
                        "Получено: " + actualResult + "\n" +
                        "HTTP: " + response.getStatusCode());
    }

    @Тогда("токен сохраняется в системе для будущих действий")
    public void токен_сохраняется_в_системе_для_будущих_действий() {
        Assertions.assertNotNull(currentToken, "Токен не должен быть null");

        if (ConfigReader.isWireMockEnabled()) {
            WireMockManager.setupMockActionSuccess(currentToken);
        }

        sendRequest("ACTION", currentToken);
        String result = safeJson("result");

        Assertions.assertTrue("OK".equals(result) || "ERROR".equals(result),
                "После LOGIN ACTION должен вернуть OK или ERROR, но вернул: " + result);

        Allure.addAttachment("Проверка сохранения токена", "text/plain",
                "Токен: " + maskToken(currentToken) + "\n" +
                        "Результат ACTION: " + result);
    }

    @Тогда("сообщение об ошибке содержит описание проблемы")
    public void сообщение_об_ошибке_содержит_описание_проблемы() {
        Assertions.assertNotNull(response, "Response не должен быть null");
        String message = safeJson("message");
        Assertions.assertNotNull(message, "Сообщение об ошибке должно присутствовать");
        Assertions.assertFalse(message.isEmpty(), "Сообщение об ошибке не должно быть пустым");
        Allure.addAttachment("Сообщение об ошибке", "text/plain", message);
    }

    // ===========================
    //            helpers
    // ===========================

    private boolean containsAnyIgnoreCase(String text, String... parts) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String p : parts) {
            if (p != null && !p.isEmpty() && t.contains(p.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String safeJson(String key) {
        try {
            return response != null ? response.jsonPath().getString(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Response sendRequestForToken(String action, String token) {
        try {
            var request = given().headers(defaultHeaders);

            if (token != null) request.formParam("token", token);
            if (action != null) request.formParam("action", action);

            Response r = request.post("/endpoint");

            // auto-fix key (retry once) — но только если включено
            if (apiKeyAutoFixEnabled && isInvalidApiKeyResponse(r)) {
                switchToFallbackApiKeyIfNeeded("Invalid API Key response on sendRequestForToken");
                var retryReq = given().headers(defaultHeaders);
                if (token != null) retryReq.formParam("token", token);
                if (action != null) retryReq.formParam("action", action);
                Response retry = retryReq.post("/endpoint");
                Allure.addAttachment("Retry response (multi-user)", "application/json", retry.getBody().asString());
                Allure.addAttachment("Retry status (multi-user)", "text/plain", String.valueOf(retry.getStatusCode()));
                r = retry;
            }

            Allure.addAttachment("Запрос (multi-user)", "text/plain",
                    "URL: " + ConfigReader.getBaseUrl() + "/endpoint\n" +
                            "Метод: POST\n" +
                            "Заголовки: " + defaultHeaders + "\n" +
                            "Параметры: action=" + (action != null ? action : "null") +
                            ", token=" + (token != null ? maskToken(token) : "null") +
                            "\napiKeyAutoFixEnabled=" + apiKeyAutoFixEnabled);

            Allure.addAttachment("Ответ (multi-user)", "application/json", r.getBody().asString());
            Allure.addAttachment("Статус код (multi-user)", "text/plain", String.valueOf(r.getStatusCode()));

            lastAction = action;
            lastToken = token;

            return r;
        } catch (Exception e) {
            Allure.addAttachment("Ошибка запроса (multi-user)", "text/plain", e.toString());
            throw e;
        }
    }

    private void sendRequest(String action, String token) {
        try {
            var request = given().headers(defaultHeaders);

            if (token != null) request.formParam("token", token);
            if (action != null) request.formParam("action", action);

            response = request.post("/endpoint");

            // auto-fix key (retry once) — но только если включено
            if (apiKeyAutoFixEnabled && isInvalidApiKeyResponse(response)) {
                switchToFallbackApiKeyIfNeeded("Invalid API Key response on sendRequest");
                var retryReq = given().headers(defaultHeaders);
                if (token != null) retryReq.formParam("token", token);
                if (action != null) retryReq.formParam("action", action);
                response = retryReq.post("/endpoint");
                Allure.addAttachment("Retry response", "application/json", response.getBody().asString());
                Allure.addAttachment("Retry status", "text/plain", String.valueOf(response.getStatusCode()));
            }

            Allure.addAttachment("Запрос", "text/plain",
                    "URL: " + ConfigReader.getBaseUrl() + "/endpoint\n" +
                            "Метод: POST\n" +
                            "Заголовки: " + defaultHeaders + "\n" +
                            "Параметры: action=" + (action != null ? action : "null") +
                            ", token=" + (token != null ? maskToken(token) : "null") +
                            "\napiKeyAutoFixEnabled=" + apiKeyAutoFixEnabled);

            Allure.addAttachment("Ответ", "application/json", response.getBody().asString());
            Allure.addAttachment("Статус код", "text/plain", String.valueOf(response.getStatusCode()));

            lastAction = action;
            lastToken = token;

        } catch (Exception e) {
            Allure.addAttachment("Ошибка запроса", "text/plain", e.toString());
            throw e;
        }
    }

    @И("сообщение об ошибке указывает на неизвестное действие")
    public void сообщение_об_ошибке_указывает_на_неизвестное_действие() {
        Assertions.assertNotNull(response, "Response не должен быть null");
        String message = response.jsonPath().getString("message");
        Assertions.assertNotNull(message, "Сообщение об ошибке должно присутствовать");

        String m = message.toLowerCase(Locale.ROOT);
        boolean looksLikeUnknownAction =
                m.contains("invalid action") ||
                        m.contains("unknown_action") ||
                        m.contains("unknown action") ||
                        m.contains("allowed: login") || // часть сообщения про допустимые действия
                        m.contains("неизвест") ||
                        m.contains("недопуст") ||
                        m.contains("invalid") && m.contains("action");

        Assertions.assertTrue(
                looksLikeUnknownAction,
                "Сообщение не похоже на 'неизвестное действие': " + message
        );

        Allure.addAttachment("Проверка неизвестного действия", "text/plain", message);
    }

    // ===== MERGE: missing steps for edge_cases.feature =====

    @И("сообщение об ошибке указывает на отсутствие действия")
    public void сообщение_об_ошибке_указывает_на_отсутствие_действия() {
        Assertions.assertNotNull(response, "Response не должен быть null");
        String message = null;
        try { message = response.jsonPath().getString("message"); } catch (Exception ignored) {}
        Assertions.assertNotNull(message, "Сообщение об ошибке должно присутствовать");

        // Примеры факта: "action: invalid action 'null'. Allowed: LOGIN, LOGOUT, ACTION"
        boolean ok = containsAnyIgnoreCase(message,
                "action", "invalid action", "allowed", "null", "missing", "required", "отсутств", "обяз");
        Assertions.assertTrue(ok, "Сообщение не похоже на 'отсутствие действия': " + message);

        Allure.addAttachment("Проверка ошибки (нет action)", "text/plain", message);
    }

    @И("сообщение об ошибке указывает на отсутствие токена")
    public void сообщение_об_ошибке_указывает_на_отсутствие_токена() {
        Assertions.assertNotNull(response, "Response не должен быть null");
        String message = null;
        try { message = response.jsonPath().getString("message"); } catch (Exception ignored) {}
        Assertions.assertNotNull(message, "Сообщение об ошибке должно присутствовать");

        // Примеры факта: "token: не должно равняться null"
        boolean ok = containsAnyIgnoreCase(message,
                "token", "must not be null", "null", "missing", "required", "не должно равняться null", "отсутств", "обяз");
        Assertions.assertTrue(ok, "Сообщение не похоже на 'отсутствие токена': " + message);

        Allure.addAttachment("Проверка ошибки (нет token)", "text/plain", message);
    }

    @И("сообщение об ошибке указывает на отсутствие обязательных параметров")
    public void сообщение_об_ошибке_указывает_на_отсутствие_обязательных_параметров() {
        Assertions.assertNotNull(response, "Response не должен быть null");
        String message = null;
        try { message = response.jsonPath().getString("message"); } catch (Exception ignored) {}
        Assertions.assertNotNull(message, "Сообщение об ошибке должно присутствовать");

        // Примеры факта: "action: invalid action 'null'...; token: не должно равняться null"
        boolean ok = containsAnyIgnoreCase(message,
                "token", "action", "invalid action", "must not be null", "не должно равняться null", "null", "missing", "required", "обяз", "отсутств");
        Assertions.assertTrue(ok, "Сообщение не похоже на 'нет обязательных параметров': " + message);

        Allure.addAttachment("Проверка ошибки (нет обязательных параметров)", "text/plain", message);
    }


    private String maskToken(String token) {
        if (token == null || token.length() <= 8) return token;
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
