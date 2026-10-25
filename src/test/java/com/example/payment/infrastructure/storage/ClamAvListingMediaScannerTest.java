package com.example.payment.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClamAvListingMediaScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsCleanInstreamResponse() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            Future<?> stub = executor.submit(() -> serveScan(server, "stream: OK\0"));
            Path upload = Files.writeString(tempDir.resolve("clean.png"), "clean-content");
            ClamAvListingMediaScanner scanner = new ClamAvListingMediaScanner(
                    "127.0.0.1", server.getLocalPort(), 1000, 3000);

            scanner.assertSafe(upload, "clean.png", "image/png");

            stub.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsMalwareFinding() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            Future<?> stub = executor.submit(() -> serveScan(server, "stream: Eicar-Test-Signature FOUND\0"));
            Path upload = Files.writeString(tempDir.resolve("unsafe.pdf"), "unsafe-content");
            ClamAvListingMediaScanner scanner = new ClamAvListingMediaScanner(
                    "127.0.0.1", server.getLocalPort(), 1000, 3000);

            assertThrows(IllegalArgumentException.class,
                    () -> scanner.assertSafe(upload, "unsafe.pdf", "application/pdf"));

            stub.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reportsPingReadiness() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            Future<?> stub = executor.submit(() -> {
                try (Socket client = server.accept()) {
                    readNullTerminated(client.getInputStream());
                    client.getOutputStream().write("PONG\0".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            ClamAvListingMediaScanner scanner = new ClamAvListingMediaScanner(
                    "127.0.0.1", server.getLocalPort(), 1000, 3000);

            assertTrue(scanner.readiness().ready());

            stub.get();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void serveScan(ServerSocket server, String response) {
        try (Socket client = server.accept()) {
            InputStream input = client.getInputStream();
            readNullTerminated(input);
            DataInputStream data = new DataInputStream(input);
            int size;
            while ((size = data.readInt()) > 0) {
                data.readNBytes(size);
            }
            client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void readNullTerminated(InputStream input) throws Exception {
        int next;
        while ((next = input.read()) >= 0 && next != 0) {
            // Read the command terminator.
        }
        if (next < 0) {
            throw new IllegalStateException("Command terminated before a null byte was received.");
        }
    }
}
