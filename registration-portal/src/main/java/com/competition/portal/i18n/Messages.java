package com.competition.portal.i18n;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The texts the portal's API sends to the user (mostly error messages), in the
 * language of the request: portal-messages.properties holds English, the
 * reference language, portal-messages_de.properties German. A key a language
 * lacks falls back to English. {0}, {1}, … are replaced by the arguments as
 * they are. Works like the Manager's com.competition.i18n.Messages.
 *
 * The language is set per request by {@link LocaleFilter}; code running
 * outside a request (tests, background jobs) gets English.
 */
public final class Messages {
    public static final Locale DEFAULT = Locale.ENGLISH;
    /** The languages there are messages for, the default first. */
    public static final List<String> LANGUAGES = List.of("en", "de");

    private static final String BUNDLE = "portal-messages";
    private static final ResourceBundle.Control CONTROL =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
    private static final ThreadLocal<Locale> CURRENT = new ThreadLocal<>();

    private Messages() {
    }

    public static Locale current() {
        Locale locale = CURRENT.get();
        return locale != null ? locale : DEFAULT;
    }

    /** Sets the language for the current thread; null resets it to English. */
    public static void setCurrent(Locale locale) {
        if (locale == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(locale);
        }
    }

    /** The supported language of a language tag such as "de-AT", or null. */
    public static Locale supported(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String language = Locale.forLanguageTag(tag.trim().replace('_', '-')).getLanguage();
        return LANGUAGES.contains(language) ? Locale.forLanguageTag(language) : null;
    }

    /** The message for key in the current language, its {n} placeholders filled from args. */
    public static String get(String key, Object... args) {
        return format(current(), key, args);
    }

    public static String format(Locale locale, String key, Object... args) {
        String message = lookup(locale, key);
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return message;
    }

    private static String lookup(Locale locale, String key) {
        try {
            return ResourceBundle.getBundle(BUNDLE, locale, CONTROL).getString(key);
        } catch (MissingResourceException e) {
            try {
                return ResourceBundle.getBundle(BUNDLE, Locale.ROOT, CONTROL).getString(key);
            } catch (MissingResourceException missing) {
                return key;
            }
        }
    }
}
