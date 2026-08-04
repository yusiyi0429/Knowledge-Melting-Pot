package com.knowledgemeltingpot.workbench.content;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "workbench.content.ocr")
public record OcrProperties(
        boolean enabled,
        String executable,
        String languages,
        int dpi,
        int maxPages,
        long maxTotalPixels,
        Duration pageTimeout,
        int maxOutputChars) {

    private static final Pattern SAFE_LANGUAGES = Pattern.compile("[A-Za-z0-9_+]{1,100}");

    @ConstructorBinding
    public OcrProperties {
        executable = executable == null || executable.isBlank() ? "tesseract" : executable.strip();
        languages = languages == null || languages.isBlank() ? "chi_sim+eng" : languages.strip();
        dpi = dpi == 0 ? 200 : dpi;
        maxPages = maxPages == 0 ? 100 : maxPages;
        maxTotalPixels = maxTotalPixels == 0 ? 200_000_000L : maxTotalPixels;
        pageTimeout = pageTimeout == null ? Duration.ofSeconds(45) : pageTimeout;
        maxOutputChars = maxOutputChars == 0 ? 5_000_000 : maxOutputChars;
        if (!SAFE_LANGUAGES.matcher(languages).matches()) {
            throw new IllegalArgumentException("workbench.content.ocr.languages is invalid");
        }
        if (dpi < 100 || dpi > 300 || maxPages < 1 || maxPages > 500
                || maxTotalPixels < 1_000_000 || maxTotalPixels > 1_000_000_000L
                || pageTimeout.isZero() || pageTimeout.isNegative() || pageTimeout.compareTo(Duration.ofMinutes(5)) > 0
                || maxOutputChars < 1_000 || maxOutputChars > 20_000_000) {
            throw new IllegalArgumentException("workbench.content.ocr resource budget is invalid");
        }
    }
}
