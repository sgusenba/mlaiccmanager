package com.competition.portal;

import com.competition.portal.service.RecordNotFoundException;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Server commands, run on the VM as the portal's user, e.g.
 * <pre>sudo -u mlaic-portal java -jar mlaic-portal.jar --add-organiser stefan</pre>
 * <ul>
 * <li>--add-organiser NAME: creates an organiser account (the first one, or any later one);</li>
 * <li>--set-password NAME: sets a new password, e.g. when it is forgotten;</li>
 * <li>--reset-totp NAME: turns the authenticator app off, when the phone is lost.</li>
 * </ul>
 * Passwords are typed twice without echo; when no terminal is attached
 * (scripts), one line is read from standard input.
 */
public final class PortalCli {
    private PortalCli() {
    }

    /** Runs the command; returns the exit code. */
    public static int run(String[] args, PortalServices services) {
        return run(args, services, System.out, System.err, passwordReader());
    }

    interface PasswordReader {
        String read(String prompt) throws IOException;
    }

    static int run(String[] args, PortalServices services, PrintStream out, PrintStream err, PasswordReader passwords) {
        if (args.length != 2 || !Arrays.asList("--add-organiser", "--set-password", "--reset-totp").contains(args[0])) {
            err.println("Usage: java -jar mlaic-portal.jar --add-organiser NAME | --set-password NAME | --reset-totp NAME");
            return 2;
        }
        String username = args[1];
        try {
            switch (args[0]) {
                case "--add-organiser" -> {
                    String password = newPassword(passwords);
                    if (password == null) {
                        err.println("The passwords do not match.");
                        return 1;
                    }
                    services.organiserService.create(username, password);
                    out.println("Organiser " + username.trim().toLowerCase() + " created.");
                }
                case "--set-password" -> {
                    int id = services.organiserService.idOf(username);
                    String password = newPassword(passwords);
                    if (password == null) {
                        err.println("The passwords do not match.");
                        return 1;
                    }
                    services.organiserService.setPassword(id, password);
                    out.println("Password of " + username + " changed.");
                    // This process cannot reach the running portal's sessions, which are kept in memory
                    out.println("Sessions already open stay valid until the portal restarts (sudo systemctl restart mlaic-portal).");
                }
                case "--reset-totp" -> {
                    services.organiserService.resetTotp(services.organiserService.idOf(username));
                    out.println("Authenticator app of " + username + " turned off; it can be set up again after logging in.");
                }
                default -> throw new IllegalStateException();
            }
            return 0;
        } catch (IllegalArgumentException | RecordNotFoundException e) {
            err.println(e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("Failed: " + e.getMessage());
            return 1;
        }
    }

    /** The new password, asked twice on a terminal; null if the two differ. */
    private static String newPassword(PasswordReader passwords) throws IOException {
        String first = passwords.read("New password (at least 12 characters): ");
        String second = passwords.read("Repeat it: ");
        if (second == null) {
            return first; // no terminal: one line from standard input
        }
        return first != null && first.equals(second) ? first : null;
    }

    private static PasswordReader passwordReader() {
        Console console = System.console();
        if (console != null) {
            return prompt -> {
                char[] chars = console.readPassword("%s", prompt);
                return chars == null ? null : new String(chars);
            };
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        boolean[] asked = {false};
        return prompt -> {
            if (asked[0]) {
                return null;
            }
            asked[0] = true;
            return in.readLine();
        };
    }
}
