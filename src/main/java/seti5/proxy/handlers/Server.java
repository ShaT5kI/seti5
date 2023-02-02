package seti5.proxy.handlers;

import seti5.proxy.handlers.interfaces.SocketConnectHandler;
import seti5.proxy.handlers.interfaces.SocketReadHandler;
import seti5.proxy.handlers.interfaces.SocketWriteHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import seti5.utils.SocketReader;
import seti5.utils.SocketWriter;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class Server implements SocketWriteHandler, SocketReadHandler, SocketConnectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

    private final ByteBuffer bufferToServer;
    private final ByteBuffer bufferFromServer;
    private final SocketChannel serverSocket;
    private final Client client;
    private final Selector selector;

    public Server (ByteBuffer bufferToServer, ByteBuffer bufferFromServer,
                   SocketChannel serverSocket, Client client, Selector selector) {
        this.bufferToServer = bufferToServer;
        this.bufferFromServer = bufferFromServer;
        this.serverSocket = serverSocket;
        this.client = client;
        this.selector = selector;
    }

    @Override
    public void handleConnect() throws IOException {
        try {
            client.connectedToServer();
            serverSocket.finishConnect();
            serverSocket.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
            LOGGER.info("Connected to server {}", serverSocket.getRemoteAddress());
        } catch (ConnectException e) {
            client.failedToConnectToServer();
            serverSocket.close();
            LOGGER.warn("Failed to connect to server");
        } catch (IOException e) {
            client.failedToConnectToServer();
            serverSocket.close();
            LOGGER.warn("Failed to connect to server");
            throw e;
        }
    }

    @Override
    public void handleRead() throws IOException {
        SocketReader.read(serverSocket, client.getSocket(), bufferFromServer, false);
    }

    @Override
    public void handleWrite() throws IOException {
        SocketWriter.write(serverSocket, client.getSocket(), bufferToServer, false);
    }

}
