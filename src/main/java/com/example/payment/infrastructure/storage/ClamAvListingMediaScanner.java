package com.example.payment.infrastructure.storage;

import com.example.payment.application.service.ListingMediaScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "app.media.malware-scan.provider", havingValue = "CLAMAV")
public class ClamAvListingMediaScanner implements ListingMediaScanner {

    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_RESPONSE_BYTES = 4096;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ClamAvListingMediaScanner(
            @Value("${app.media.malware-scan.clamav.host:localhost}") String host,
            @Value("${app.media.malware-scan.clamav.port:3310}") int port,
            @Value("${app.media.malware-scan.clamav.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.media.malware-scan.clamav.read-timeout-ms:30000}") int readTimeoutMs) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalStateException("A valid ClamAV host and port are required.");
        }
        this.host = host.trim();
        this.port = port;
        this.connectTimeoutMs = positive(connectTimeoutMs, "ClamAV connect timeout");
        this.readTimeoutMs = positive(readTimeoutMs, "ClamAV read timeout");
    }

    @Override
    public String providerName() {
        return "CLAMAV";
    }

    @Override
    public void assertSafe(Path path, String filename, String contentType) {
        try (Socket socket = connect();
             DataOutputStream output = new DataOutputStream(socket.getOutputStream());
             InputStream file = Files.newInputStream(path)) {
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = file.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.writeInt(read);
                output.write(buffer, 0, read);
            }
            output.writeInt(0);
            output.flush();

            String response = readResponse(socket.getInputStream());
            if (response.endsWith("OK")) {
                return;
            }
            if (response.contains("FOUND")) {
                throw new IllegalArgumentException("Unsafe media file was rejected: " + filename);
            }
            throw new IllegalStateException("ClamAV could not verify the uploaded media.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("Malware scanner is unavailable; upload was rejected.", exception);
        }
    }

    @Override
    public ScannerReadiness readiness() {
        try (Socket socket = connect()) {
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = readResponse(socket.getInputStream());
            return new ScannerReadiness(response.contains("PONG"),
                    response.contains("PONG") ? "ClamAV malware scanner is reachable."
                            : "ClamAV returned an unexpected readiness response.");
        } catch (IOException | RuntimeException exception) {
            return new ScannerReadiness(false,
                    "ClamAV malware scanner is unavailable: " + exception.getClass().getSimpleName());
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        return socket;
    }

    private String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        for (int next; response.size() < MAX_RESPONSE_BYTES && (next = input.read()) >= 0;) {
            if (next == 0) {
                break;
            }
            response.write(next);
        }
        return response.toString(StandardCharsets.US_ASCII).trim();
    }

    private int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be positive.");
        }
        return value;
    }
}
