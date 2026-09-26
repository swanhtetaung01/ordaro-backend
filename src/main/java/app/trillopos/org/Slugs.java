package app.trillopos.org;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Public-link slugs (spec §3): the latinised business name plus four random characters.
 * A name with no Latin letters (most Burmese names) falls back to "shop".
 */
public final class Slugs {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SUFFIX_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private Slugs() {
    }

    public static String generate(String businessName) {
        String latin = Normalizer.normalize(businessName, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (latin.length() > 40) {
            latin = latin.substring(0, 40).replaceAll("-+$", "");
        }
        StringBuilder slug = new StringBuilder(latin.isEmpty() ? "shop" : latin).append('-');
        for (int i = 0; i < 4; i++) {
            slug.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return slug.toString();
    }
}
