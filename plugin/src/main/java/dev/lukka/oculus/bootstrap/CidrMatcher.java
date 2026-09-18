package dev.lukka.oculus.bootstrap;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class CidrMatcher {

    private final byte[] network;
    private final byte[] mask;

    public CidrMatcher(String cidr) throws IllegalArgumentException {
        if (cidr == null || cidr.isBlank()) {
            throw new IllegalArgumentException("CIDR cannot be empty");
        }
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) {
            // Treat as single IP /32 or /128
            try {
                InetAddress ip = InetAddress.getByName(parts[0]);
                this.network = ip.getAddress();
                int prefix = this.network.length * 8;
                this.mask = createMask(this.network.length, prefix);
                applyMask(this.network, this.mask);
                return;
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid IP address: " + parts[0], e);
            }
        }

        try {
            InetAddress ip = InetAddress.getByName(parts[0]);
            this.network = ip.getAddress();
            int prefix = Integer.parseInt(parts[1]);
            int maxPrefix = this.network.length * 8;
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException("Invalid prefix length: " + prefix);
            }
            this.mask = createMask(this.network.length, prefix);
            applyMask(this.network, this.mask);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
        }
    }

    public boolean matches(String ipStr) {
        if (ipStr == null || ipStr.isBlank()) {
            return false;
        }
        try {
            InetAddress ip = InetAddress.getByName(ipStr.trim());
            byte[] ipBytes = ip.getAddress();
            if (ipBytes.length != this.network.length) {
                return false;
            }
            for (int i = 0; i < ipBytes.length; i++) {
                if ((ipBytes[i] & mask[i]) != network[i]) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static byte[] createMask(int length, int prefix) {
        byte[] mask = new byte[length];
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            mask[i] = (byte) 0xFF;
        }
        if (remainingBits > 0 && fullBytes < length) {
            mask[fullBytes] = (byte) ((0xFF << (8 - remainingBits)) & 0xFF);
        }
        return mask;
    }

    private static void applyMask(byte[] network, byte[] mask) {
        for (int i = 0; i < network.length; i++) {
            network[i] = (byte) (network[i] & mask[i]);
        }
    }
}
