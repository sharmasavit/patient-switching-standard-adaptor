package uk.nhs.adaptors.pss.translator.util;

import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.intellij.lang.annotations.RegExp;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TextUtil {

    @RegExp
    private static final String PMIP_COMMENT_REGEX = "^CommentType:[A-Za-z0-9() ]+[\\s\\n]+CommentDate:[0-9]+\\s*\\n+([\\S\\s]*)$";

    public static String getLastLine(String text) {
        if (text == null) {
            return StringUtils.EMPTY;
        }

        if (!text.contains(StringUtils.LF)) {
            return text;
        }

        String trimmed = text.trim();
        return trimmed.substring(trimmed.lastIndexOf(StringUtils.LF)).trim();
    }

    public static String addLine(String text, String line) {
        return text == null ? line : text.concat(StringUtils.LF).concat(line);
    }

    public static String extractPmipComment(String text) {
        var pattern = Pattern.compile(PMIP_COMMENT_REGEX);
        var matcher = pattern.matcher(text);

        if (!matcher.find()) {
            return text;
        }

        return matcher.group(1).stripTrailing();
    }
}
