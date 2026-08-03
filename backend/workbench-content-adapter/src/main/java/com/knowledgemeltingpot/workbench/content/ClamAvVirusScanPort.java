package com.knowledgemeltingpot.workbench.content;

import com.knowledgemeltingpot.workbench.application.port.VirusScanException;
import com.knowledgemeltingpot.workbench.application.port.VirusScanPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Fail-closed ClamAV INSTREAM client using only JDK {@link Socket}.
 * No third-party ClamAV library is used.
 */
public class ClamAvVirusScanPort implements VirusScanPort {

    private static final int CHUNK_SIZE = 8192;
    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);

    private final ClamAvProperties properties;

    public ClamAvVirusScanPort(ClamAvProperties properties) {
        this.properties = properties;
    }

    @Override
    public ScanReport scan(Path file) throws VirusScanException {
        long size = fileSize(file);
        if (size > properties.maxStreamLength()) {
            throw new VirusScanException("FILE_EXCEEDS_SCAN_LIMIT");
        }

        VersionInfo version = queryVersion();
        try (Socket socket = openSocket()) {
            sendInstream(socket, file, size);
            String reply = readReply(socket);
            if ("OK".equals(reply) || reply.endsWith(": OK")) {
                return new VirusScanPort.ScanReport(true, version.engineVersion(), version.signatureVersion());
            }
            if (reply.endsWith(" FOUND") || reply.startsWith("FOUND")) {
                return new VirusScanPort.ScanReport(false, version.engineVersion(), version.signatureVersion());
            }
            throw new VirusScanException("SCAN_REPLY_UNRECOGNIZED");
        } catch (SocketTimeoutException exception) {
            throw new VirusScanException("SCAN_TIMEOUT", exception);
        } catch (IOException exception) {
            throw new VirusScanException("SCAN_CONNECTION_FAILED", exception);
        }
    }

    private VersionInfo queryVersion() throws VirusScanException {
        try (Socket socket = openSocket()) {
            return readVersion(socket);
        } catch (SocketTimeoutException exception) {
            throw new VirusScanException("SCAN_TIMEOUT", exception);
        } catch (IOException exception) {
            throw new VirusScanException("SCAN_CONNECTION_FAILED", exception);
        }
    }

    private long fileSize(Path file) throws VirusScanException {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            throw new VirusScanException("FILE_SIZE_UNAVAILABLE", exception);
        }
    }

    private Socket openSocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(properties.host(), properties.port()),
                (int) properties.connectTimeout().toMillis());
        socket.setSoTimeout((int) properties.readTimeout().toMillis());
        return socket;
    }

    private VersionInfo readVersion(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write("VERSION\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[256];
        int length = 0;
        while (length < buffer.length) {
            int read = in.read(buffer, length, buffer.length - length);
            if (read < 0) {
                throw new IOException("version stream closed");
            }
            for (int i = length; i < length + read; i++) {
                if (buffer[i] == '\n') {
                    String line = new String(buffer, 0, i, StandardCharsets.US_ASCII).trim();
                    return parseVersion(line);
                }
            }
            length += read;
        }
        throw new IOException("version reply too long");
    }

    private VersionInfo parseVersion(String line) throws IOException {
        if (line == null || line.isBlank()) {
            throw new IOException("empty version reply");
        }
        String[] parts = line.split("/", 3);
        String engine = parts[0].trim();
        String signature = parts.length > 1 ? parts[1].trim() : "";
        if (engine.isBlank()) {
            throw new IOException("invalid version reply: " + line);
        }
        return new VersionInfo(engine, signature);
    }

    private void sendInstream(Socket socket, Path file, long size) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(INSTREAM);

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            long remaining = size;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("file truncated while streaming");
                }
                writeChunkLength(out, read);
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        writeChunkLength(out, 0);
        out.flush();
    }

    private void writeChunkLength(OutputStream out, int length) throws IOException {
        byte[] lengthBytes = new byte[4];
        lengthBytes[0] = (byte) ((length >> 24) & 0xFF);
        lengthBytes[1] = (byte) ((length >> 16) & 0xFF);
        lengthBytes[2] = (byte) ((length >> 8) & 0xFF);
        lengthBytes[3] = (byte) (length & 0xFF);
        out.write(lengthBytes);
    }

    private String readReply(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[512];
        int length = 0;
        while (length < buffer.length) {
            int read = in.read(buffer, length, buffer.length - length);
            if (read < 0) {
                throw new IOException("reply stream closed");
            }
            for (int i = length; i < length + read; i++) {
                if (buffer[i] == 0) {
                    return new String(buffer, 0, i, StandardCharsets.US_ASCII).trim();
                }
            }
            length += read;
        }
        throw new IOException("reply too long");
    }

    private record VersionInfo(String engineVersion, String signatureVersion) {
    }
}
