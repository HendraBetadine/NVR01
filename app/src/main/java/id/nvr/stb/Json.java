package id.nvr.stb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parser JSON mini (tanpa dependensi) untuk respons ACME. Objek -> Map, array -> List. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String s) {
        Json p = new Json(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("JSON: sisa data di posisi " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String s) { return (Map<String, Object>) parse(s); }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    /** Escape string untuk ditulis ke JSON. */
    public static String q(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("JSON: data habis");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalArgumentException("JSON: karakter tak terduga '" + c + "' di " + i);
        String num = s.substring(st, i);
        try { return Long.parseLong(num); } catch (NumberFormatException e) { return Double.parseDouble(num); }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i++) != ':') throw new IllegalArgumentException("JSON: ':' diharapkan di " + (i - 1));
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("JSON: ',' atau '}' diharapkan di " + (i - 1));
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("JSON: ',' atau ']' diharapkan di " + (i - 1));
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("JSON: string diharapkan di " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: sb.append(e);
            }
        }
    }
}
