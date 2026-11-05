package app.pantallatv;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Empaqueta H.264 (Annex B) en MP4 fragmentado, con un fragmento por imagen.
 * La línea de tiempo es continua (cada imagen dura 1/30 s) para que el navegador
 * nunca encuentre huecos, aunque la pantalla esté quieta y lleguen menos imágenes.
 */
final class Fmp4Muxer {
    private static final int TIMESCALE = 90000;
    private static final int DURACION = 3000;
    private static final int FLAGS_CLAVE = 0x02000000;
    private static final int FLAGS_NORMAL = 0x01010000;

    private int secuencia = 1;
    private long tiempo = 0;

    /** Divide un bloque Annex B en unidades NAL, sin los códigos de inicio. */
    static List<byte[]> dividirNals(byte[] d) {
        List<byte[]> nals = new ArrayList<>();
        int inicio = -1;
        int i = 0;
        while (i + 3 <= d.length) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                if (inicio >= 0) nals.add(recortar(d, inicio, i));
                inicio = i + 3;
                i += 3;
            } else {
                i++;
            }
        }
        if (inicio >= 0 && inicio < d.length) nals.add(recortar(d, inicio, d.length));
        return nals;
    }

    private static byte[] recortar(byte[] d, int desde, int hasta) {
        // Quita los ceros finales: pertenecen al siguiente código de inicio de 4 bytes.
        while (hasta > desde && d[hasta - 1] == 0) hasta--;
        byte[] r = new byte[hasta - desde];
        System.arraycopy(d, desde, r, 0, r.length);
        return r;
    }

    static String cadenaCodec(byte[] sps) {
        return String.format("avc1.%02X%02X%02X", sps[1] & 255, sps[2] & 255, sps[3] & 255);
    }

    byte[] inicio(byte[] sps, byte[] pps, int ancho, int alto) {
        byte[] ftyp = caja("ftyp", ascii("isom"), entero(512), ascii("isom"), ascii("iso5"), ascii("avc1"), ascii("mp41"));
        byte[] moov = caja("moov", mvhd(), trak(sps, pps, ancho, alto), mvex());
        return unir(ftyp, moov);
    }

    /** Devuelve un fragmento con una imagen, o null si el bloque no contiene imagen. */
    byte[] fragmento(byte[] annexB, boolean clave) {
        ByteArrayOutputStream datos = new ByteArrayOutputStream(annexB.length + 16);
        for (byte[] nal : dividirNals(annexB)) {
            if (nal.length == 0) continue;
            int tipo = nal[0] & 0x1f;
            if (tipo == 7 || tipo == 8 || tipo == 9) continue;
            datos.write(entero(nal.length), 0, 4);
            datos.write(nal, 0, nal.length);
        }
        if (datos.size() == 0) return null;
        byte[] muestra = datos.toByteArray();
        int flags = clave ? FLAGS_CLAVE : FLAGS_NORMAL;

        byte[] moof = moof(muestra.length, flags, 0);
        moof = moof(muestra.length, flags, moof.length + 8);
        secuencia++;
        tiempo += DURACION;
        return unir(moof, caja("mdat", muestra));
    }

    private byte[] moof(int tamano, int flags, int desplazamiento) {
        byte[] mfhd = caja("mfhd", entero(0), entero(secuencia));
        byte[] tfhd = caja("tfhd", entero(0x020000), entero(1));
        byte[] tfdt = caja("tfdt", entero(0x01000000), largo(tiempo));
        byte[] trun = caja("trun", entero(0x000701), entero(1), entero(desplazamiento),
                entero(DURACION), entero(tamano), entero(flags));
        return caja("moof", mfhd, caja("traf", tfhd, tfdt, trun));
    }

    private static byte[] mvhd() {
        ByteBuffer b = ByteBuffer.allocate(100);
        b.putInt(0).putInt(0).putInt(0).putInt(TIMESCALE).putInt(0);
        b.putInt(0x00010000).putShort((short) 0x0100).putShort((short) 0);
        b.putInt(0).putInt(0);
        ponerMatriz(b);
        for (int i = 0; i < 6; i++) b.putInt(0);
        b.putInt(2);
        return caja("mvhd", b.array());
    }

    private static byte[] trak(byte[] sps, byte[] pps, int ancho, int alto) {
        ByteBuffer t = ByteBuffer.allocate(84);
        t.putInt(0x00000007).putInt(0).putInt(0).putInt(1).putInt(0).putInt(0);
        t.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        ponerMatriz(t);
        t.putInt(ancho << 16).putInt(alto << 16);
        byte[] tkhd = caja("tkhd", t.array());

        ByteBuffer m = ByteBuffer.allocate(24);
        m.putInt(0).putInt(0).putInt(0).putInt(TIMESCALE).putInt(0).putShort((short) 0x55c4).putShort((short) 0);
        byte[] mdhd = caja("mdhd", m.array());

        byte[] hdlr = caja("hdlr", entero(0), entero(0), ascii("vide"), entero(0), entero(0), entero(0),
                ascii("VideoHandler"), new byte[] {0});
        byte[] vmhd = caja("vmhd", entero(1), new byte[8]);
        byte[] dinf = caja("dinf", caja("dref", entero(0), entero(1), caja("url ", entero(1))));
        byte[] stbl = caja("stbl", caja("stsd", entero(0), entero(1), avc1(sps, pps, ancho, alto)),
                caja("stts", entero(0), entero(0)), caja("stsc", entero(0), entero(0)),
                caja("stsz", entero(0), entero(0), entero(0)), caja("stco", entero(0), entero(0)));
        return caja("trak", tkhd, caja("mdia", mdhd, hdlr, caja("minf", vmhd, dinf, stbl)));
    }

    private static byte[] avc1(byte[] sps, byte[] pps, int ancho, int alto) {
        ByteBuffer c = ByteBuffer.allocate(11 + sps.length + pps.length);
        c.put((byte) 1).put(sps[1]).put(sps[2]).put(sps[3]).put((byte) 0xff).put((byte) 0xe1);
        c.putShort((short) sps.length).put(sps);
        c.put((byte) 1).putShort((short) pps.length).put(pps);
        byte[] avcC = caja("avcC", c.array());

        ByteBuffer e = ByteBuffer.allocate(78);
        e.put(new byte[6]).putShort((short) 1).put(new byte[16]);
        e.putShort((short) ancho).putShort((short) alto);
        e.putInt(0x00480000).putInt(0x00480000).putInt(0).putShort((short) 1);
        e.put(new byte[32]).putShort((short) 0x0018).putShort((short) -1);
        return caja("avc1", e.array(), avcC);
    }

    private static byte[] mvex() {
        return caja("mvex", caja("trex", entero(0), entero(1), entero(1), entero(0), entero(0), entero(0)));
    }

    private static void ponerMatriz(ByteBuffer b) {
        b.putInt(0x00010000).putInt(0).putInt(0).putInt(0).putInt(0x00010000).putInt(0)
                .putInt(0).putInt(0).putInt(0x40000000);
    }

    private static byte[] caja(String tipo, byte[]... partes) {
        int total = 8;
        for (byte[] p : partes) total += p.length;
        ByteBuffer b = ByteBuffer.allocate(total);
        b.putInt(total).put(ascii(tipo));
        for (byte[] p : partes) b.put(p);
        return b.array();
    }

    private static byte[] unir(byte[]... partes) {
        int total = 0;
        for (byte[] p : partes) total += p.length;
        ByteBuffer b = ByteBuffer.allocate(total);
        for (byte[] p : partes) b.put(p);
        return b.array();
    }

    private static byte[] ascii(String s) {
        byte[] r = new byte[s.length()];
        for (int i = 0; i < r.length; i++) r[i] = (byte) s.charAt(i);
        return r;
    }

    private static byte[] entero(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    private static byte[] largo(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }
}
