package com.careslot.notify.application;

/** Recipients are stored masked: enough for support to recognise them, not enough to contact the patient. */
public final class Masking {

    private Masking() {
    }

    public static String phone(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    public static String email(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
