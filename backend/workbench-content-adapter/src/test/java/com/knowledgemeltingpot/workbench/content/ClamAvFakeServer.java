package com.knowledgemeltingpot.workbench.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class ClamAvFakeServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Mode mode;

    enum Mode {
        CLEAN,
        INFECTED,
        SILENT
    }

    ClamAvFakeServer(Mode mode) throws IOException {
        this.mode = mode;
        this.serverSocket = new ServerSocket(0);
        this.executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        executor.submit(this::serve);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    private void serve() {
        if (mode == Mode.SILENT) {
            try (Socket client = serverSocket.accept()) {
                while (!closed.get() && !client.isClosed()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (IOException ignored) {
                // Expected when the timeout test closes the server.
            }
            return;
        }

        try (Socket versionClient = serverSocket.accept()) {
            versionClient.setSoTimeout(500);
            InputStream in = versionClient.getInputStream();
            OutputStream out = versionClient.getOutputStream();
            drainCommand(in);
            out.write("ClamAV 1.4.5/12345/Fri Aug 01 00:00:00 2025\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException exception) {
            return;
        }

        try (Socket scanClient = serverSocket.accept()) {
            scanClient.setSoTimeout(500);
            InputStream in = scanClient.getInputStream();
            OutputStream out = scanClient.getOutputStream();
            drainInstreamCommand(in);
            drainInstreamChunks(in);

            String reply = switch (mode) {
                case CLEAN -> "stream: OK\0";
                case INFECTED -> "stream: Eicar-Test-Signature FOUND\0";
                default -> throw new IllegalStateException();
            };
            out.write(reply.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException exception) {
            // Fake server errors surface as fail-closed client failures.
        }
    }

    private void drainCommand(InputStream in) throws IOException {
        byte[] buffer = new byte[64];
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                throw new IOException("client closed before command complete");
            }
            for (int i = 0; i < read; i++) {
                if (buffer[i] == '\n') {
                    return;
                }
            }
        }
    }

    private void drainInstreamCommand(InputStream in) throws IOException {
        byte[] header = new byte[10];
        readFully(in, header);
        String headerString = new String(header, StandardCharsets.US_ASCII);
        if (!headerString.equals("zINSTREAM\0")) {
            throw new IOException("unexpected instream header: " + headerString);
        }
    }

    private void drainInstreamChunks(InputStream in) throws IOException {
        byte[] lengthBytes = new byte[4];
        while (true) {
            readFully(in, lengthBytes);
            int length = ((lengthBytes[0] & 0xFF) << 24)
                    | ((lengthBytes[1] & 0xFF) << 16)
                    | ((lengthBytes[2] & 0xFF) << 8)
                    | (lengthBytes[3] & 0xFF);
            if (length == 0) {
                return;
            }
            byte[] chunk = new byte[length];
            readFully(in, chunk);
        }
    }

    private void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new IOException("unexpected end of stream");
            }
            offset += read;
        }
    }

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            serverSocket.close();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }
}
