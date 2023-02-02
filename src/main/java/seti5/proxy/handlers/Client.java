package seti5.proxy.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.TextParseException;
import seti5.proxy.Proxy;
import seti5.proxy.handlers.interfaces.SocketReadHandler;
import seti5.proxy.handlers.interfaces.SocketWriteHandler;
import seti5.utils.SocketReader;
import seti5.utils.SocketWriter;


import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class Client implements SocketWriteHandler, SocketReadHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Client.class);

    enum Status {
        NO_CONNECT,
        AUTHENTICATED,
        CONNECTED
    }

    private final ByteBuffer bufferFromClient;
    private final ByteBuffer bufferToClient;
    private final SocketChannel clientSocket;
    private final DNSResolver dnsResolver;
    private final Selector selector;
    private SocketChannel serverSocket;
    private int serverPort;
    private Status status = Status.NO_CONNECT;
    private InetSocketAddress proxyAddress;

    public Client(SocketChannel clientSocket, Selector selector, DNSResolver dnsResolver) throws IOException {
        this.clientSocket = clientSocket;
        this.dnsResolver = dnsResolver;
        this.selector = selector;
        bufferFromClient = ByteBuffer.allocate(Proxy.REQUEST_BUFFER_SIZE);
        bufferToClient = ByteBuffer.allocate(Proxy.RESPONSE_BUFFER_SIZE);
        proxyAddress = (InetSocketAddress)clientSocket.getLocalAddress();
    }

    @Override
    public void handleRead() throws IOException {
        if (status == Status.NO_CONNECT){
            authentication();
        }
        else if (status == Status.AUTHENTICATED){
            connection();
        }
        else {
            boolean isEof = SocketReader.read(clientSocket, serverSocket, bufferFromClient, true);
            if (isEof && clientSocket.isOpen()) {
                clientSocket.register(selector, SelectionKey.OP_WRITE, this);
            }
        }
    }

    @Override
    public void handleWrite() throws IOException {
        SocketWriter.write(clientSocket, serverSocket, bufferToClient, true);
        if (status == Status.NO_CONNECT) {
            clientSocket.close();
        }
        if (status == Status.AUTHENTICATED) {
            clientSocket.register(selector, SelectionKey.OP_READ, this);
        }
    }

    private void authentication() throws IOException {
        LOGGER.info("Client {} authentication...", clientSocket.getRemoteAddress());
        bufferFromClient.clear();
        bufferToClient.clear();
        int numRead = clientSocket.read(bufferFromClient);
        bufferFromClient.flip();
        LOGGER.info("Read from client {} bytes", numRead);
        if (numRead < 3) {
            LOGGER.warn("Too short message from client!");
            clientSocket.close();
            return;
        }

        byte socksVersion = bufferFromClient.get();
        if (socksVersion != (byte) 0x05) { // support only socks5
            LOGGER.warn("Authentication fail. Wrong socks version!");
        }

        byte numAuthMethods = bufferFromClient.get();
        boolean foundNoAuthMethod = false;
        for (byte i = 0; i < numAuthMethods; i++) {
            byte authMethod =bufferFromClient.get();
            if (authMethod == (byte) 0x00){ // noAuth byte
                foundNoAuthMethod = true;
            }
        }

        bufferToClient.put((byte) 0x05);
        if (!foundNoAuthMethod) {
            bufferToClient.put((byte) 0xFF); // did not find noAuth method
            LOGGER.warn("Authentication fail. Did not find noAuth method!");
        } else {
            bufferToClient.put((byte) 0x00); // noAuth method
        }
        bufferToClient.flip();

        if (foundNoAuthMethod && socksVersion == (byte) 0x05) {
            status = Status.AUTHENTICATED;
            LOGGER.info("Authentication success");
        }
        clientSocket.register(selector, SelectionKey.OP_WRITE, this);
    }

    private void connection() throws IOException {
        LOGGER.info("Client {} connection...", clientSocket.getRemoteAddress());
        bufferFromClient.clear();
        bufferToClient.clear();
        int numRead = clientSocket.read(bufferFromClient);
        bufferFromClient.flip();
        LOGGER.info("Read from client {} bytes", numRead);
        if (numRead < 10) {
            LOGGER.warn("Too short message from client!");
            clientSocket.close();
            return;
        }

        boolean connectionFailed = false;

        byte socksVersion = bufferFromClient.get();
        if (socksVersion != (byte) 0x05) { // support only socks5
            connectionFailed = true;
            LOGGER.warn("Connection fail. Wrong socks version!");
        }
        bufferToClient.put((byte) 0x05);

        byte command = bufferFromClient.get();
        if (command != (byte) 0x01) { // establish a TCP/IP stream connection
            connectionFailed = true;
            bufferToClient.put((byte) 0x07);
            LOGGER.warn("Connection failed. Unsupported client command!");
        }

        bufferFromClient.get();  // byte reserved for future use

        byte addrType = bufferFromClient.get();
        byte[] addr;
        InetAddress serverAddr = null;
        if (addrType == (byte) 0x01) { // IPv4 addr
            LOGGER.info("Address is IPv4. No need to resolve");
            StringBuilder ipv4Addr;
            addr = new byte[4];
            ipv4Addr = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                addr[i] = bufferFromClient.get();
                ipv4Addr.append(Byte.toUnsignedInt(addr[i]));
                if (i != 3) {
                    ipv4Addr.append('.');
                }
            }
            bufferToClient.put((byte) 0x00); //OK
            serverAddr = InetAddress.getByName(ipv4Addr.toString());
        } else if (addrType == (byte) 0x03 ) { // domain name
            byte domainNameLength = bufferFromClient.get();
            addr = new byte[domainNameLength];
            bufferFromClient.get(addr);
            String domainName = new String(addr, StandardCharsets.UTF_8);
            domainName = domainName + "."; // root zone
            if (!connectionFailed) {
                LOGGER.info("Address is a domain name. Need to resolve");
                try{
                    dnsResolver.resolve(domainName, this);
                    bufferToClient.put((byte) 0x00); //OK
                } catch (TextParseException e){
                    LOGGER.warn("Connection failed. Failed to parse domain name {}!", domainName);
                    connectionFailed = true;
                    bufferToClient.put((byte) 0x04); // host unreachable
                } catch (IOException e) {
                    LOGGER.warn("Connection failed. Failed to write to DNS-Resolver Server!");
                    connectionFailed = true;
                    bufferToClient.put((byte) 0x01); // general failure
                }
            }
        } else if (addrType == (byte) 0x04) { // IPv6 addr
            addr = new byte[16];
            bufferFromClient.get(addr);
            connectionFailed = true;
            bufferToClient.put((byte) 0x08);
            LOGGER.warn("Connection failed. IPv6 address is not supported!");
        } else {
            addr = new byte[0];
            connectionFailed = true;
            bufferToClient.put((byte) 0x07);
            LOGGER.warn("Connection failed. Unknown address type!");
        }

        byte portPart1 = bufferFromClient.get();
        byte portPart2 = bufferFromClient.get();
        serverPort = (Byte.toUnsignedInt(portPart1) << 8) | Byte.toUnsignedInt(portPart2);

        bufferToClient.put((byte) 0x00); // reserved
        bufferToClient.put((byte) 0x01); // IPv4
        bufferToClient.put(proxyAddress.getAddress().getAddress());
        bufferToClient.put((byte) (proxyAddress.getPort() >> 8));
        bufferToClient.put((byte) proxyAddress.getPort());
        bufferToClient.flip();

        if (!connectionFailed && addrType == (byte) 0x01) {
            openServerSocket(serverAddr);
        }

        if (connectionFailed) {
            clientSocket.register(selector,  SelectionKey.OP_WRITE, this);
            status = Status.NO_CONNECT;
        } else {
            clientSocket.keyFor(selector).cancel();
        }
    }

    public void openServerSocket(InetAddress serverAddr) throws IOException {
        SocketAddress serverAddress = new InetSocketAddress(serverAddr, serverPort);
        serverSocket = SocketChannel.open();
        serverSocket.configureBlocking(false);
        serverSocket.connect(serverAddress);
        Server server = new Server(bufferFromClient, bufferToClient,
                serverSocket, this, selector);
        serverSocket.register(selector, SelectionKey.OP_CONNECT, server);
        LOGGER.info("Created server socket {}:{}", serverAddr, serverPort);
    }

    public void connectedToServer() throws IOException {
        clientSocket.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
        status = Status.CONNECTED;
    }

    public void failedToConnectToServer() throws IOException {
        bufferToClient.put (1, (byte) 0x04); // host unreachable
        clientSocket.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
    }

    public SocketChannel getSocket() {
        return clientSocket;
    }

}
