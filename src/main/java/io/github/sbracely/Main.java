package io.github.sbracely;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args) {
        Path source = Path.of("cldr", "cldr-common-48.2", "common");
        Path output = Path.of("properties");
        try {
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                if (option.equals("--help") || option.equals("-h")) {
                    System.out.println("Usage: java -cp target\\classes io.github.sbracely.Main "
                            + "[--cldr-dir <common-directory>] "
                            + "[--output <directory>]");
                    System.out.println("Defaults: --cldr-dir " + source
                            + "; output properties. All locales are scanned. Paths are relative to "
                            + "the current working directory. CLDR data is read from external files.");
                    return 0;
                }
                if (!List.of("--cldr-dir", "--output").contains(option)) {
                    throw new IllegalArgumentException("Unknown option: " + option);
                }
                if (++i == args.length || args[i].startsWith("--") || args[i].isBlank()) {
                    throw new IllegalArgumentException("Missing value for " + option);
                }
                switch (option) {
                    case "--cldr-dir" -> source = Path.of(args[i]);
                    case "--output" -> output = Path.of(args[i]);
                    default -> throw new IllegalStateException(option);
                }
            }
            CldrSlicer slicer = new CldrSlicer(source);
            Path license = source.toAbsolutePath().normalize().resolve("..").resolve("LICENSE").normalize();
            byte[] licenseContents = Files.readAllBytes(license);
            Map<String, Map<String, String>> slices = slicer.sliceAll();
            for (var entry : slices.entrySet()) {
                Path file = output.resolve(fileName(entry.getKey()));
                CldrSlicer.write(file, entry.getValue());
            }
            Files.write(output.resolve("LICENSE"), licenseContents);
            removeObsoleteGeneratedFiles(output, slices);
            System.out.println("Generated " + output.resolve(fileName("root")) + " ("
                    + slices.get("root").size() + " entries) and " + (slices.size() - 1)
                    + " ResourceBundle locale files (root differences and required fallback overrides).");
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("CLDR slice failed: " + e.getMessage());
            return 1;
        }
    }

    private static String fileName(String locale) {
        return CldrSlicer.BUNDLE_NAME + (locale.equals("root") ? "" : "_" + locale) + ".properties";
    }

    private static void removeObsoleteGeneratedFiles(Path output,
                                                    Map<String, Map<String, String>> slices)
            throws IOException {
        Set<String> expectedFiles = slices.keySet().stream()
                .map(Main::fileName).collect(Collectors.toSet());
        try (var files = Files.list(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (expectedFiles.contains(name)
                        || !name.matches("(?:calendar|chinese-calendar)(?:_[A-Za-z0-9_]+)?\\.properties")) {
                    continue;
                }
                try (var reader = Files.newBufferedReader(file)) {
                    if (!CldrSlicer.GENERATED_HEADER.equals(reader.readLine())) {
                        continue;
                    }
                }
                Files.delete(file);
            }
        }
    }
}
