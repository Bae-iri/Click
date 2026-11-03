package com.click.data.remote;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistrationContractTest {
    private JSONObject document(int minimum, boolean withName) throws JSONException {
        JSONObject properties = new JSONObject()
                .put("email", new JSONObject().put("type", "string"))
                .put("password", new JSONObject().put("type", "string")
                        .put("minLength", minimum).put("maxLength", 128))
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

    @Test public void thirteenCharactersReachDeployedServerWithoutUnsupportedName() throws Exception {
        RegistrationContract contract = RegistrationContract.fromOpenApi(document(12, false));
        String password = "sample-pass13";
        assertEquals(13, password.length());
        JSONObject body = contract.request("테스트", "review@example.com", password, "60_plus", null);
        assertEquals(password, body.getString("password"));
        assertEquals("review@example.com", body.getString("email"));
        assertEquals("60_plus", body.getString("age_group"));
        assertFalse(body.has("name"));
        assertFalse(body.has("occupation_category"));
        assertEquals(3, body.length());
        assertEquals(12, contract.minimumPasswordLength);
    }

    @Test public void updatedServerStillReceivesNameAndOccupation() throws Exception {
        RegistrationContract contract = RegistrationContract.fromOpenApi(document(8, true));
        JSONObject body = contract.request("테스트", "review@example.com", "eight888", "unknown", "서비스업");
        assertEquals("테스트", body.getString("name"));
        assertEquals("서비스업", body.getString("occupation_category"));
        assertEquals(8, contract.minimumPasswordLength);
    }

    @Test public void optionalNameCanBeSkippedWithUpdatedServer() throws Exception {
        RegistrationContract contract = RegistrationContract.fromOpenApi(document(8, true));
        for (String name : new String[]{null, "", "   "}) {
            JSONObject body = contract.request(name, "review@example.com", "eight888", "unknown", null);
            assertFalse(body.has("name"));
            assertFalse(body.has("occupation_category"));
        }
    }

    @Test public void bothServerPasswordBoundariesAreRespected() throws Exception {
        for (int minimum : new int[] {8, 12}) {
            RegistrationContract contract = RegistrationContract.fromOpenApi(document(minimum, false));
            contract.request("", "review@example.com", "a".repeat(minimum), "unknown", null);
            contract.request("", "review@example.com", "a".repeat(128), "unknown", null);
            RegistrationContract.PasswordLengthException shortPassword = assertThrows(
                    RegistrationContract.PasswordLengthException.class,
                    () -> contract.request("", "review@example.com", "a".repeat(minimum - 1), "unknown", null));
            assertTrue(AuthErrorMessages.registration(shortPassword).contains(minimum + "글자 이상"));
            RegistrationContract.PasswordLengthException longPassword = assertThrows(
                    RegistrationContract.PasswordLengthException.class,
                    () -> contract.request("", "review@example.com", "a".repeat(129), "unknown", null));
            assertTrue(AuthErrorMessages.registration(longPassword).contains("128글자까지"));
        }
    }

    @Test public void charactersMatchServerUnicodeCountingAndAreNotTrimmed() throws Exception {
        RegistrationContract contract = RegistrationContract.fromOpenApi(document(12, false));
        assertThrows(RegistrationContract.PasswordLengthException.class,
                () -> contract.request("", "review@example.com", "😀".repeat(7), "unknown", null));
        String password = " 한글Abc123😀xyz ";
        assertEquals(password, contract.request("", "review@example.com", password, "unknown", null)
                .getString("password"));
    }

    @Test public void missingOrInvalidSchemaDoesNotGuessRequestFields() throws Exception {
        assertThrows(JSONException.class, () -> RegistrationContract.fromOpenApi(new JSONObject()));
        assertThrows(JSONException.class, () -> RegistrationContract.fromOpenApi(document(129, false)));
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
