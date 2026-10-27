package id.nvr.stb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Klien ACME (RFC 8555) mini untuk Let's Encrypt, validasi HTTP-01.
 * Java murni (tanpa API Android) supaya bisa diuji di PC.
 *
 * File di folder kerja: account.key (kunci akun), domain.key + cert.pem (sertifikat aktif).
 */
public class Acme {

    public static final String LETSENCRYPT = "https://acme-v02.api.letsencrypt.org/directory";

    /** token -> key authorization, dilayani web server di /.well-known/acme-challenge/<token>. */
    public static final Map<String, String> CHALLENGES = new ConcurrentHashMap<>();

    public interface Logger { void log(String msg); }

    private final String directoryUrl;
    private final File dir;
    private final SSLSocketFactory ssl;
    private final Logger log;

    private Map<String, Object> directory;
    private String nonce;
    private KeyPair account;
    private String kid;

    public Acme(String directoryUrl, File dir, SSLSocketFactory ssl, Logger log) {
        this.directoryUrl = directoryUrl;
        this.dir = dir;
        this.ssl = ssl;
        this.log = log == null ? new Logger() { public void log(String m) { } } : log;
        dir.mkdirs();
    }

    public File keyFile() { return new File(dir, "domain.key"); }
    public File certFile() { return new File(dir, "cert.pem"); }

    // =====================================================================
    //  Alur utama
    // =====================================================================
    public void obtain(String domain, String email) throws Exception {
        log.log("Menghubungi " + directoryUrl);
        directory = Json.obj(request("GET", directoryUrl, null, null).body);
        freshNonce();

        // 1. Akun
        File accFile = new File(dir, "account.key");
        account = accFile.isFile() ? loadKeyPair(accFile) : null;
        if (account == null) {
            log.log("Membuat kunci akun…");
            account = newRsa();
            writeFile(accFile, account.getPrivate().getEncoded());
        }
        String payload = "{\"termsOfServiceAgreed\":true"
                + (email == null || email.trim().isEmpty() ? "" : ",\"contact\":[" + Json.q("mailto:" + email.trim()) + "]")
                + "}";
        Resp acc = post(Json.str(directory, "newAccount"), payload, true);
        kid = acc.header("location");
        if (kid == null) throw new IOException("Akun ACME tidak punya URL (Location)");
        log.log("Akun: " + kid);

        // 2. Order
        Resp ord = post(Json.str(directory, "newOrder"),
                "{\"identifiers\":[{\"type\":\"dns\",\"value\":" + Json.q(domain) + "}]}", false);
        String orderUrl = ord.header("location");
        Map<String, Object> order = Json.obj(ord.body);

        // 3. Validasi HTTP-01 untuk tiap authorization
        for (Object a : Json.list(order, "authorizations")) authorize(String.valueOf(a));

        // 4. Finalize dengan CSR
        log.log("Membuat kunci domain & CSR…");
        KeyPair domainKey = newRsa();
        byte[] csr = csr(domain, domainKey);
        post(Json.str(order, "finalize"), "{\"csr\":" + Json.q(b64u(csr)) + "}", false);

        // 5. Tunggu sertifikat
        String certUrl = null;
        for (int t = 0; t < 60 && certUrl == null; t++) {
            order = Json.obj(post(orderUrl, "", false).body);
            String st = Json.str(order, "status");
            if ("valid".equals(st)) certUrl = Json.str(order, "certificate");
            else if ("invalid".equals(st)) throw new IOException("Order ditolak: " + order);
            else Thread.sleep(2000);
        }
        if (certUrl == null) throw new IOException("Sertifikat tidak kunjung terbit");
        String pem = post(certUrl, "", false).body;
        if (!pem.contains("BEGIN CERTIFICATE")) throw new IOException("Respons sertifikat tidak valid");

        // 6. Simpan (tulis ke file sementara dulu, lalu ganti)
        File k = new File(dir, "domain.key.new"), c = new File(dir, "cert.pem.new");
        writeFile(k, domainKey.getPrivate().getEncoded());
        writeFile(c, pem.getBytes("UTF-8"));
        keyFile().delete();
        certFile().delete();
        if (!k.renameTo(keyFile()) || !c.renameTo(certFile())) throw new IOException("Gagal menyimpan sertifikat");
        log.log("Sertifikat terbit ✓ berlaku sampai " + certExpiry(certFile()));
    }

