package seti5.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class SocketWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketWriter.class);

    public static void write(SocketChannel writingSocket, SocketChannel companionSocket,
                             ByteBuffer buffer, boolean isClient) throws IOException {
        String source = isClient ? "Client" : "Server";
        if (buffer.hasRemaining()) {
            try {
                int numWrote = writingSocket.write(buffer);
                //LOGGER.info("Wrote {} bytes to {} {}", numWrote, source, writingSocket.getRemoteAddress());
            } catch (IOException e) {
                writingSocket.close();
                if (isClient) {
                    LOGGER.info("Client finished to receive data");
                } else {
                    companionSocket.close();
                    LOGGER.error("Failed to write to {} {}!", source, writingSocket.getRemoteAddress());
                }
            }

        } else {
            //LOGGER.info("Nothing to write to {} {}", source, writingSocket.getRemoteAddress());
            if (isClient && !companionSocket.isOpen()) {
                writingSocket.close();
                LOGGER.info("Finished connection. Closing client");
            }
        }
    }

}