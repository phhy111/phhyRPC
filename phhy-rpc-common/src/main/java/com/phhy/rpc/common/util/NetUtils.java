package com.phhy.rpc.common.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

public final class NetUtils {

    private NetUtils() {
    }

    public static String getLocalHost() {
        try {
            InetAddress candidateAddress = null;
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            if (networkInterfaces != null) {
                while (networkInterfaces.hasMoreElements()) {
                    NetworkInterface ni = networkInterfaces.nextElement();
                    Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddress = inetAddresses.nextElement();
                        if (!inetAddress.isLoopbackAddress() && inetAddress.isSiteLocalAddress()) {
                            return inetAddress.getHostAddress();
                        }
                        if (!inetAddress.isLoopbackAddress() && candidateAddress == null) {
                            candidateAddress = inetAddress;
                        }
                    }
                }
            }
            if (candidateAddress != null) {
                return candidateAddress.getHostAddress();
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (SocketException | UnknownHostException e) {
            return "127.0.0.1";
        }
    }
}
