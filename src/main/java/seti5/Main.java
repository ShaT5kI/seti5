package seti5;

import seti5.proxy.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Main {
    private static final Logger LOGGER  = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: socks-proxy.jar <port>");
            return;
        }
        try {
            int port = Integer.parseInt(args[0]);
            Proxy proxy = new Proxy(port);
            proxy.work();
        } catch (NumberFormatException e) {
            System.err.println("Usage: socks-proxy.jar <port>");
        } catch (IOException e) {
            LOGGER.error("Error during proxy work. Terminating.", e);
        }
    }

}