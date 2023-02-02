package seti5.proxy;


import seti5.proxy.handlers.Client;
import seti5.proxy.handlers.DNSResolver;
import seti5.proxy.handlers.interfaces.SocketConnectHandler;
import seti5.proxy.handlers.interfaces.SocketReadHandler;
import seti5.proxy.handlers.interfaces.SocketWriteHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
public class Proxy {

    private static final Logger LOGGER = LoggerFactory.getLogger(Proxy.class);
    public static final int REQUEST_BUFFER_SIZE = 512;
    public static final int RESPONSE_BUFFER_SIZE = 2048;

    private Selector selector;
    private ServerSocketChannel serverSocket;
    private DNSResolver dnsResolver;

    public Proxy(int port) throws IOException {
        selector = Selector.open();

        serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(port));
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        dnsResolver = new DNSResolver(selector);
    }

    public void work() throws IOException {
        while (true) {
            selector.select();
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                if (key.isValid() && key.isAcceptable()) {
                    registerClient(serverSocket, selector);
                }
                if (key.isValid() && key.isConnectable()) {
                    SocketConnectHandler handler = (SocketConnectHandler) key.attachment();
                    handler.handleConnect();
                }
                if (key.isValid() && key.isReadable()) {
                    SocketReadHandler handler = (SocketReadHandler) key.attachment();
                    handler.handleRead();
                }
                if (key.isValid() && key.isWritable()) {
                    SocketWriteHandler handler = (SocketWriteHandler) key.attachment();
                    handler.handleWrite();
                }
                iter.remove();
            }
        }
    }

    private void registerClient(ServerSocketChannel serverSocketChannel, Selector selector) throws IOException {
        SocketChannel clientSocket = serverSocketChannel.accept();
        Client client = new Client(clientSocket, selector, dnsResolver);
        clientSocket.configureBlocking(false);
        clientSocket.register(selector, SelectionKey.OP_READ, client);
        LOGGER.info("Accepted new client: {}", clientSocket.getRemoteAddress());
    }

}
