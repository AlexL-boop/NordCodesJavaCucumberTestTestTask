package utils;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

import static io.restassured.RestAssured.given;

public class HttpUtils {

    public static Response sendPostRequest(String url, Map<String, String> formParams, Map<String, String> headers) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .accept("application/json");

        // Добавляем заголовки
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.header(header.getKey(), header.getValue());
            }
        }

        // Добавляем form параметры
        if (formParams != null) {
            for (Map.Entry<String, String> param : formParams.entrySet()) {
                request.formParam(param.getKey(), param.getValue());
            }
        }

        return request.post(url);
    }

    public static void validateSuccessResponse(Response response) {
        response.then()
                .statusCode(200)
                .assertThat()
                .body("result", org.hamcrest.Matchers.equalTo("OK"));
    }

    public static void validateErrorResponse(Response response) {
        response.then()
                .statusCode(200)
                .assertThat()
                .body("result", org.hamcrest.Matchers.equalTo("ERROR"));
    }
}