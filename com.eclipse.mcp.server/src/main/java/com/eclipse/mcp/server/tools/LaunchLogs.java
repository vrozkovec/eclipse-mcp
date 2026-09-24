package com.eclipse.mcp.server.tools;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Per-launch console log files for applications started by {@code debug_relaunch}.
 *
 * <p>The workspace's launch configurations all write their console to the same Output File, so two
 * applications running at once would overwrite each other's log. Each launch therefore gets a fresh
 * file in {@link #LOG_DIRECTORY}, and only the newest {@value #RETAINED_LOGS} of them are kept.</p>
 */
final class LaunchLogs {

    /** Directory of the per-launch log files, next to the MCP server's own log. */
    static final Path LOG_DIRECTORY = Path.of("/data/tmp/eclipse");

    /** Number of per-launch log files that {@link #prune(Collection)} keeps. */
    private static final int RETAINED_LOGS = 20;

    private static final String PREFIX = "eclipse-java-app-";
    private static final String SUFFIX = ".log";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private LaunchLogs() {
    }

    /**
     * Creates an empty log file for a new launch of a configuration, e.g.
     * {@code /data/tmp/eclipse/eclipse-java-app-20260924-143012-MyServer.log}. A numeric suffix
     * keeps the name unique when the configuration is launched twice within a second.
     *
     * @param configurationName name of the launched configuration
     * @return the created file
     * @throws IOException if the directory or the file cannot be created
     */
    static Path newLogFile(String configurationName) throws IOException {
        Files.createDirectories(LOG_DIRECTORY);
        String baseName = PREFIX + LocalDateTime.now().format(TIMESTAMP) + "-" + fileNameSafe(configurationName);
        int attempt = 1;
        while (true) {
            Path file = LOG_DIRECTORY.resolve(attempt == 1 ? baseName + SUFFIX : baseName + "-" + attempt + SUFFIX);
            try {
                return Files.createFile(file);
            } catch (FileAlreadyExistsException e) {
                attempt++;
            }
        }
    }

    /**
     * Deletes all but the newest {@value #RETAINED_LOGS} per-launch log files, newest by
     * modification time. Best effort: files that cannot be read or deleted are skipped.
     *
     * @param inUse files that must never be deleted, such as the logs of running applications
     */
    static void prune(Collection<Path> inUse) {
        Set<Path> keep = inUse.stream().map(LaunchLogs::normalized).collect(Collectors.toSet());
        List<Path> logs;
        try (Stream<Path> files = Files.list(LOG_DIRECTORY)) {
            logs = files.filter(LaunchLogs::isLaunchLog)
                    .sorted(Comparator.comparing(LaunchLogs::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            return;
        }
        for (Path log : logs.subList(Math.min(RETAINED_LOGS, logs.size()), logs.size())) {
            if (!keep.contains(normalized(log))) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException e) {
                    // best effort — the next launch tries again
                }
            }
        }
    }

    private static boolean isLaunchLog(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(PREFIX) && name.endsWith(SUFFIX) && Files.isRegularFile(file);
    }

    private static FileTime lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static Path normalized(Path file) {
        return file.toAbsolutePath().normalize();
    }

    /**
     * Turns a launch configuration name into a file name part, e.g. {@code "shop - CZECHIA"} into
     * {@code "shop_-_CZECHIA"}.
     */
    private static String fileNameSafe(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isEmpty() ? "launch" : safe;
    }
}
