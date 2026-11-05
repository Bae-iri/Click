package com.click.data.remote;

import com.click.designpreview.SignupInputRules;
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
        if (!properties.has("phone_number") || minimum != 4 || maximum != 4)
            throw new JSONException("Unsupported authentication contract: expected phone number and four-digit PIN");
        return new RegistrationContract(minimum, maximum, properties.has("name"));
    }

    public JSONObject request(String name, String phone, String password) throws JSONException {
        JSONObject body = credentials(phone, password);
        if (!SignupInputRules.validName(name)) {
            throw new RegistrationInputException("이름을 1~50글자로 입력해 주세요.");
        }
        // Older deployments forbid the newly added name field. The app also saves the name locally.
        if (acceptsName) body.put("name", name.trim());
        return body;
    }

    public static JSONObject credentials(String phone, String pin) throws JSONException {
        if (!SignupInputRules.validPhone(phone))
            throw new RegistrationInputException("전화번호를 한 번 더 확인해 주세요.");
        if (!SignupInputRules.validPin(pin))
            throw new PasswordLengthException("비밀번호는 숫자 4자리로 입력해 주세요.");
        return new JSONObject().put("phone_number", SignupInputRules.normalizePhone(phone))
                .put("password", pin);
    }

    public static final class PasswordLengthException extends IllegalArgumentException {
        private PasswordLengthException(String message) { super(message); }
    }

    public static final class RegistrationInputException extends IllegalArgumentException {
        private RegistrationInputException(String message) { super(message); }
    }
}
