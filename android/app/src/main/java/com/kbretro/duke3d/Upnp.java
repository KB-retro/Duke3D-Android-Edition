package com.kbretro.duke3d;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abre el puerto del juego en el router (UPnP IGD) para jugar por internet sin configurar nada
 * a mano, y averigua la IP pública. Funciona en la mayoría de routers domésticos con UPnP
 * activado; con CG-NAT del operador no hay puerto que abrir (se avisa).
 */
final class Upnp {
    /** Resultado: IP pública (o null) y un mensaje para enseñar. */
    static final class Result {
        final String publicIp;
        final boolean opened;
        final boolean cgnat;

        Result(String publicIp, boolean opened, boolean cgnat) {
            this.publicIp = publicIp;
            this.opened = opened;
            this.cgnat = cgnat;
        }
    }

    private static final String[] SERVICES = {
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
    };

    /** Busca el router, abre el puerto UDP y pide la IP pública. Bloquea unos segundos. */
    static Result openPort(String localIp, int port) {
        try {
            for (String location : discover()) {
                String xml = get(location);
                if (xml == null) continue;
                for (String service : SERVICES) {
                    String control = controlUrl(xml, service, location);
                    if (control == null) continue;
                    String ip = externalIp(control, service);
                    boolean opened = addMapping(control, service, localIp, port, 7200)
                            || addMapping(control, service, localIp, port, 0);
                    android.util.Log.i("Duke3D", "UPnP: " + service + " ip=" + ip + " abierto=" + opened);
                    return new Result(ip, opened, ip != null && isPrivate(ip));
                }
            }
        } catch (Exception e) {
            android.util.Log.w("Duke3D", "UPnP", e);
        }
        return new Result(null, false, false);
    }

    /** Las direcciones (LOCATION) de los routers que responden a la búsqueda SSDP. */
    private static Set<String> discover() throws Exception {
        Set<String> found = new LinkedHashSet<>();
        String req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(2500);
            byte[] data = req.getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) s.send(new DatagramPacket(data, data.length, group, 1900));
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                Matcher m = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(resp);
                if (m.find()) found.add(m.group(1).trim());
            }
        }
        return found;
    }

    private static String controlUrl(String xml, String service, String location) throws Exception {
        int i = xml.indexOf(service);
        if (i < 0) return null;
        Matcher m = Pattern.compile("<controlURL>\\s*([^<]+?)\\s*</controlURL>").matcher(xml);
        if (!m.find(i)) return null;
        return new URL(new URL(location), m.group(1)).toString();
    }

    private static String externalIp(String control, String service) {
        String resp = soap(control, service, "GetExternalIPAddress", "");
        if (resp == null) return null;
        Matcher m = Pattern.compile("<NewExternalIPAddress>\\s*([^<]+?)\\s*</NewExternalIPAddress>").matcher(resp);
        return m.find() ? m.group(1) : null;
    }

    private static boolean addMapping(String control, String service, String localIp, int port, int lease) {
        String args = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>UDP</NewProtocol>"
                + "<NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + localIp + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>Duke3D Android Edition</NewPortMappingDescription>"
                + "<NewLeaseDuration>" + lease + "</NewLeaseDuration>";
        return soap(control, service, "AddPortMapping", args) != null;
    }

    /** Una llamada SOAP; devuelve la respuesta o null si falla. */
    private static String soap(String control, String service, String action, String args) {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(control).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            if (c.getResponseCode() != 200) return null;
            return read(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            return read(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toString("UTF-8");
    }

    /** IP privada o de CG-NAT (100.64.0.0/10): desde fuera no se puede llegar. */
    static boolean isPrivate(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Upnp() {
    }
}
