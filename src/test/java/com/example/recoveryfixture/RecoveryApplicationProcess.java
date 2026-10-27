package com.example.recoveryfixture;

import com.example.payment.PaymentApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owns only the child JVM created for a recovery test; never attaches to a running demo app. */
public final class RecoveryApplicationProcess implements AutoCloseable {
    private final Process process;
    private final Thread shutdownHook;
    private int port;

    private RecoveryApplicationProcess(Process process) {
        this.process = process;
        shutdownHook = new Thread(process::destroyForcibly, "recovery-child-cleanup-" + process.pid());
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static RecoveryApplicationProcess start(Path directory, Map<String, String> configuration) throws Exception {
        Files.createDirectories(directory);
        String run = UUID.randomUUID().toString();
        Path config = directory.resolve(run + ".properties");
        Path ready = directory.resolve(run + ".ready");
        Path log = directory.resolve(run + ".log");
        Properties properties = new Properties();
        properties.putAll(configuration);
        try (var writer = Files.newBufferedWriter(config)) {
            properties.store(writer, "Isolated recovery test services and dummy provider credentials only");
        }
        String classpath = Files.readString(Path.of(System.getProperty("recovery.test.classpath-file")));
        Path arguments = directory.resolve(run + ".args");
        // Java argument files avoid Windows command-line limits for the test runtime classpath.
        Files.write(arguments, List.of("-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp", quote(classpath),
                RecoveryApplicationProcess.class.getName(), quote(config.toString()), quote(ready.toString())));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "@" + arguments).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // Do not inherit real PG keys, Spring profiles, JAVA_TOOL_OPTIONS, or external service settings.
        var inherited = Map.copyOf(builder.environment());
        builder.environment().clear();
        inherited.forEach((key, value) -> {
            if (List.of("SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "TEMP", "TMP", "PATH", "HOME", "USERPROFILE")
                    .contains(key.toUpperCase(java.util.Locale.ROOT))) builder.environment().put(key, value);
        });
        var child = new RecoveryApplicationProcess(builder.start());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                if (!child.process.isAlive()) throw new IllegalStateException("Recovery app exited; see " + log);
                if (Files.exists(ready)) {
                    child.port = Integer.parseInt(Files.readString(ready));
                    return child;
                }
                Thread.sleep(200);
            }
            throw new IllegalStateException("Recovery app did not start within 90 seconds; see " + log);
        } catch (Exception | Error failure) {
            child.close();
            throw failure;
        }
    }

    public int port() { return port; }
    public long pid() { return process.pid(); }
    public boolean isAlive() { return process.isAlive(); }

    @Override
    public void close() throws InterruptedException {
        try {
            if (process.isAlive()) process.destroyForcibly();
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IllegalStateException("Recovery child did not terminate");
        } finally {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void main(String[] args) throws Exception {
        Properties configuration = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) { configuration.load(reader); }
        String[] options = configuration.stringPropertyNames().stream()
                .map(key -> "--" + key + "=" + configuration.getProperty(key)).toArray(String[]::new);
        var context = (ServletWebServerApplicationContext) SpringApplication.run(PaymentApplication.class, options);
        Path ready = Path.of(args[1]);
        Path staging = ready.resolveSibling(ready.getFileName() + ".tmp");
        Files.writeString(staging, Integer.toString(context.getWebServer().getPort()));
        Files.move(staging, ready);
    }
}
