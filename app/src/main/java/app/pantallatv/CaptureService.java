package app.pantallatv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Captura la pantalla, la codifica en H.264 y la entrega al servidor local. */
public class CaptureService extends Service implements StreamServer.Listener {
    static final String EXTRA_CODIGO = "codigo";
    static final String EXTRA_DATOS = "datos";
    static final String ACCION_PARAR = "app.pantallatv.PARAR";

    static volatile boolean activo;
    static volatile boolean televisor;
    static volatile int puerto = 8080;
    static volatile String error;

    private static final String TAG = "PantallaTV";
    private static final String CANAL = "emision";
    private static final int LADO_LARGO = 1280;
    private static final int BITRATE = 5_000_000;

    private HandlerThread hilo;
    private Handler manejador;
    private MediaProjection proyeccion;
    private VirtualDisplay pantalla;
    private StreamServer servidor;
    private PowerManager.WakeLock bloqueo;

    private final Object cerrojoCodec = new Object();
    private MediaCodec codec;
    private Surface superficie;
    private Thread drenaje;
    private volatile boolean drenando;
    private int anchoActual;
    private int altoActual;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACCION_PARAR.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (activo) return START_NOT_STICKY;

        ponerEnPrimerPlano();
        int codigo = intent.getIntExtra(EXTRA_CODIGO, 0);
        Intent datos = intent.getParcelableExtra(EXTRA_DATOS);

        hilo = new HandlerThread("captura");
        hilo.start();
        manejador = new Handler(hilo.getLooper());
        error = null;
        activo = true;

