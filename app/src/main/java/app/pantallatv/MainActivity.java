package app.pantallatv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pantalla principal: indica qué dirección abrir en el televisor y arranca la emisión. */
public class MainActivity extends Activity {
    private static final int PEDIR_CAPTURA = 10;
    private static final int FONDO = Color.rgb(17, 21, 31);
    private static final int TEXTO = Color.rgb(241, 243, 248);
    private static final int SUAVE = Color.rgb(169, 178, 199);
    private static final int VERDE = Color.rgb(85, 209, 135);

    private final Handler refresco = new Handler(Looper.getMainLooper());
    private TextView estado;
    private TextView direccion;
    private TextView pasos;
    private Button principal;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            actualizar();
            refresco.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);
        int d = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics());

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(d, d, d, d);

        raiz.addView(texto("PantallaTV", 30, TEXTO, true));
        estado = texto("", 17, SUAVE, false);
        estado.setPadding(0, d / 4, 0, d);
        raiz.addView(estado);

        raiz.addView(texto("En el navegador Silk del Fire TV, escribe:", 16, SUAVE, false));
        direccion = texto("", 30, TEXTO, true);
        direccion.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        direccion.setPadding(0, d / 3, 0, d);
        direccion.setTextIsSelectable(true);
        raiz.addView(direccion);

        principal = new Button(this);
        principal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        principal.setAllCaps(false);
        principal.setOnClickListener(v -> pulsarPrincipal());
        raiz.addView(principal, ancho());

        Button hotspot = new Button(this);
        hotspot.setText("Abrir ajustes del hotspot");
        hotspot.setAllCaps(false);
        hotspot.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        raiz.addView(hotspot, ancho());

        pasos = texto("1. Activa el hotspot del móvil y conecta a él el Fire TV. También sirve la misma Wi-Fi.\n\n"
                + "2. Pulsa Compartir pantalla y acepta el aviso de Android.\n\n"
                + "3. Abre Silk en el Fire TV y escribe la dirección.\n\n"
                + "4. Pulsa OK en el mando para ver los datos y usar la pantalla completa.\n\n"
                + "El sonido se sigue oyendo en el móvil. Las apps protegidas pueden verse en negro.", 16, SUAVE, false);
        pasos.setPadding(0, d, 0, 0);
        raiz.addView(pasos);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(FONDO);
        scroll.addView(raiz);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    private LinearLayout.LayoutParams ancho() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView texto(String s, int sp, int color, boolean fuerte) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (fuerte) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresco.post(tick);
    }

    @Override
    protected void onPause() {
        refresco.removeCallbacks(tick);
        super.onPause();
    }

    private void pulsarPrincipal() {
        if (CaptureService.activo) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACCION_PARAR));
            refresco.postDelayed(this::actualizar, 300);
        } else {
            MediaProjectionManager g = getSystemService(MediaProjectionManager.class);
            startActivityForResult(g.createScreenCaptureIntent(), PEDIR_CAPTURA);
        }
    }

    @Override
    protected void onActivityResult(int peticion, int resultado, Intent datos) {
        super.onActivityResult(peticion, resultado, datos);
        if (peticion == PEDIR_CAPTURA && resultado == RESULT_OK && datos != null) {
            Intent i = new Intent(this, CaptureService.class)
                    .putExtra(CaptureService.EXTRA_CODIGO, resultado)
                    .putExtra(CaptureService.EXTRA_DATOS, datos);
            startForegroundService(i);
        }
    }

    private void actualizar() {
        boolean activo = CaptureService.activo;
        principal.setText(activo ? "Dejar de compartir" : "Compartir pantalla");
        List<String> ips = direcciones();
        if (ips.isEmpty()) {
            direccion.setText("Sin red local");
        } else {
            StringBuilder b = new StringBuilder();
            for (String ip : ips) {
                if (b.length() > 0) b.append('\n');
                b.append("http://").append(ip).append(':').append(CaptureService.puerto);
            }
            direccion.setText(b.toString());
        }
        if (CaptureService.error != null) {
            estado.setText(CaptureService.error);
            estado.setTextColor(Color.rgb(255, 122, 112));
        } else if (activo && CaptureService.televisor) {
            estado.setText("Televisor conectado");
            estado.setTextColor(VERDE);
        } else if (activo) {
            estado.setText("Compartiendo. Esperando al televisor…");
            estado.setTextColor(TEXTO);
        } else if (ips.isEmpty()) {
            estado.setText("Activa el hotspot o conéctate a una Wi-Fi.");
            estado.setTextColor(SUAVE);
        } else {
            estado.setText("Listo para compartir.");
            estado.setTextColor(SUAVE);
        }
    }

    /** Direcciones de la red local. Omite las redes móviles, que el televisor no puede alcanzar. */
    private static List<String> direcciones() {
        List<String> lista = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                String nombre = n.getName();
                if (nombre.startsWith("rmnet") || nombre.startsWith("ccmni") || nombre.startsWith("pdp")
                        || nombre.startsWith("tun") || nombre.startsWith("v4-") || nombre.startsWith("dummy")
                        || nombre.startsWith("ims") || nombre.startsWith("clat") || nombre.startsWith("sit")) {
                    continue;
                }
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) lista.add(a.getHostAddress());
                }
            }
        } catch (Exception ignorado) {
        }
        return lista;
    }
}
