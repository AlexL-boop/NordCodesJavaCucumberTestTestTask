package hooks;

import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.Scenario;
import io.qameta.allure.Allure;
import utils.ConfigReader;
import utils.WireMockManager;

import java.io.ByteArrayInputStream;
import java.util.UUID;

public class TestHooks {

    private static final ThreadLocal<String> testToken = new ThreadLocal<>();

    @BeforeAll
    public static void beforeAll() {
        System.out.println("=== ИНИЦИАЛИЗАЦИЯ ТЕСТОВОГО ОКРУЖЕНИЯ ===");

        if (ConfigReader.isWireMockEnabled()) {
            WireMockManager.startServer();
            System.out.println("WireMock сервер запущен");
        }

        System.out.println("Базовый URL приложения: " + ConfigReader.getBaseUrl());
        System.out.println("API Key: " + ConfigReader.getApiKey());
    }

    @AfterAll
    public static void afterAll() {
        System.out.println("\n=== ЗАВЕРШЕНИЕ ТЕСТОВОГО ОКРУЖЕНИЯ ===");

        if (ConfigReader.isWireMockEnabled()) {
            WireMockManager.stopServer();
        }
    }

    @Before
    public void beforeScenario(Scenario scenario) {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("НАЧИНАЕТСЯ СЦЕНАРИЙ: " + scenario.getName());
        System.out.println("ТЕГИ: " + scenario.getSourceTagNames());
        System.out.println("=".repeat(50));

        // Генерация уникального токена для сценария, убрана, использован хардкод
        // String token = UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
        testToken.set("A94F2C7D8E1B4A6F9C3D2E5B8A7F1C0D");
        System.out.println("Сгенерирован тестовый токен: " + "A94F2C7D8E1B4A6F9C3D2E5B8A7F1C0D");

        // Сброс WireMock перед каждым сценарием
        if (ConfigReader.isWireMockEnabled()) {
            WireMockManager.resetAll();
        }
    }

    @After
    public void afterScenario(Scenario scenario) {
        System.out.println("\n" + "-".repeat(50));
        System.out.println("ЗАВЕРШЕН СЦЕНАРИЙ: " + scenario.getName());
        System.out.println("СТАТУС: " + scenario.getStatus());

        if (scenario.isFailed()) {
            System.out.println("❌ СЦЕНАРИЙ ПРОВАЛЕН!");

            // Прикрепление информации в Allure отчет
            Allure.addAttachment("Сценарий провален",
                    "text/plain",
                    "Сценарий: " + scenario.getName() + "\n" +
                            "Токен: " + testToken.get() + "\n" +
                            "Статус: " + scenario.getStatus());
        } else {
            System.out.println("✅ СЦЕНАРИЙ УСПЕШНО ВЫПОЛНЕН");
        }
        System.out.println("-".repeat(50) + "\n");

        // Очистка токена
        testToken.remove();
    }

    @Before("@wiremock")
    public void setupWireMock() {
        if (ConfigReader.isWireMockEnabled()) {
            System.out.println("Настройка WireMock для сценария...");
        }
    }

    @After("@cleanup")
    public void cleanupData() {
        System.out.println("Выполнение очистки тестовых данных...");
        // Здесь можно добавить логику очистки БД или других ресурсов
    }

    public static String getCurrentTestToken() {
        return testToken.get();
    }
}