package com.click.data.remote;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistrationContractTest {
    private JSONObject document(String field, int minimum, int maximum) throws JSONException {
        JSONObject properties = new JSONObject().put(field, new JSONObject().put("type", "string"))
                .put("name", new JSONObject().put("type", "string"))
                .put("password", new JSONObject().put("minLength", minimum).put("maxLength", maximum));
        return new JSONObject().put("paths", new JSONObject().put("/api/v1/auth/register", new JSONObject()
                .put("post", new JSONObject().put("requestBody", new JSONObject().put("content", new JSONObject()
                .put("application/json", new JSONObject().put("schema", new JSONObject().put("properties", properties))))))));
    }

    @Test public void acceptsTheDeployedRegistrationSchemaSnapshot() throws Exception {
        try (java.io.InputStream stream = getClass().getResourceAsStream("/auth/phone-registration-openapi.json")) {
            assertNotNull(stream);
            JSONObject document = new JSONObject(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            JSONObject body = RegistrationContract.fromOpenApi(document).request("클릭", "01012345678", "0123");
            assertEquals("01012345678", body.getString("phone_number"));
            assertFalse(body.has("email"));
        }
    }

    @Test public void registrationUsesPhoneFieldAndPreservesLeadingZeroInPin() throws Exception {
        RegistrationContract contract = RegistrationContract.fromOpenApi(document("phone_number", 4, 4));
        JSONObject body = contract.request(" 클릭 ", " 010-1234-5678 ", "0123");
        assertEquals("01012345678", body.getString("phone_number"));
        assertEquals("0123", body.getString("password"));
        assertEquals("클릭", body.getString("name"));
        assertEquals(3, body.length());
        assertFalse(body.has("email"));
    }

    @Test public void loginUsesSameCredentialValidationAndPayload() throws Exception {
        JSONObject body = RegistrationContract.credentials("010 1234 5678", "0000");
        assertEquals("01012345678", body.getString("phone_number"));
        assertEquals("0000", body.getString("password"));
        assertEquals(2, body.length());
        for (String phone : new String[]{"review@example.com", "0212345678", "010123", "010123456789", "０１０12345678"})
            assertThrows(RegistrationContract.RegistrationInputException.class,
                    () -> RegistrationContract.credentials(phone, "0123"));
        for (String pin : new String[]{"123", "12345", "abcd", "１２３４", " 0123", "12a3"})
            assertThrows(RegistrationContract.PasswordLengthException.class,
                    () -> RegistrationContract.credentials("01012345678", pin));
    }

    @Test public void unsupportedServerSchemaFailsInsteadOfSendingAnEmailPayload() throws Exception {
        assertThrows(JSONException.class, () -> RegistrationContract.fromOpenApi(document("email", 4, 4)));
        assertThrows(JSONException.class, () -> RegistrationContract.fromOpenApi(document("phone_number", 8, 128)));
    }

    @Test public void nameIsRequiredAndUnicodeLimitIsPreserved() throws Exception {
        RegistrationContract contract = RegistrationContract.fromOpenApi(document("phone_number", 4, 4));
        for (String name : new String[]{null, "", "   ", "\u3000"})
            assertThrows(RegistrationContract.RegistrationInputException.class,
                    () -> contract.request(name, "01012345678", "0123"));
        assertEquals("😀".repeat(50), contract.request("😀".repeat(50), "01012345678", "0123").getString("name"));
        assertThrows(RegistrationContract.RegistrationInputException.class,
                () -> contract.request("김".repeat(51), "01012345678", "0123"));
    }
}
