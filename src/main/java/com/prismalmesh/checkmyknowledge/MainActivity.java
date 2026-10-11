package com.prismalmesh.checkmyknowledge;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * FullStatus Student · Propiedad intelectual de PRISMAL MESH. Todos los derechos reservados.
 *
 * Única Activity de la app. Es un "cascarón" nativo que:
 *   1. Muestra en un WebView la app real del alumno (assets/index.html).
 *   2. Hace cumplir las reglas anti-trampa que HTML/JS no puede hacer solo:
 *      bloquear capturas, detectar que la app pierde el foco e ignorar el
 *      botón Atrás.
 *
 * REGLA CENTRAL: mientras el examen está activo (examenActivo == true),
 * cualquier cosa sospechosa llama a anularExamen(), que CIERRA la app por
 * completo. Al reabrirla, el alumno empieza de cero. No hay "pausa".
 *
 * COMUNICACIÓN CON index.html: el JS llama a los métodos de la clase Puente
 * (expuesta como window.Android). iniciarExamen() y terminarExamen() marcan
 * el inicio y fin del periodo vigilado. Si cambias sus nombres, cambia
 * también las llamadas en assets/index.html.
 *
 * HILOS: los callbacks de Puente llegan en un hilo del WebView, NO en el
 * principal. Por eso tocan estado vía runOnUiThread().
 *
 * CAMBIOS (2/oct/2026): index.html se carga con origen https (loadDataWithBaseURL).
 * El origen ya NO es "https://www.youtube.com" (la página se hacía pasar por
 * YouTube y el reproductor respondía error 152-4). Ahora es "https://" + el
 * package de la app, que es como YouTube pide que se identifique una app que
 * embebe sus videos. Ver origenBase().
 *
 * CAMBIO (10/oct/2026): archivo restaurado completo y sin contenido duplicado
 * (el build de GitHub Actions fallaba con "class, interface, enum, or record
 * expected" por llaves desbalanceadas). Sin cambios de comportamiento.
 *
 * CAMBIO (11/oct/2026): se ELIMINÓ por completo la defensa del sensor de
 * proximidad y su modo de depuración (DEPURAR_SENSOR, Toast, Handler): la
 * investigación se abandonó porque en pruebas reales nunca anuló el examen.
 * Las demás reglas anti-trampa no cambian.
 */
public class MainActivity extends Activity {

    private WebView webView;

    // ------------------------------------------------------------------
    // ORIGEN "https" PARA LA PÁGINA (necesario para los videos de YouTube)
    // ------------------------------------------------------------------
    // Una página file:// no tiene origen ni Referer válidos, y YouTube rechaza
    // reproducir videos embebidos así (error de configuración 153). Por eso
    // index.html se lee de assets y se carga con loadDataWithBaseURL, que le da
    // a la página un origen https; el iframe de YouTube envía entonces un
    // Referer válido.
    //
    // El origen debe identificar A NUESTRA APP, no a YouTube: con
    // "https://www.youtube.com" el reproductor respondía error 152-4. El formato
    // recomendado por YouTube para apps es "https://<applicationId>".
    // Si algún día vuelve a fallar, esta función es lo único que hay que tocar
    // (p. ej. devolver "https://localhost/").
    private String origenBase() {
        return "https://" + getPackageName();
    }

    /** Lee un archivo de assets/ completo como texto UTF-8. */
    private String leerAsset(String nombre) throws IOException {
        try (InputStream in = getAssets().open(nombre);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /**
     * true solo entre iniciarExamen() y terminarExamen() (o hasta que se anule).
     * Es volatile porque se lee desde varios hilos (WebView y principal).
     */
    private volatile boolean examenActivo = false;

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // FLAG_SECURE: Android bloquea capturas y grabación de pantalla, y la
        // app aparece en negro en la vista de Recientes.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        // Evita que la pantalla se apague sola a mitad del examen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ajustes = webView.getSettings();
        ajustes.setJavaScriptEnabled(true);   // index.html es JS; necesario
        ajustes.setAllowFileAccess(false);    // cerrado a propósito...
        ajustes.setAllowContentAccess(false);
        // El reproductor embebido de YouTube usa almacenamiento DOM; sin esto puede
        // negarse a iniciar. No da acceso a archivos ni a otras páginas.
        ajustes.setDomStorageEnabled(true);
        // ...file:///android_asset/ NO depende de setAllowFileAccess, por eso
        // el loadUrl() de respaldo de abajo sigue funcionando.

        // Sin menú de copiar/pegar por pulsación larga.
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);

        // El alumno no puede navegar a ningún otro sitio desde la app.
        // OJO: esto NO afecta al iframe de YouTube que index.html embebe como
        // contexto de una pregunta: un iframe no pasa por este método.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });

        // Expone la clase Puente al JS como window.Android.
        webView.addJavascriptInterface(new Puente(), "Android");
        // Se carga con origen https propio (ver origenBase()). Si por algo no se pudo
        // leer el asset, se cae al método anterior para que la app al menos abra
        // (sin videos).
        try {
            webView.loadDataWithBaseURL(origenBase(), leerAsset("index.html"), "text/html", "UTF-8", null);
        } catch (IOException e) {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    /**
     * Métodos que index.html puede llamar como Android.iniciarExamen(), etc.
     * Todos llegan en un hilo del WebView, así que el estado se toca dentro de
     * runOnUiThread (hilo principal).
     */
    public class Puente {

        /** El alumno pulsó "Comenzar examen": comienza la vigilancia. */
        @JavascriptInterface
        public void iniciarExamen() {
            runOnUiThread(() -> examenActivo = true);
        }

        /** El alumno terminó el examen: termina la vigilancia (para poder mostrar el resultado). */
        @JavascriptInterface
        public void terminarExamen() {
            runOnUiThread(() -> examenActivo = false);
        }

        /** Botón "Salir" de la pantalla final: cierra la app y la quita de Recientes. */
        @JavascriptInterface
        public void salir() { runOnUiThread(() -> finishAndRemoveTask()); }
    }

    /**
     * Pierde el foco (notificación desplegada, ventana flotante, panel de
     * ajustes rápidos, etc.) durante el examen: se anula.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && examenActivo) anularExamen();
    }

    /** Va a Inicio, Recientes o cambia de app durante el examen: se anula. */
    @Override
    protected void onPause() {
        super.onPause();
        if (examenActivo) anularExamen();
    }

    /** Durante el examen el botón Atrás no hace nada; fuera de él se comporta normal. */
    @Override
    public void onBackPressed() {
        if (!examenActivo) super.onBackPressed();
    }

    /**
     * Anula el examen cerrando la app por completo (y quitándola de Recientes).
     * No guarda nada: al abrirla de nuevo se empieza de cero. Cualquier regla
     * nueva anti-trampa debería terminar llamando a este método.
     */
    private void anularExamen() {
        examenActivo = false;
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
