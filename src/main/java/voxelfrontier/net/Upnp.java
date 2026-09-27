package voxelfrontier.net;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP IGD でルーターにポート開放を依頼する (インターネット越しに参加してもらうため)。
 * 対応していないルーターや、UPnPが無効なルーターでは失敗するので、その場合は手動でポート開放してもらう。
 *
 * 手順: SSDPでルーターを探す → 機器説明XMLから WANIPConnection の制御URLを得る → SOAPで AddPortMapping。
 */
public final class Upnp {

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
    };
    private static final String[] SERVICE_TYPES = {
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
    };
    private static final Pattern LOCATION = Pattern.compile("(?im)^location:\\s*(\\S+)\\s*$");

    private final HttpClient http;
    private final URI controlUrl;
    private final String serviceType;
    private final InetAddress localAddress;

    private Upnp(HttpClient http, URI controlUrl, String serviceType, InetAddress localAddress) {
        this.http = http;
        this.controlUrl = controlUrl;
        this.serviceType = serviceType;
        this.localAddress = localAddress;
    }

    /** LAN内のUPnP対応ルーターを探す。見つからなければ空。 */
    public static Optional<Upnp> discover(int timeoutMillis) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis)).build();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMillis);
            InetSocketAddress target = new InetSocketAddress(SSDP_ADDRESS, SSDP_PORT);
            for (String st : SEARCH_TARGETS) {
                String request = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + st + "\r\n\r\n";
                byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(bytes, bytes.length, target));
            }

            byte[] buffer = new byte[2048];
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String text = new String(response.getData(), 0, response.getLength(), StandardCharsets.US_ASCII);
                Matcher m = LOCATION.matcher(text);
                if (!m.find()) {
                    continue;
                }
                Optional<Upnp> found = fromDescription(http, URI.create(m.group(1)), response.getAddress());
                if (found.isPresent()) {
                    return found;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[UPnP] ルーターの検索に失敗しました: " + e.getMessage());
        }
        return Optional.empty();
    }

    private static Optional<Upnp> fromDescription(HttpClient http, URI location, InetAddress router) {
        try {
            HttpResponse<byte[]> res = http.send(
                    HttpRequest.newBuilder(location).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                return Optional.empty();
            }
            Document doc = parseXml(res.body());
            URI base = location;
            NodeList urlBase = doc.getElementsByTagName("URLBase");
            if (urlBase.getLength() > 0 && !urlBase.item(0).getTextContent().isBlank()) {
                base = URI.create(urlBase.item(0).getTextContent().strip());
            }

            NodeList services = doc.getElementsByTagName("service");
            for (String wanted : SERVICE_TYPES) {
                for (int i = 0; i < services.getLength(); i++) {
                    Element service = (Element) services.item(i);
                    String type = childText(service, "serviceType");
                    String control = childText(service, "controlURL");
                    if (wanted.equals(type) && control != null) {
                        InetAddress local;
                        try (DatagramSocket probe = new DatagramSocket()) {
                            probe.connect(router, SSDP_PORT); // ルーターへ向かうときに使われる自分のアドレス
                            local = probe.getLocalAddress();
                        }
                        return Optional.of(new Upnp(http, base.resolve(control), type, local));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[UPnP] 機器情報を読めませんでした (" + location + "): " + e.getMessage());
        }
        return Optional.empty();
    }

    public InetAddress localAddress() {
        return localAddress;
    }

    /** TCPポートを開放する。成功したら true。 */
    public boolean addPortMapping(int port, String description) {
        String args = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>"
                + "<NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + localAddress.getHostAddress() + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>" + escape(description) + "</NewPortMappingDescription>"
                + "<NewLeaseDuration>0</NewLeaseDuration>";
        return soap("AddPortMapping", args).isPresent();
    }

    public void deletePortMapping(int port) {
        String args = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>";
        soap("DeletePortMapping", args);
    }

    /** ルーターのグローバルIPアドレス (友達に伝えるアドレス)。 */
    public Optional<String> externalIpAddress() {
        return soap("GetExternalIPAddress", "").flatMap(body -> {
            Matcher m = Pattern.compile("<NewExternalIPAddress>([^<]*)</NewExternalIPAddress>").matcher(body);
            return m.find() && !m.group(1).isBlank() ? Optional.of(m.group(1)) : Optional.empty();
        });
    }

    private Optional<String> soap(String action, String args) {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        try {
            HttpRequest request = HttpRequest.newBuilder(controlUrl)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "text/xml; charset=\"utf-8\"")
                    .header("SOAPAction", "\"" + serviceType + "#" + action + "\"")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                return Optional.of(res.body());
            }
            System.err.println("[UPnP] " + action + " がエラーを返しました (HTTP " + res.statusCode() + ")");
        } catch (IOException e) {
            System.err.println("[UPnP] " + action + " に失敗しました: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    private static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // ルーターから来たXMLなので、外部エンティティは読まない
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder builder = f.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml));
    }

    private static String childText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() > 0 ? list.item(0).getTextContent().strip() : null;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
