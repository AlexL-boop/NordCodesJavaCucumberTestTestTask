package utils;

import java.io.InputStream;
import java.util.Properties;

public class ConfigReader {
    private static final Properties properties = new Properties();

    static {
        loadProperties();
    }

    private static void loadProperties() {
        try (InputStream input = ConfigReader.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (input == null) {
                System.out.println("Файл config.properties не найден в classpath");
                return;
            }
            properties.load(input);
        } catch (Exception e) {
            System.err.println("Ошибка загрузки config.properties: " + e.getMessage());
        }
    }

    public static String getProperty(String key) {
        return properties.getProperty(key);
    }

    public static String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public static String getBaseUrl() {
        return getProperty("base.url", "http://localhost:8080");
    }

    public static String getApiKey() {
        return getProperty("api.key", "A94F2C7D8E1B4A6F9C3D2E5B8A7F1C0D");
    }

    public static int getMockPort() {
        return Integer.parseInt(getProperty("mock.port", "8888"));
    }

    public static boolean isWireMockEnabled() {
        return Boolean.parseBoolean(getProperty("wiremock.enabled", "true"));
    }

    public static int getTimeout() {
        return Integer.parseInt(getProperty("timeout.ms", "5000"));
    }
}