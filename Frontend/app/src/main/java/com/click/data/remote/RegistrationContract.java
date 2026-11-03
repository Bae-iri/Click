package com.click.data.remote;

import org.json.JSONException;
import org.json.JSONObject;

/** Registration fields and password limits advertised by the server serving this app. */
public final class RegistrationContract {
    public final int minimumPasswordLength;
    public final int maximumPasswordLength;
    private final boolean acceptsName;

    private RegistrationContract(int minimum, int maximum, boolean acceptsName) {
        this.minimumPasswordLength = minimum;
        this.maximumPasswordLength = maximum;
        this.acceptsName = acceptsName;
    }

    public static RegistrationContract fromOpenApi(JSONObject document) throws JSONException {
        JSONObject schema = document.getJSONObject("paths").getJSONObject("/api/v1/auth/register")
                .getJSONObject("post").getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema");
        if (schema.has("$ref")) {
            String reference = schema.getString("$ref");
            if (!reference.startsWith("#/components/schemas/")) throw new JSONException("Unsupported registration schema");
            schema = document.getJSONObject("components").getJSONObject("schemas")
                    .getJSONObject(reference.substring("#/components/schemas/".length()));
        }
        JSONObject properties = schema.getJSONObject("properties");
        JSONObject password = properties.getJSONObject("password");
        int minimum = password.getInt("minLength");
        int maximum = password.getInt("maxLength");
        if (minimum < 1 || maximum < minimum || maximum > 4096) throw new JSONException("Invalid password limits");
        return new RegistrationContract(minimum, maximum, properties.has("name"));
    }

    public JSONObject request(String name, String email, String password, String ageGroup,
                              String occupation) throws JSONException {
        int length = password.codePointCount(0, password.length());
        if (length < minimumPasswordLength) {
            throw new PasswordLengthException("비밀번호를 " + minimumPasswordLength + "글자 이상 입력해 주세요.");
        }
        if (length > maximumPasswordLength) {
            throw new PasswordLengthException("비밀번호는 " + maximumPasswordLength + "글자까지 입력할 수 있어요.");
        }
        JSONObject body = new JSONObject().put("email", email).put("password", password).put("age_group", ageGroup);
        // Older deployments forbid the newly added name field. The app also saves the name locally.
        if (acceptsName && name != null && !name.trim().isEmpty()) body.put("name", name.trim());
        if (occupation != null) body.put("occupation_category", occupation);
        return body;
    }

    public static final class PasswordLengthException extends IllegalArgumentException {
        private PasswordLengthException(String message) { super(message); }
    }
}
