package com.competition.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Machine-specific settings, stored in appsettings.json. Everything is off or empty by default. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {
    @JsonProperty("externalBackup")
    private ExternalBackup externalBackup = new ExternalBackup();

    @JsonProperty("backupReceiver")
    private BackupReceiver backupReceiver = new BackupReceiver();

    public BackupReceiver getBackupReceiver() {
        return backupReceiver;
    }

    public void setBackupReceiver(BackupReceiver backupReceiver) {
        this.backupReceiver = backupReceiver == null ? new BackupReceiver() : backupReceiver;
    }

    public ExternalBackup getExternalBackup() {
        return externalBackup;
    }

    public void setExternalBackup(ExternalBackup externalBackup) {
        this.externalBackup = externalBackup == null ? new ExternalBackup() : externalBackup;
    }

    /** Settings of the backup receiver (--backup-server): the token every request must carry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BackupReceiver {
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token == null ? "" : token.trim();
        }
    }

    /** Where and how often the running app pushes its backup to a backup receiver. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExternalBackup {
        public static final int MIN_SECONDS = 10;
        public static final int MAX_SECONDS = 3600;

        private boolean enabled = false;
        private String url = "";
        private String token = "";
        private int debounceSeconds = 60;
        private int maxDelaySeconds = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null ? "" : url.trim();
        }

        /** Sent to the receiver as a bearer token; must match the receiver's own token. */
        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token == null ? "" : token.trim();
        }

        public int getDebounceSeconds() {
            return debounceSeconds;
        }

        public void setDebounceSeconds(int debounceSeconds) {
            this.debounceSeconds = debounceSeconds;
        }

        public int getMaxDelaySeconds() {
            return maxDelaySeconds;
        }

        public void setMaxDelaySeconds(int maxDelaySeconds) {
            this.maxDelaySeconds = maxDelaySeconds;
        }

        public ExternalBackup copy() {
            ExternalBackup copy = new ExternalBackup();
            copy.enabled = enabled;
            copy.url = url;
            copy.token = token;
            copy.debounceSeconds = debounceSeconds;
            copy.maxDelaySeconds = maxDelaySeconds;
            return copy;
        }

        /** The receiver's base URL, without a trailing slash; throws IllegalArgumentException if it is not an http(s) URL. */
        public static String validateUrl(String url) {
            String trimmed = url == null ? "" : url.trim();
            java.net.URI uri;
            try {
                uri = new java.net.URI(trimmed);
            } catch (java.net.URISyntaxException e) {
                throw new IllegalArgumentException("The URL is not valid");
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
                throw new IllegalArgumentException("The URL must start with http:// or https:// and name a host");
            }
            return trimmed.replaceAll("/+$", "");
        }

        /** Rejects values the scheduler cannot work with; the URL is only required, and normalized, when given or enabled. */
        public void validate() {
            if (debounceSeconds < MIN_SECONDS || debounceSeconds > MAX_SECONDS) {
                throw new IllegalArgumentException(
                    "The quiet time must be between " + MIN_SECONDS + " and " + MAX_SECONDS + " seconds");
            }
            if (maxDelaySeconds < debounceSeconds || maxDelaySeconds > MAX_SECONDS) {
                throw new IllegalArgumentException(
                    "The maximum delay must be between the quiet time and " + MAX_SECONDS + " seconds");
            }
            if (enabled || !url.isEmpty()) {
                url = validateUrl(url);
            }
        }
    }
}
