package id.nvr.stb;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Uji integrasi klien ACME terhadap Pebble (server uji resmi Let's Encrypt), dijalankan di CI.
 * Argumen: path pebble.minica.pem
 */
public class AcmeIT {
    public static void main(String[] args) throws Exception {
        String domain = "nvr-test.example.com";
        String roots = new String(Files.readAllBytes(new File(args[0]).toPath()), "US-ASCII");

        // Server HTTP-01 di port 5002 (port validasi bawaan Pebble), sama seperti WebServer di STB.
        HttpServer http = HttpServer.create(new InetSocketAddress(5002), 0);
        http.createContext("/.well-known/acme-challenge/", ex -> {
            String token = ex.getRequestURI().getPath().replace("/.well-known/acme-challenge/", "");
            String ka = Acme.CHALLENGES.get(token);
            byte[] b = (ka == null ? "" : ka).getBytes("UTF-8");
            ex.sendResponseHeaders(ka == null ? 404 : 200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
            System.out.println("  [http-01] " + token + " -> " + (ka == null ? 404 : 200));
        });
        http.start();

        File dir = Files.createTempDirectory("acme").toFile();
        Acme acme = new Acme("https://localhost:14000/dir", dir, Acme.clientFactory(roots), m -> System.out.println("  " + m));

        System.out.println("== Terbit pertama");
        acme.obtain(domain, "admin@example.com");
        check(acme.certValidFor(domain, 1), "certValidFor setelah terbit");
        check(!acme.certValidFor("lain.example.com", 1), "domain lain tidak boleh cocok");

        System.out.println("== Perpanjang (akun lama dipakai ulang)");
        long t0 = acme.certFile().lastModified();
        Thread.sleep(1100);
        acme.obtain(domain, null);
        check(acme.certFile().lastModified() > t0, "sertifikat diperbarui");

        System.out.println("== Server TLS dari sertifikat tersimpan");
        SSLServerSocket ss = (SSLServerSocket) acme.serverFactory().createServerSocket(0);
        int port = ss.getLocalPort();
        Thread srv = new Thread(() -> {
            try (SSLSocket s = (SSLSocket) ss.accept()) {
                s.startHandshake();
                s.getOutputStream().write("ok".getBytes());
            } catch (Exception e) { e.printStackTrace(); }
        });
        srv.start();
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] x, String a) { }
            public void checkServerTrusted(X509Certificate[] x, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (SSLSocket cs = (SSLSocket) c.getSocketFactory().createSocket("localhost", port)) {
            cs.startHandshake();
            X509Certificate leaf = (X509Certificate) cs.getSession().getPeerCertificates()[0];
            System.out.println("  handshake " + cs.getSession().getProtocol() + " " + cs.getSession().getCipherSuite()
                    + " · sertifikat: " + leaf.getSubjectAlternativeNames());
            check(leaf.getSubjectAlternativeNames().toString().contains(domain), "SAN sertifikat = domain");
            byte[] b = new byte[2];
            check(cs.getInputStream().read(b) == 2 && "ok".equals(new String(b)), "data lewat TLS");
        }
        srv.join();
        http.stop(0);
        System.out.println("SEMUA UJI ACME LULUS ✓");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("GAGAL: " + what);
        System.out.println("  ✓ " + what);
    }
}
