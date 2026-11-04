package com.click.data.remote;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistrationContractTest {
    private JSONObject document(int minimum, boolean withName) throws JSONException {
        JSONObject properties = new JSONObject()
                .put("phone_number", new JSONObject().put("type", "string"))
                .put("password", new JSONObject().put("type", "string")
                        .put("minLength", minimum).put("maxLength", 4))
                .put("age_group", new JSONObject().put("type", "string"))
                .put("occupation_category", new JSONObject().put("type", "string"));
        if (withName) properties.put("name", new JSONObject().put("type", "string"));
        return new JSONObject()
                .put("paths", new JSONObject().put("/api/v1/auth/register", new JSONObject()
                        .put("post", new JSONObject().put("requestBody", new JSONObject()
                                .put("content", new JSONObject().put("application/json", new JSONObject()
                                        .put("schema", new JSONObject().put("$ref",
                                                "#/components/schemas/RegisterRequest"))))))))
                .put("components", new JSONObject().put("schemas", new JSONObject()
                        .put("RegisterRequest", new JSONObject().put("additionalProperties", false)
                                .put("properties", properties))));
    }

    @Test public void phoneAndFourDigitPinReachServer() throws Exception {
        RegistrationContract contract = RegistrationContract.fromOpenApi(document(4, true));
        JSONObject body = contract.request("Test", "01012345678", "0123");
        assertEquals("0123", body.getString("password"));
        assertEquals("01012345678", body.getString("phone_number"));
        assertEquals("Test", body.getString("name"));
        assertFalse(body.has("email"));
        for (String invalid : new String[]{"123", "12345", "abcd", "１２３４"}) {
            assertThrows(RegistrationContract.PasswordLengthException.class,
                    () -> contract.request("", "01012345678", invalid));
        }
    }

    @Test public void unrelatedValidationFailureNeverBlamesPasswordLengthOrLeaksInput() {
        ApiException error = new ApiException("POST", "/api/v1/auth/register", new HttpResult(422,
                "{\"detail\":[{\"loc\":[\"body\",\"name\"],\"input\":\"private-input\"}]}", null));
        String message = AuthErrorMessages.registration(error);
        assertFalse(message.contains("비밀번호"));
        assertFalse(message.contains("8글자"));
        assertFalse(message.contains("private-input"));
        assertTrue(message.contains("확인"));
    }

    @Test public void connectionFailureDoesNotBlameInput() {
        String message = AuthErrorMessages.registration(new java.io.IOException("private-details"));
        assertTrue(message.contains("연결 상태"));
        assertFalse(message.contains("private-details"));
        assertFalse(message.contains("비밀번호"));
    }
}
