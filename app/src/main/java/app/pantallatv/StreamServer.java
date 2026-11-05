package app.pantallatv;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Servidor local mínimo: entrega la página del televisor y le envía el vídeo por WebSocket.
 * Si la red se atasca, descarta imágenes y pide una imagen clave para recuperar el directo.
 */
final class StreamServer {
    interface Listener {
        void pedirImagenClave();
        void televisorConectado(boolean conectado);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int COLA_MAXIMA = 8;

    private final byte[] pagina;
    private final Listener listener;
    private ServerSocket servidor;
    private volatile boolean activo;
    private volatile int puerto;

    private byte[] sps;
    private byte[] pps;
    private int ancho;
    private int alto;
    private Cliente cliente;

    StreamServer(byte[] pagina, Listener listener) {
        this.pagina = pagina;
        this.listener = listener;
    }

    int puerto() {
        return puerto;
    }

    void iniciar(int primerPuerto) throws IOException {
        IOException ultimo = null;
        for (int p = primerPuerto; p < primerPuerto + 10; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                servidor = s;
                puerto = p;
                ultimo = null;
                break;
            } catch (IOException e) {
                ultimo = e;
            }
        }
        if (ultimo != null) throw ultimo;
        activo = true;
        Thread t = new Thread(this::aceptar, "servidor");
        t.setDaemon(true);
        t.start();
    }

    void detener() {
        activo = false;
        try {
            if (servidor != null) servidor.close();
        } catch (IOException ignorado) {
        }
        synchronized (this) {
            if (cliente != null) cliente.cerrar();
            cliente = null;
        }
    }

    /** Recibe la configuración del codificador (SPS y PPS) al empezar o al girar el móvil. */
    synchronized void alConfigurar(byte[] annexB, int ancho, int alto) {
        byte[] nuevoSps = null;
        byte[] nuevoPps = null;
        List<byte[]> nals = Fmp4Muxer.dividirNals(annexB);
        for (byte[] n : nals) {
            if (n.length == 0) continue;
            int tipo = n[0] & 0x1f;
            if (tipo == 7) nuevoSps = n;
            if (tipo == 8) nuevoPps = n;
        }
        if (nuevoSps == null || nuevoPps == null || nuevoSps.length < 4) return;
        boolean igual = Arrays.equals(nuevoSps, sps) && Arrays.equals(nuevoPps, pps)
                && this.ancho == ancho && this.alto == alto;
        sps = nuevoSps;
        pps = nuevoPps;
        this.ancho = ancho;
        this.alto = alto;
        if (!igual && cliente != null) {
            cliente.iniciado = false;
            cliente.esperaClave = true;
        }
    }

    synchronized void alRecibirImagen(byte[] annexB, boolean clave) {
        Cliente c = cliente;
        if (c == null || sps == null) return;
        if (c.cola.size() > COLA_MAXIMA) {
            // La cola vaciada podía contener la cabecera del vídeo: se reenvía con la imagen clave.
            c.cola.clear();
            c.iniciado = false;
            c.esperaClave = true;
            listener.pedirImagenClave();
        }
        if (c.esperaClave) {
            if (!clave) return;
            c.esperaClave = false;
        }
        if (!c.iniciado) {
            String meta = "{\"codec\":\"" + Fmp4Muxer.cadenaCodec(sps) + "\",\"ancho\":" + ancho
                    + ",\"alto\":" + alto + "}";
            c.cola.add(marcoWs(0x81, meta.getBytes(StandardCharsets.UTF_8)));
            c.cola.add(marcoWs(0x82, c.muxer.inicio(sps, pps, ancho, alto)));
            c.iniciado = true;
        }
        byte[] f = c.muxer.fragmento(annexB, clave);
        if (f != null) c.cola.add(marcoWs(0x82, f));
    }

