package app.ordaro.org;

import java.util.regex.Pattern;

import app.ordaro.shared.web.ApiException;

/** Phone numbers are stored in E.164 (`+959…`). */
public final class Phones {

    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{6,14}");

    private Phones() {
    }

    public static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String compact = phone.replaceAll("[\\s()\\-.]", "");
        if (!E164.matcher(compact).matches()) {
            throw ApiException.badRequest("invalid_phone", "phone must be in international format, e.g. +959…");
        }
        return compact;
    }
}
