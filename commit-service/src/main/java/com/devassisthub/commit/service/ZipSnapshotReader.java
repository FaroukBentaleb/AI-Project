package com.devassisthub.commit.service;

import com.devassisthub.commit.config.UploadProperties;
import com.devassisthub.commit.exception.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * Reads an uploaded ZIP into a {path: text} snapshot. Keeps text files only (build output, VCS folders, binaries and
 * oversized files are skipped), normalizes line endings, and drops a single shared top-level folder so that
 * "project/" and "project-v2/" zips of the same tree produce the same paths.
 */
@Component
public class ZipSnapshotReader {

    private static final Set<String> IGNORED_DIRS = Set.of(".git", ".svn", ".idea", ".vscode", "node_modules",
            "target", "build", "dist", "out", "__pycache__", ".gradle", "__MACOSX", ".angular", "venv", ".venv");
    private static final Set<String> BINARY_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "ico", "webp",
            "pdf", "zip", "gz", "tar", "jar", "war", "class", "exe", "dll", "so", "woff", "woff2", "ttf", "eot",
            "mp3", "mp4", "mov", "avi", "lock");

    private final UploadProperties limits;

    public ZipSnapshotReader(UploadProperties limits) {
        this.limits = limits;
    }

    public Map<String, String> read(InputStream zip) {
        Map<String, String> files = new TreeMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(zip, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                // A ZIP with far more entries than we would ever keep is rejected rather than inflated.
                if (++entries > limits.maxFiles() * 4) {
                    throw ApiException.invalidUpload("The ZIP contains too many entries");
                }
                String path = normalize(entry.getName());
                if (entry.isDirectory() || path == null || isIgnored(path)) {
                    continue;
                }
                byte[] bytes = readBounded(in, limits.maxFileBytes());
                if (bytes == null) {
                    continue; // larger than the per-file limit: skipped, like a binary
                }
                total += bytes.length;
                if (total > limits.maxTotalBytes()) {
                    throw ApiException.invalidUpload("The ZIP is larger than the allowed size once extracted");
                }
                String text = decodeText(bytes);
                if (text == null) {
                    continue;
                }
                files.put(path, text.replace("\r\n", "\n").replace('\r', '\n'));
                if (files.size() > limits.maxFiles()) {
                    throw ApiException.invalidUpload("The ZIP contains more than " + limits.maxFiles() + " text files");
                }
            }
        } catch (ZipException e) {
            throw ApiException.invalidUpload("The file is not a valid ZIP archive");
        } catch (IOException e) {
            throw ApiException.invalidUpload("The ZIP could not be read");
        }
        if (files.isEmpty()) {
            throw ApiException.invalidUpload("The ZIP contains no text files");
        }
        return stripCommonRoot(files);
    }

    /** Backslashes become slashes; absolute paths and ".." segments are rejected by returning null. */
    static String normalize(String name) {
        String path = name.replace('\\', '/');
        if (path.startsWith("/")) {
            return null;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return path;
    }

    private static boolean isIgnored(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (IGNORED_DIRS.contains(segments[i])) {
                return true;
            }
        }
        String file = segments[segments.length - 1];
        if (file.equals(".DS_Store") || file.equals("Thumbs.db")) {
            return true;
        }
        int dot = file.lastIndexOf('.');
        return dot >= 0 && BINARY_EXTENSIONS.contains(file.substring(dot + 1).toLowerCase());
    }

    /** Reads at most {@code limit} bytes of the current entry; null when the entry is larger. */
    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            read += n;
            if (read > limit) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Strict UTF-8; null for binary content (NUL byte or invalid UTF-8). */
    private static String decodeText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static Map<String, String> stripCommonRoot(Map<String, String> files) {
        String root = null;
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            if (slash < 0) {
                return files;
            }
            String first = path.substring(0, slash);
            if (root == null) {
                root = first;
            } else if (!root.equals(first)) {
                return files;
            }
        }
        Map<String, String> stripped = new TreeMap<>();
        int cut = root.length() + 1;
        files.forEach((path, text) -> stripped.put(path.substring(cut), text));
        return stripped;
    }
}