    private void aceptar() {
        while (activo) {
            try {
                Socket s = servidor.accept();
                s.setTcpNoDelay(true);
                Thread t = new Thread(() -> atender(s), "conexion");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!activo) return;
            }
        }
    }

    private void atender(Socket s) {
        try {
            InputStream in = s.getInputStream();
            BufferedReader lector = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            String peticion = lector.readLine();
            if (peticion == null) {
                s.close();
                return;
            }
            String clave = null;
            boolean websocket = false;
            String linea;
            while ((linea = lector.readLine()) != null && !linea.isEmpty()) {
                String m = linea.toLowerCase();
                if (m.startsWith("sec-websocket-key:")) clave = linea.substring(linea.indexOf(':') + 1).trim();
                if (m.startsWith("upgrade:") && m.contains("websocket")) websocket = true;
            }
            String[] partes = peticion.split(" ");
            String ruta = partes.length > 1 ? partes[1] : "/";
            if (websocket && clave != null) {
                abrirWebSocket(s, in, clave);
            } else if (ruta.equals("/") || ruta.startsWith("/?")) {
                responder(s, "200 OK", "text/html; charset=utf-8", pagina);
            } else {
                responder(s, "404 Not Found", "text/plain", new byte[0]);
            }
        } catch (Exception e) {
            try {
                s.close();
            } catch (IOException ignorado) {
            }
        }
    }

    private static void responder(Socket s, String estado, String tipo, byte[] cuerpo) throws IOException {
        OutputStream out = s.getOutputStream();
        String cabecera = "HTTP/1.1 " + estado + "\r\nContent-Type: " + tipo + "\r\nContent-Length: " + cuerpo.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(cabecera.getBytes(StandardCharsets.ISO_8859_1));
        out.write(cuerpo);
        out.flush();
        s.close();
    }

    private void abrirWebSocket(Socket s, InputStream in, String clave) throws Exception {
        String acepta = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((clave + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acepta + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        Cliente nuevo = new Cliente(s);
        synchronized (this) {
            if (cliente != null) cliente.cerrar();
            cliente = nuevo;
        }
        listener.televisorConectado(true);
        listener.pedirImagenClave();
        Thread escritor = new Thread(nuevo::escribir, "envio");
        escritor.setDaemon(true);
        escritor.start();

        // El televisor no envía datos útiles; leer solo sirve para detectar que se ha ido.
        byte[] basura = new byte[256];
        try {
            while (in.read(basura) >= 0) {
                // Se ignora.
            }
        } catch (IOException ignorado) {
        }
        nuevo.cerrar();
        boolean eraActual;
        synchronized (this) {
            eraActual = cliente == nuevo;
            if (eraActual) cliente = null;
        }
        if (eraActual) listener.televisorConectado(false);
    }

    private static byte[] marcoWs(int primerByte, byte[] datos) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(datos.length + 10);
        b.write(primerByte);
        int n = datos.length;
        if (n < 126) {
            b.write(n);
        } else if (n < 65536) {
            b.write(126);
            b.write(n >>> 8);
            b.write(n);
        } else {
            b.write(127);
            b.write(0);
            b.write(0);
            b.write(0);
            b.write(0);
            b.write(n >>> 24);
            b.write(n >>> 16);
            b.write(n >>> 8);
            b.write(n);
        }
        b.write(datos, 0, n);
        return b.toByteArray();
    }

    private static final class Cliente {
        final Socket socket;
        final LinkedBlockingDeque<byte[]> cola = new LinkedBlockingDeque<>();
        final Fmp4Muxer muxer = new Fmp4Muxer();
        boolean iniciado;
        boolean esperaClave = true;
        volatile boolean cerrado;

        Cliente(Socket socket) {
            this.socket = socket;
        }

        void escribir() {
            try {
                OutputStream out = socket.getOutputStream();
                while (!cerrado) {
                    byte[] m = cola.take();
                    out.write(m);
                    out.flush();
                }
            } catch (IOException | InterruptedException e) {
                cerrar();
            }
        }

        void cerrar() {
            cerrado = true;
            cola.offer(new byte[0]);
            try {
                socket.close();
            } catch (IOException ignorado) {
            }
        }
    }
}