        // La sesión debe crearse con el servicio ya en primer plano.
        MediaProjectionManager gestor = getSystemService(MediaProjectionManager.class);
        proyeccion = gestor.getMediaProjection(codigo, datos);
        if (proyeccion == null) {
            fallar("Android no concedió la captura de pantalla.");
            return START_NOT_STICKY;
        }
        proyeccion.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }

            @Override
            public void onCapturedContentResize(int ancho, int alto) {
                manejador.post(() -> ajustar(ancho, alto));
            }
        }, manejador);

        manejador.post(this::arrancar);
        return START_NOT_STICKY;
    }

    private void arrancar() {
        try {
            servidor = new StreamServer(leerPagina(), this);
            servidor.iniciar(8080);
            puerto = servidor.puerto();
            PowerManager pm = getSystemService(PowerManager.class);
            bloqueo = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "PantallaTV:emision");
            bloqueo.acquire(6 * 60 * 60 * 1000L);
            int[] t = tamanoPantalla();
            ajustar(t[0], t[1]);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo iniciar", e);
            fallar("No se pudo iniciar: " + e.getMessage());
        }
    }

    private byte[] leerPagina() throws IOException {
        try (InputStream in = getAssets().open("receptor.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] t = new byte[8192];
            int n;
            while ((n = in.read(t)) > 0) b.write(t, 0, n);
            return b.toByteArray();
        }
    }

    private int[] tamanoPantalla() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        return new int[] {m.widthPixels, m.heightPixels};
    }

    @Override
    public void onConfigurationChanged(Configuration nueva) {
        super.onConfigurationChanged(nueva);
        // Desde Android 14 avisa onCapturedContentResize, también al compartir una sola app.
        if (manejador != null && Build.VERSION.SDK_INT < 34) {
            manejador.post(() -> {
                int[] t = tamanoPantalla();
                ajustar(t[0], t[1]);
            });
        }
    }

    private static int[] escalar(int ancho, int alto, int ladoLargo) {
        float f = Math.min(1f, ladoLargo / (float) Math.max(ancho, alto));
        return new int[] {Math.max(16, Math.round(ancho * f) / 8 * 8), Math.max(16, Math.round(alto * f) / 8 * 8)};
    }

    /** Crea el codificador para el tamaño indicado. Se ejecuta en el hilo de captura. */
    private void ajustar(int ancho, int alto) {
        if (!activo || ancho <= 0 || alto <= 0) return;
        for (int lado : new int[] {LADO_LARGO, 960, 640}) {
            int[] t = escalar(ancho, alto, lado);
            if (lado == LADO_LARGO && t[0] == anchoActual && t[1] == altoActual) return;
            try {
                cambiarCodec(t[0], t[1]);
                return;
            } catch (Exception e) {
                Log.w(TAG, "El codificador rechazó " + t[0] + "x" + t[1], e);
            }
        }
        fallar("El móvil no pudo iniciar el codificador de vídeo.");
    }

    private void cambiarCodec(int ancho, int alto) throws IOException {
        MediaCodec nuevo = crearCodificador(ancho, alto);
        Surface nuevaSuperficie = nuevo.createInputSurface();
        nuevo.start();

        pararDrenaje();
        MediaCodec anterior;
        Surface superficieAnterior = superficie;
        synchronized (cerrojoCodec) {
            anterior = codec;
            codec = nuevo;
            superficie = nuevaSuperficie;
        }
        anchoActual = ancho;
        altoActual = alto;
        arrancarDrenaje(nuevo, ancho, alto);

        int dpi = getResources().getDisplayMetrics().densityDpi;
        if (pantalla == null) {
            // Android 14 solo permite crear una pantalla virtual por sesión: después se redimensiona.
            pantalla = proyeccion.createVirtualDisplay("PantallaTV", ancho, alto, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, nuevaSuperficie, null, manejador);
        } else {
            pantalla.resize(ancho, alto, dpi);
            pantalla.setSurface(nuevaSuperficie);
        }
        liberar(anterior, superficieAnterior);
    }

    private MediaCodec crearCodificador(int ancho, int alto) throws IOException {
        MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            c.configure(formato(ancho, alto, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (RuntimeException e) {
            // Algunos móviles rechazan las opciones de baja latencia: se reintenta con lo básico.
            c.reset();
            try {
                c.configure(formato(ancho, alto, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException e2) {
                c.release();
                throw e2;
            }
        }
        return c;
    }

    private static MediaFormat formato(int ancho, int alto, boolean completo) {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ancho, alto);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        if (completo) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            f.setFloat("max-fps-to-encoder", 30f);
            f.setInteger(MediaFormat.KEY_LATENCY, 1);
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        return f;
    }

    private void arrancarDrenaje(MediaCodec c, int ancho, int alto) {
        drenando = true;
        drenaje = new Thread(() -> drenar(c, ancho, alto), "drenaje");
        drenaje.start();
    }

    private void pararDrenaje() {
        drenando = false;
        Thread t = drenaje;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignorado) {
                Thread.currentThread().interrupt();
            }
        }
        drenaje = null;
    }

    private void drenar(MediaCodec c, int ancho, int alto) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (drenando) {
                int i = c.dequeueOutputBuffer(info, 50_000);
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    enviarConfiguracion(c.getOutputFormat(), ancho, alto);
                } else if (i >= 0) {
                    ByteBuffer b = c.getOutputBuffer(i);
                    if (b != null && info.size > 0) {
                        byte[] datos = new byte[info.size];
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        b.get(datos);
                        StreamServer s = servidor;
                        if (s != null) {
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                s.alConfigurar(datos, ancho, alto);
                            } else {
                                s.alRecibirImagen(datos, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                            }
                        }
                    }
                    c.releaseOutputBuffer(i, false);
                }
            }
        } catch (IllegalStateException e) {
            if (drenando) Log.w(TAG, "El codificador se detuvo", e);
        }
    }

    private void enviarConfiguracion(MediaFormat f, int ancho, int alto) {
        ByteBuffer csd0 = f.getByteBuffer("csd-0");
        ByteBuffer csd1 = f.getByteBuffer("csd-1");
        StreamServer s = servidor;
        if (csd0 == null || csd1 == null || s == null) return;
        byte[] todo = new byte[csd0.remaining() + csd1.remaining()];
        csd0.duplicate().get(todo, 0, csd0.remaining());
        csd1.duplicate().get(todo, csd0.remaining(), csd1.remaining());
        s.alConfigurar(todo, ancho, alto);
    }

    private static void liberar(MediaCodec c, Surface s) {
        if (c != null) {
            try {
                c.stop();
            } catch (RuntimeException ignorado) {
            }
            c.release();
        }
        if (s != null) s.release();
    }

    @Override
    public void pedirImagenClave() {
        synchronized (cerrojoCodec) {
            if (codec == null) return;
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                codec.setParameters(b);
            } catch (IllegalStateException ignorado) {
            }
        }
    }

    @Override
    public void televisorConectado(boolean conectado) {
        televisor = conectado;
    }

    private void fallar(String mensaje) {
        error = mensaje;
        stopSelf();
    }

    private void ponerEnPrimerPlano() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Emisión de pantalla", NotificationManager.IMPORTANCE_LOW));
        Intent parar = new Intent(this, CaptureService.class).setAction(ACCION_PARAR);
        PendingIntent pi = PendingIntent.getService(this, 1, parar, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent abrir = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CANAL)
                .setContentTitle("PantallaTV está compartiendo tu pantalla")
                .setContentText("Toca Parar para terminar.")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(abrir)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Parar", pi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        activo = false;
        televisor = false;
        pararDrenaje();
        if (pantalla != null) pantalla.release();
        pantalla = null;
        synchronized (cerrojoCodec) {
            liberar(codec, superficie);
            codec = null;
            superficie = null;
        }
        if (proyeccion != null) proyeccion.stop();
        if (servidor != null) servidor.detener();
        servidor = null;
        if (bloqueo != null && bloqueo.isHeld()) bloqueo.release();
        if (hilo != null) hilo.quitSafely();
        super.onDestroy();
    }
}
