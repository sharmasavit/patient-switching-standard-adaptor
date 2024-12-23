package uk.nhs.adaptors.common.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.FileNotFoundException;
import java.io.InputStream;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.io.IOUtils;

import lombok.SneakyThrows;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FileUtil {

    @SneakyThrows
    public static String readResourceAsString(String path) {
        try (InputStream is = FileUtil.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new FileNotFoundException(path);
            }
            return IOUtils.toString(is, UTF_8);
        }
    }
}