    private void authorize(String authzUrl) throws Exception {
        Map<String, Object> authz = Json.obj(post(authzUrl, "", false).body);
        if ("valid".equals(Json.str(authz, "status"))) return;
        Map<String, Object> ch = null;
        for (Object o : Json.list(authz, "challenges")) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            if ("http-01".equals(Json.str(m, "type"))) ch = m;
        }
        if (ch == null) throw new IOException("Server tidak menawarkan validasi http-01");
        String token = Json.str(ch, "token");
        CHALLENGES.put(token, token + "." + thumbprint(account.getPublic()));
        try {
            log.log("Validasi domain lewat http://…/.well-known/acme-challenge/" + token);
            post(Json.str(ch, "url"), "{}", false);
            for (int t = 0; t < 45; t++) {
                Thread.sleep(2000);
                authz = Json.obj(post(authzUrl, "", false).body);
                String st = Json.str(authz, "status");
                if ("valid".equals(st)) { log.log("Domain tervalidasi ✓"); return; }
                if ("invalid".equals(st)) {
                    String detail = "";
                    for (Object o : Json.list(authz, "challenges")) {
                        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                        Object err = m.get("error");
                        if (err instanceof Map) {
                            @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) err;
                            detail = Json.str(e, "detail");
                        }
                    }
                    throw new IOException("Validasi gagal: " + detail
                            + " — pastikan port 80 publik di-DNAT ke STB port web.");
                }
            }
            throw new IOException("Validasi domain terlalu lama");
        } finally {
            CHALLENGES.remove(token);
        }
    }

    // =====================================================================
    //  HTTP + JWS
    // =====================================================================
    private static class Resp {
        int code;
        String body;
        final Map<String, String> headers = new java.util.HashMap<>();
        String header(String k) { return headers.get(k.toLowerCase()); }
    }

    private Resp request(String method, String url, byte[] body, String contentType) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && ssl != null) ((HttpsURLConnection) c).setSSLSocketFactory(ssl);
        c.setRequestMethod(method);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "STB-NVR-acme/1.0");
        c.setRequestProperty("Accept", "application/pem-certificate-chain, application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null && !e.getValue().isEmpty()) r.headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
        }
        InputStream is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = is == null ? "" : new String(readAll(is), "UTF-8");
        c.disconnect();
        String n = r.header("replay-nonce");
        if (n != null) nonce = n;
        return r;
    }

    private void freshNonce() throws IOException {
        request("HEAD", Json.str(directory, "newNonce"), null, null);
        if (nonce == null) throw new IOException("Tidak dapat nonce ACME");
    }

    /** POST JWS. payload "" = POST-as-GET. useJwk: true untuk newAccount. */
    private Resp post(String url, String payload, boolean useJwk) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (nonce == null) freshNonce();
            String prot = "{\"alg\":\"RS256\","
                    + (useJwk ? "\"jwk\":" + jwk(account.getPublic()) : "\"kid\":" + Json.q(kid))
                    + ",\"nonce\":" + Json.q(nonce) + ",\"url\":" + Json.q(url) + "}";
            nonce = null;
            String p64 = b64u(prot.getBytes("UTF-8"));
            String b64 = payload.isEmpty() ? "" : b64u(payload.getBytes("UTF-8"));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(account.getPrivate());
            sig.update((p64 + "." + b64).getBytes("UTF-8"));
            String jws = "{\"protected\":" + Json.q(p64) + ",\"payload\":" + Json.q(b64)
                    + ",\"signature\":" + Json.q(b64u(sig.sign())) + "}";
            Resp r = request("POST", url, jws.getBytes("UTF-8"), "application/jose+json");
            if (r.code < 400) return r;
            if (r.body.contains("badNonce")) continue;          // nonce kedaluwarsa -> ulangi
            String detail = r.body;
            try { detail = Json.str(Json.obj(r.body), "detail"); } catch (Exception ignored) { }
            throw new IOException("ACME " + r.code + ": " + detail);
        }
        throw new IOException("ACME: nonce terus ditolak");
    }

    // =====================================================================
    //  Kunci, JWK, CSR
    // =====================================================================
    private static KeyPair newRsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    public static PrivateKey loadPrivateKey(File f) throws Exception {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readFile(f)));
    }

    private static KeyPair loadKeyPair(File f) {
        try {
            PrivateKey pk = loadPrivateKey(f);
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pk;
            PublicKey pub = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new KeyPair(pub, pk);
        } catch (Exception e) {
            return null;
        }
    }

    private static String jwk(PublicKey k) {
        RSAPublicKey r = (RSAPublicKey) k;
        // Urutan kunci e, kty, n (urutan leksikografis) -> juga dipakai untuk thumbprint.
        return "{\"e\":" + Json.q(b64u(unsigned(r.getPublicExponent()))) + ",\"kty\":\"RSA\",\"n\":"
                + Json.q(b64u(unsigned(r.getModulus()))) + "}";
    }

    private static String thumbprint(PublicKey k) throws Exception {
        return b64u(MessageDigest.getInstance("SHA-256").digest(jwk(k).getBytes("UTF-8")));
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    /** CSR PKCS#10 (DER) dengan CN + subjectAltName = domain, ditandatangani SHA256withRSA. */
    static byte[] csr(String domain, KeyPair kp) throws Exception {
        byte[] name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, domain.getBytes("UTF-8")))));
        byte[] san = seq(tlv(0x82, domain.getBytes("US-ASCII")));                        // dNSName
        byte[] ext = seq(seq(oid("2.5.29.17"), tlv(0x04, san)));                           // Extensions
        byte[] attrs = tlv(0xA0, seq(oid("1.2.840.113549.1.9.14"), set(ext)));             // [0] extensionRequest
        byte[] info = seq(tlv(0x02, new byte[]{0}), name, kp.getPublic().getEncoded(), attrs);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(info);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(info, seq(oid("1.2.840.113549.1.1.11"), new byte[]{0x05, 0x00}), tlv(0x03, bits));
    }

    // ---- DER mini ----
    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = v.length;
        if (n < 0x80) o.write(n);
        else if (n < 0x100) { o.write(0x81); o.write(n); }
        else if (n < 0x10000) { o.write(0x82); o.write(n >> 8); o.write(n & 0xff); }
        else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xff); o.write(n & 0xff); }
        o.write(v, 0, n);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... p) { return tlv(0x30, cat(p)); }
    private static byte[] set(byte[]... p) { return tlv(0x31, cat(p)); }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            do { tmp[n++] = (byte) (v & 0x7f); v >>= 7; } while (v > 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    // =====================================================================
    //  TLS: server dari sertifikat tersimpan, klien dengan root CA tertentu
    // =====================================================================
    public static List<X509Certificate> loadChain(File pem) throws Exception {
        FileInputStream in = new FileInputStream(pem);
        try {
            Collection<? extends Certificate> cs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            List<X509Certificate> out = new ArrayList<>();
            for (Certificate c : cs) out.add((X509Certificate) c);
            return out;
        } finally {
            in.close();
        }
    }

    public static java.util.Date certExpiry(File pem) {
        try { return loadChain(pem).get(0).getNotAfter(); } catch (Exception e) { return null; }
    }

    /** Apakah sertifikat tersimpan untuk domain ini dan masih berlaku > minDays hari. */
    public boolean certValidFor(String domain, int minDays) {
        try {
            if (!keyFile().isFile() || !certFile().isFile()) return false;
            X509Certificate c = loadChain(certFile()).get(0);
            Collection<List<?>> sans = c.getSubjectAlternativeNames();
            boolean match = false;
            if (sans != null) for (List<?> e : sans) if (domain.equalsIgnoreCase(String.valueOf(e.get(1)))) match = true;
            return match && c.getNotAfter().getTime() - System.currentTimeMillis() > minDays * 86_400_000L;
        } catch (Exception e) {
            return false;
        }
    }

    public SSLServerSocketFactory serverFactory() throws Exception {
        PrivateKey key = loadPrivateKey(keyFile());
        List<X509Certificate> chain = loadChain(certFile());
        char[] pass = "stbnvr".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("nvr", key, pass, chain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx.getServerSocketFactory();
    }

    /** SSLSocketFactory klien yang hanya mempercayai root CA dari teks PEM (mis. ISRG Root X1/X2). */
    public static SSLSocketFactory clientFactory(String rootsPem) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int n = 0;
        for (Certificate c : CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(rootsPem.getBytes("US-ASCII")))) {
            ks.setCertificateEntry("root" + (n++), c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx.getSocketFactory();
    }

    // =====================================================================
    //  Util
    // =====================================================================
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    static String b64u(byte[] d) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (; i + 2 < d.length; i += 3) {
            int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
            sb.append(B64[v >> 18]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]).append(B64[v & 63]);
        }
        if (d.length - i == 1) {
            int v = (d[i] & 0xff) << 16;
            sb.append(B64[v >> 18]).append(B64[(v >> 12) & 63]);
        } else if (d.length - i == 2) {
            int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8);
            sb.append(B64[v >> 18]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]);
        }
        return sb.toString();
    }

    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) o.write(b, 0, n);
        is.close();
        return o.toByteArray();
    }

    static byte[] readFile(File f) throws IOException { return readAll(new FileInputStream(f)); }

    static void writeFile(File f, byte[] d) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(d); o.getFD().sync(); } finally { o.close(); }
    }
}
