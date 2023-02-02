package seti5.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class SocketReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketReader.class);
    private static final double BUFFER_THRESHOLD_VALUE = 0.5;

    public static boolean read(SocketChannel readingSocket, SocketChannel companionSocket,
                               ByteBuffer buffer, boolean isClient) throws IOException {
        String source = isClient ? "Client" : "Server";
        try {
            if (buffer.remaining() < BUFFER_THRESHOLD_VALUE * buffer.capacity()) {
                buffer.compact();
                int numRead = readingSocket.read(buffer);
                buffer.flip();
                //LOGGER.info("Read {} bytes from {} {}", numRead, source, readingSocket.getRemoteAddress());
                if (numRead == -1) { // FIN package
                    LOGGER.info("{} {} finished sending", source, readingSocket.getRemoteAddress());
                    if (!isClient) {
                        readingSocket.close();
                    } else {
                        readingSocket.shutdownInput();
                    }
                    return true;
                }
            } else {
                LOGGER.warn("Buffer from {} {} is busy! Not read: {}%", source, readingSocket.getRemoteAddress(),
                        (double) buffer.remaining() / buffer.capacity() * 100);
                if (!companionSocket.isOpen()) {
                    LOGGER.warn("No one is reading from buffer! Closing {} {}",
                            source, readingSocket.getRemoteAddress());
                    readingSocket.close();
                }
            }
            return false;
        } catch (IOException e) {
            readingSocket.close();
            if (!isClient) {
                LOGGER.info("Server finished sending"); // RST package
                return true;
            } else {
                LOGGER.warn("Failed to read from {}", source);
                companionSocket.close();
                return true;
            }
        }
    }
}
