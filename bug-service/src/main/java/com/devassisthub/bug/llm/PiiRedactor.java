package com.devassisthub.bug.llm;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Masks personal data and secrets before a report leaves the service: private keys, JWTs, bearer tokens,
 * {@code password=...}-style values, well-known API key formats, credentials in URLs, emails, public IPv4 addresses,
 * international phone numbers and the user name in home-directory paths. Each match becomes a placeholder such as
 * {@code [EMAIL]} that the prompt tells the model to ignore.
 */
@Component
public class PiiRedactor {

    /** The masked text and how many values were replaced. */
    public record Result(String text, int count) {
    }

    private record Rule(Pattern pattern, Function<Matcher, String> replacement) {
    }

    private static final List<Rule> RULES = List.of(
            rule("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----", m -> "[SECRET]"),
            rule("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}", m -> "[TOKEN]"),
            rule("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}", m -> m.group(1) + " [TOKEN]"),
            rule("(?i)\\b(password|passwd|pwd|secret|client[_-]?secret|api[_-]?key|access[_-]?token|auth[_-]?token|token)"
                    + "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&]+)", m -> m.group(1) + m.group(2) + "[SECRET]"),
            rule("\\b(?:hf_|sk-|sk_live_|pk_live_|gh[pousr]_|glpat-|xox[abprs]-|AKIA|AIza)[A-Za-z0-9_-]{10,}",
                    m -> "[TOKEN]"),
            rule("(\\b[a-zA-Z][a-zA-Z0-9+.-]*://)[^\\s/:@]+:[^\\s/@]+@", m -> m.group(1) + "[SECRET]@"),
            rule("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", m -> "[EMAIL]"),
            rule("(?<![\\d.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?![\\d.])",
                    m -> isLocal(m.group()) ? m.group() : "[IP]"),
            rule("(?<![\\w+])\\+\\d{1,3}(?:[\\s.-]?\\(?\\d{1,4}\\)?){2,5}(?!\\d)", m -> "[PHONE]"),
            rule("(?i)\\b([A-Z]:\\\\(?:Users|Documents and Settings)\\\\)(?!\\[USER])[^\\\\/\\s:*?\"<>|]+",
                    m -> m.group(1) + "[USER]"),
            rule("(/(?:home|Users)/)(?!\\[USER])[^/\\s]+", m -> m.group(1) + "[USER]"));

    public Result redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        String current = text;
        int count = 0;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(current);
            StringBuilder out = new StringBuilder(current.length());
            boolean changed = false;
            while (m.find()) {
                String replacement = rule.replacement().apply(m);
                if (!replacement.equals(m.group())) {
                    count++;
                    changed = true;
                }
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            if (changed) {
                m.appendTail(out);
                current = out.toString();
            }
        }
        return new Result(current, count);
    }

    private static Rule rule(String regex, Function<Matcher, String> replacement) {
        return new Rule(Pattern.compile(regex), replacement);
    }

    /** Loopback and unspecified addresses are not personal and help the diagnosis. */
    private static boolean isLocal(String ip) {
        return ip.startsWith("127.") || ip.equals("0.0.0.0");
    }
}
