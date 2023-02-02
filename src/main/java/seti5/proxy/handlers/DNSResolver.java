package seti5.proxy.handlers;

import org.xbill.DNS.Record;
import seti5.proxy.handlers.interfaces.SocketReadHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;


import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Map;

public class DNSResolver implements SocketReadHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DNSResolver.class);
    private static final int DNS_SERVER_PORT = 53;
    private static final int DNS_BUFFER_SIZE = 1024;

    private final DatagramChannel dnsSocket;
    private final ByteBuffer buffer = ByteBuffer.allocate(DNS_BUFFER_SIZE);
    private final Map<Integer, Client> dnsRequests = new HashMap<>();

    public DNSResolver(Selector selector) throws IOException {
        dnsSocket = DatagramChannel.open();
        dnsSocket.configureBlocking(false);
        dnsSocket.register(selector, SelectionKey.OP_READ, this);

        String[] dnsServers = ResolverConfig.getCurrentConfig().servers();
        if (dnsServers.length == 0){
            throw new IOException("Failed to find DNS-resolver server!");
        }
        InetAddress dnsAddr = InetAddress.getByName(dnsServers[0]);
        dnsSocket.connect(new InetSocketAddress(dnsAddr, DNSResolver.DNS_SERVER_PORT));
    }

    @Override
    public void handleRead() throws IOException {
        buffer.clear();
        int len = dnsSocket.read(buffer);
        if (len <= 0) {
            return;
        }
        Message msg = new Message(buffer.array());
        Record[] recs = msg.getSectionArray(Section.ANSWER);
        for (Record rec : recs) {
            if (rec instanceof ARecord) {
                ARecord aRecord = (ARecord)rec;
                int id = msg.getHeader().getID();
                Client client = dnsRequests.get(id);
                if (client == null) {
                    continue;
                }
                InetAddress addr = aRecord.getAddress();
                client.openServerSocket(addr);
                LOGGER.info("Resolved Domain name {}", aRecord.getName().toString());
                dnsRequests.remove(id);
            }
        }
    }

    public void resolve(String domainName, Client client) throws IOException {
        Name name = Name.fromString(domainName, Name.root);
        Record rec = Record.newRecord(name, Type.A, DClass.IN);
        Message msg = Message.newQuery(rec);
        dnsSocket.write(ByteBuffer.wrap(msg.toWire()));
        dnsRequests.put(msg.getHeader().getID(), client);
        LOGGER.info("Sent request to resolve domain name {}", domainName);
    }
}
