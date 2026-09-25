package forge.nova.online;

import forge.gamemodes.net.server.FServerManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The addresses friends can use to reach this computer: local network, VPNs (Tailscale, ZeroTier,
 * Radmin, Hamachi...) and the public internet address.
 */
public final class NetInfo {
    private NetInfo() {
    }

    /** One way to reach the host. {@code kind}: "lan" or "vpn". */
    public record Address(String label, String ip, String kind) {
    }

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /** The address of the network adapter that leads to the internet (the one routers forward to). */
    public static String routableAddress() {
        try {
            return FServerManager.getLocalAddress();
        } catch (Throwable t) {
            return "localhost";
        }
    }

    /** Local IPv4 addresses, the main one first, with friendly adapter names (same rules as Forge's own lobby). */
    public static List<Address> localAddresses() {
        List<Address> out = new ArrayList<>();
        Map<String, String> all;
        try {
            all = FServerManager.getAllLocalAddresses();
        } catch (Throwable t) {
            all = Map.of("Local network", routableAddress());
        }
        for (Map.Entry<String, String> e : all.entrySet()) {
            String ip = e.getValue();
            String label = e.getKey();
            if (ip == null || !IPV4.matcher(ip).matches() || ip.startsWith("169.254.") || ip.startsWith("127.")) {
                continue; // link-local addresses only exist on one cable
            }
            String l = label.toLowerCase(Locale.ROOT);
            if (l.contains("virtualbox") || l.contains("vmware") || l.contains("vethernet") || l.contains("hyper-v") || l.contains("wsl")) {
                continue; // virtual machine adapters: friends can't reach those
            }
            boolean vpn = l.contains("tailscale") || l.contains("zerotier") || l.contains("hamachi") || l.contains("radmin")
                    || l.contains("wireguard") || l.contains("vpn") || l.contains("virtual network");
            out.add(new Address(vpn ? label : "Local network" + (label.isBlank() || l.startsWith("default") ? "" : " (" + label + ")"),
                    ip, vpn ? "vpn" : "lan"));
        }
        return out;
    }

    /** The address the internet sees (a few seconds at most); null when it can't be determined. */
    public static String publicIp() {
        for (String service : new String[]{"https://checkip.amazonaws.com", "https://api.ipify.org"}) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(service).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setRequestProperty("User-Agent", "Forge-Nova");
                if (c.getResponseCode() != 200) {
                    continue;
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII))) {
                    String ip = r.readLine();
                    if (ip != null && IPV4.matcher(ip.trim()).matches()) {
                        return ip.trim();
                    }
                }
            } catch (Exception ignored) {
                // try the next service
            }
        }
        return null;
    }

    /**
     * A router whose own internet address is private or "carrier-grade NAT" sits behind another router
     * of the internet provider: port forwarding cannot reach this computer from the internet then.
     */
    public static boolean isPrivateAddress(String ip) {
        if (ip == null) {
            return false;
        }
        var m = IPV4.matcher(ip);
        if (!m.matches()) {
            return false;
        }
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127);
    }
}
