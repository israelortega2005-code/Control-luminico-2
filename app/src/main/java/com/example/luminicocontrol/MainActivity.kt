package com.example.luminicocontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.log10

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private val esp32Ip = "http://192.168.4.1"
    private var ultimoComandoMs: Long = 0
    private var ultimoEnvioWheelMs: Long = 0
    private var esMinutoActivo = true

    // Variables para seguimiento de la configuración del usuario
    private var modoPrincipalActual = "color"
    private var subModoActual = "FF0000"
    private var colorRgbActual = "FF0000"
    private var sensibilidadActual = "estandar"

    // Bandera para evitar bucles infinitos en listeners de switches cuando se cambian por código
    private var ignoreSwitchListeners = false

    // Bandera de protección contra doble clic al conectar Wi-Fi
    private var conectandoWifi = false

    // Control de estado de conexión para activar la vibración doble Bluetooth únicamente al cambiar a EN LÍNEA
    @Volatile
    private var estaEnLinea = false

    // Control del ciclo de vida del hilo de monitoreo en tiempo real
    @Volatile
    private var monitoreando = true

    // Control de concurrencia HTTP para el servidor monohilo del ESP32
    private val httpLock = Any()
    private var sincronizandoOffline = false

    // Ejecutor en segundo plano para escrituras SQLite y gestor de Toast sin cola
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private var toastActual: Toast? = null

    // Handler y Runnable para debounce de edición de tiempo PIR (500 ms)
    private val handlerPirDebounce = Handler(Looper.getMainLooper())
    private var pirDebounceRunnable: Runnable? = null

    // Control de red WiFi
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Instancia de la base de datos para almacenamiento temporal y configuración
    private lateinit var dbHelper: AdminSQLiteOpenHelper

    // Lanzador de permisos dinámicos en tiempo de ejecución (Android 13+ / 14+)
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val nearbyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.NEARBY_WIFI_DEVICES] ?: false
        } else true

        if (fineLocationGranted || nearbyGranted) {
            iniciarConexionInteligentemente()
        } else {
            mostrarToastRapido("Se requieren permisos de red cercana para conectar al ESP32")
            iniciarConexionInteligentemente()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // Configuración de barra de estado clara para contraste con fondo #F2F6FA
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        // Inicializar Base de Datos Offline y Persistencia
        dbHelper = AdminSQLiteOpenHelper(this)

        val btnBack = findViewById<TextView>(R.id.btnBack)
        val tvSubtituloConexion = findViewById<TextView>(R.id.tvSubtituloConexion)
        val tvNivelAudio = findViewById<TextView>(R.id.tvNivelAudio)
        val pbAudioLevel = findViewById<ProgressBar>(R.id.pbAudioLevel)
        val tvEstadoPir = findViewById<TextView>(R.id.tvEstadoPir)

        // Botones de Ayuda Interactiva ⓘ
        val btnInfoMonitoreo = findViewById<TextView>(R.id.btnInfoMonitoreo)
        val btnInfoMic = findViewById<TextView>(R.id.btnInfoMic)
        val btnInfoPir = findViewById<TextView>(R.id.btnInfoPir)

        // Control de Brillo
        val cardBrillo = findViewById<MaterialCardView>(R.id.cardBrillo)
        val sbBrilloLed = findViewById<SeekBar>(R.id.sbBrilloLed)
        val tvPorcentajeBrillo = findViewById<TextView>(R.id.tvPorcentajeBrillo)

        // Indicadores de Telemetría en Tiempo Real
        val tvMetricPir = findViewById<TextView>(R.id.tvMetricPir)
        val tvMetricAudio = findViewById<TextView>(R.id.tvMetricAudio)
        val tvMetricLdr = findViewById<TextView>(R.id.tvMetricLdr)
        val tvMetricEstilo = findViewById<TextView>(R.id.tvMetricEstilo)

        // Barras de Espectro Frecuencial Audio
        val pbGraves = findViewById<ProgressBar>(R.id.pbGraves)
        val pbMedios = findViewById<ProgressBar>(R.id.pbMedios)
        val pbAgudos = findViewById<ProgressBar>(R.id.pbAgudos)
        val tvGravesPct = findViewById<TextView>(R.id.tvGravesPct)
        val tvMediosPct = findViewById<TextView>(R.id.tvMediosPct)
        val tvAgudosPct = findViewById<TextView>(R.id.tvAgudosPct)

        val switchLuces = findViewById<SwitchMaterial>(R.id.btnApagar)
        val switchPir = findViewById<SwitchMaterial>(R.id.switchPir)
        val switchMic = findViewById<SwitchMaterial>(R.id.switchMic)
        val switchSeguridadPermanente = findViewById<SwitchMaterial>(R.id.switchSeguridadPermanente)
        val layoutConfigPir = findViewById<LinearLayout>(R.id.layoutConfigPir)

        val cardHeroDevice = findViewById<MaterialCardView>(R.id.cardHeroDevice)

        // --- Diálogos de Ayuda Interactivos ⓘ ---
        btnInfoMonitoreo.setOnClickListener {
            vibrarToque(it)
            if (isFinishing || isDestroyed) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Monitoreo en Tiempo Real")
                .setMessage("Muestra los datos en vivo detectados por el sistema:\n\n• PIR: Avisa si alguien pasa frente al sensor de movimiento.\n• Audio: Mide qué tan fuerte suena la música o tu voz en la habitación.\n• Luz LDR: Detecta si la pieza está oscura o iluminada.\n• Estilo: Muestra qué efecto de luces está activo en este momento.")
                .setPositiveButton("Entendido", null)
                .show()
        }

        btnInfoMic.setOnClickListener {
            vibrarToque(it)
            if (isFinishing || isDestroyed) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Sensibilidad de Audio (KY-037)")
                .setMessage("Hace que las luces bailen al ritmo de la música o tu voz. Usa 'Suave' para música tranquila, 'Estándar' para uso normal o 'Fiesta' si el volumen está muy alto.")
                .setPositiveButton("Entendido", null)
                .show()
        }

        btnInfoPir.setOnClickListener {
            vibrarToque(it)
            if (isFinishing || isDestroyed) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Alarma de Movimiento PIR")
                .setMessage("Enciende las luces automáticamente cuando detecta que alguien entra o se mueve, y las mantiene prendidas durante el tiempo que elijas abajo.")
                .setPositiveButton("Entendido", null)
                .show()
        }

        // --- Interruptores colores dinámico ---
        val estadosSwitch = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val coloresTrack = intArrayOf(0xFF00B4D8.toInt(), 0xFFCBD5E1.toInt())
        val coloresThumb = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        val trackList = ColorStateList(estadosSwitch, coloresTrack)
        val thumbList = ColorStateList(estadosSwitch, coloresThumb)

        switchLuces.trackTintList = trackList
        switchLuces.thumbTintList = thumbList
        switchPir.trackTintList = trackList
        switchPir.thumbTintList = thumbList
        switchMic.trackTintList = trackList
        switchMic.thumbTintList = thumbList
        switchSeguridadPermanente.trackTintList = trackList
        switchSeguridadPermanente.thumbTintList = thumbList

        // --- MODOS Y CONTENEDORES PARA ACORDEÓN ---
        val scrollModosLuz = findViewById<HorizontalScrollView>(R.id.scrollModosLuz)
        val contenedorSubPaneles = findViewById<FrameLayout>(R.id.contenedorSubPaneles)

        val cardColor = findViewById<MaterialCardView>(R.id.btnModoColor)
        val cardSeguridad = findViewById<MaterialCardView>(R.id.btnModoSeguridad)
        val cardRitmo = findViewById<MaterialCardView>(R.id.btnModoRitmo)
        val cardMulticolor = findViewById<MaterialCardView>(R.id.btnModoGaming)
        val cardNeon = findViewById<MaterialCardView>(R.id.btnModoFreeze)
        val cardAmbiente = findViewById<MaterialCardView>(R.id.btnModoWave) // Renombrado de Onda a Ambiente

        // --- SUB-PANELES ---
        val panelSubModosRitmo = findViewById<LinearLayout>(R.id.panelSubModosRitmo)
        val panelSubModosColor = findViewById<HorizontalScrollView>(R.id.panelSubModosColor)
        val panelSubModosNeon = findViewById<LinearLayout>(R.id.panelSubModosNeon)
        val panelSubModosAmbiente = findViewById<HorizontalScrollView>(R.id.panelSubModosAmbiente)
        val panelSubModosSeguridad = findViewById<HorizontalScrollView>(R.id.panelSubModosSeguridad)

        // Sub-modos Ritmo
        val cardRitmoAuto = findViewById<MaterialCardView>(R.id.cardRitmoAuto)
        val cardRitmoVu = findViewById<MaterialCardView>(R.id.cardRitmoVu)
        val cardRitmoEspectro = findViewById<MaterialCardView>(R.id.cardRitmoEspectro)
        val cardRitmoBeat = findViewById<MaterialCardView>(R.id.cardRitmoBeat)
        val cardRitmoFuego = findViewById<MaterialCardView>(R.id.cardRitmoFuego)
        val cardRitmoDestellos = findViewById<MaterialCardView>(R.id.cardRitmoDestellos)
        val cardRitmoPulso = findViewById<MaterialCardView>(R.id.cardRitmoPulso)

        // Sub-modos Neón y Velocidad
        val cardNeonClasico = findViewById<MaterialCardView>(R.id.cardNeonClasico)
        val cardNeonArcoiris = findViewById<MaterialCardView>(R.id.cardNeonArcoiris)
        val cardNeonCyberpunk = findViewById<MaterialCardView>(R.id.cardNeonCyberpunk)
        val cardNeonRespiracion = findViewById<MaterialCardView>(R.id.cardNeonRespiracion)
        val cardNeonPlasma = findViewById<MaterialCardView>(R.id.cardNeonPlasma)
        val sbVelocidadNeon = findViewById<SeekBar>(R.id.sbVelocidadNeon)
        val tvVelocidadNeonVal = findViewById<TextView>(R.id.tvVelocidadNeonVal)

        // Sub-modos Ambiente (7 Escenas)
        val cardAmbAuto = findViewById<MaterialCardView>(R.id.cardAmbAuto)
        val cardAmbTormenta = findViewById<MaterialCardView>(R.id.cardAmbTormenta)
        val cardAmbFuego = findViewById<MaterialCardView>(R.id.cardAmbFuego)
        val cardAmbOceano = findViewById<MaterialCardView>(R.id.cardAmbOceano)
        val cardAmbNaturaleza = findViewById<MaterialCardView>(R.id.cardAmbNaturaleza)
        val cardAmbAurora = findViewById<MaterialCardView>(R.id.cardAmbAurora)
        val cardAmbAtardecer = findViewById<MaterialCardView>(R.id.cardAmbAtardecer)

        // Sub-modos Color (13 Colores Puros Saturated + Botón "Más Colores")
        val btnColorRojo = findViewById<MaterialCardView>(R.id.btnColorRojo)
        val btnColorNaranja = findViewById<MaterialCardView>(R.id.btnColorNaranja)
        val btnColorAmarillo = findViewById<MaterialCardView>(R.id.btnColorAmarillo)
        val btnColorLima = findViewById<MaterialCardView>(R.id.btnColorLima)
        val btnColorVerde = findViewById<MaterialCardView>(R.id.btnColorVerde)
        val btnColorTurquesa = findViewById<MaterialCardView>(R.id.btnColorTurquesa)
        val btnColorCian = findViewById<MaterialCardView>(R.id.btnColorCian)
        val btnColorAzul = findViewById<MaterialCardView>(R.id.btnColorAzul)
        val btnColorMorado = findViewById<MaterialCardView>(R.id.btnColorMorado)
        val btnColorRosa = findViewById<MaterialCardView>(R.id.btnColorRosa)
        val btnColorCoral = findViewById<MaterialCardView>(R.id.btnColorCoral)
        val btnColorCalido = findViewById<MaterialCardView>(R.id.btnColorCalido)
        val btnColorBlanco = findViewById<MaterialCardView>(R.id.btnColorBlanco)
        val btnColorMasColores = findViewById<MaterialCardView>(R.id.btnColorMasColores)

        // Sub-modos Seguridad
        val cardSegBaliza = findViewById<MaterialCardView>(R.id.cardSegBaliza)
        val cardSegEstrobo = findViewById<MaterialCardView>(R.id.cardSegEstrobo)
        val cardSegAlerta = findViewById<MaterialCardView>(R.id.cardSegAlerta)
        val cardSegTv = findViewById<MaterialCardView>(R.id.cardSegTv)

        // Temporizador Seguridad
        val etSeguridadTiempo = findViewById<EditText>(R.id.etSeguridadTiempo)
        val cardTiempoSeg = findViewById<MaterialCardView>(R.id.cardTiempoSeg)
        val cardTiempoMin = findViewById<MaterialCardView>(R.id.cardTiempoMin)
        val tvTiempoSeg = findViewById<TextView>(R.id.tvTiempoSeg)
        val tvTiempoMin = findViewById<TextView>(R.id.tvTiempoMin)

        // Referencias a Sensibilidad
        val scrollSensibilidadAudio = findViewById<HorizontalScrollView>(R.id.scrollSensibilidadAudio)
        val cardSensEstandar = findViewById<MaterialCardView>(R.id.cardSensEstandar)
        val tvSensEstandar = findViewById<TextView>(R.id.tvSensEstandar)
        val cardSensSuave = findViewById<MaterialCardView>(R.id.cardSensSuave)
        val tvSensSuave = findViewById<TextView>(R.id.tvSensSuave)
        val cardSensFiesta = findViewById<MaterialCardView>(R.id.cardSensFiesta)
        val tvSensFiesta = findViewById<TextView>(R.id.tvSensFiesta)

        val todasLasTarjetas = listOf(cardColor, cardRitmo, cardMulticolor, cardNeon, cardAmbiente, cardSeguridad)
        val todasLasTarjetasRitmo = listOf(cardRitmoAuto, cardRitmoVu, cardRitmoEspectro, cardRitmoBeat, cardRitmoFuego, cardRitmoDestellos, cardRitmoPulso)
        val todasLasTarjetasNeon = listOf(cardNeonClasico, cardNeonArcoiris, cardNeonCyberpunk, cardNeonRespiracion, cardNeonPlasma)
        val todasLasTarjetasAmbiente = listOf(cardAmbAuto, cardAmbTormenta, cardAmbFuego, cardAmbOceano, cardAmbNaturaleza, cardAmbAurora, cardAmbAtardecer)
        val todasLasTarjetasSeguridad = listOf(cardSegBaliza, cardSegEstrobo, cardSegAlerta, cardSegTv)
        val todosLosColores = listOf(
            btnColorRojo, btnColorNaranja, btnColorAmarillo, btnColorLima,
            btnColorVerde, btnColorTurquesa, btnColorCian, btnColorAzul,
            btnColorMorado, btnColorRosa, btnColorCoral, btnColorCalido, btnColorBlanco
        )

        // --- Funciones Auxiliares Acordeón Desplegable ---
        fun actualizarVisibilidadIluminacion(encendido: Boolean) {
            val visibilidad = if (encendido) View.VISIBLE else View.GONE
            scrollModosLuz.visibility = visibilidad
            contenedorSubPaneles.visibility = visibilidad
            cardBrillo.visibility = visibilidad
        }

        fun actualizarVisibilidadAudio(encendido: Boolean) {
            scrollSensibilidadAudio.visibility = if (encendido) View.VISIBLE else View.GONE
        }

        fun resaltarTarjetaActiva(tarjetaSeleccionada: MaterialCardView) {
            val densidad = resources.displayMetrics.density
            for (tarjeta in todasLasTarjetas) {
                if (tarjeta == tarjetaSeleccionada) {
                    tarjeta.strokeColor = 0xFF00B4D8.toInt()
                    tarjeta.strokeWidth = (3 * densidad).toInt()
                } else {
                    tarjeta.strokeColor = 0xFFFFFFFF.toInt()
                    tarjeta.strokeWidth = (1.5f * densidad).toInt()
                }
            }
        }

        fun seleccionarSubModoGeneral(tarjetas: List<MaterialCardView>, cardSeleccionada: MaterialCardView, tieneSubtitulo: Boolean) {
            val densidad = resources.displayMetrics.density
            for (card in tarjetas) {
                val layoutInterno = if (tieneSubtitulo) card.getChildAt(0) as? LinearLayout else null
                val tvTitulo = if (tieneSubtitulo) layoutInterno?.getChildAt(0) as? TextView else card.getChildAt(0) as? TextView
                val tvSubtitulo = if (tieneSubtitulo) layoutInterno?.getChildAt(1) as? TextView else null

                if (tvTitulo == null) continue

                if (card == cardSeleccionada) {
                    card.setCardBackgroundColor(0xFF00B4D8.toInt())
                    card.strokeWidth = 0
                    tvTitulo.setTextColor(0xFFFFFFFF.toInt())
                    tvSubtitulo?.setTextColor(0xFFE0F2FE.toInt())
                } else {
                    card.setCardBackgroundColor(0xFFFFFFFF.toInt())
                    card.strokeColor = 0xFFCBD5E1.toInt()
                    card.strokeWidth = (1 * densidad).toInt()
                    tvTitulo.setTextColor(0xFF0F172A.toInt())
                    tvSubtitulo?.setTextColor(0xFF64748B.toInt())
                }
            }
        }

        fun seleccionarSelectorColor(cardSeleccionada: MaterialCardView?) {
            val densidad = resources.displayMetrics.density
            for (card in todosLosColores) {
                if (card == cardSeleccionada) {
                    card.strokeWidth = (3 * densidad).toInt()
                    card.strokeColor = 0xFFFFFFFF.toInt()
                } else {
                    card.strokeWidth = (1 * densidad).toInt()
                    card.strokeColor = 0x88FFFFFF.toInt()
                }
            }
            btnColorBlanco.strokeColor = 0xFFE2E8F0.toInt()
            if (cardSeleccionada == btnColorBlanco) btnColorBlanco.strokeWidth = (3 * densidad).toInt()
        }

        fun actualizarSensibilidadVisual(cardActiva: MaterialCardView) {
            val tarjetas = listOf(cardSensEstandar, cardSensSuave, cardSensFiesta)
            val textos = listOf(tvSensEstandar, tvSensSuave, tvSensFiesta)
            val densidad = resources.displayMetrics.density

            for (i in tarjetas.indices) {
                if (tarjetas[i] == cardActiva) {
                    tarjetas[i].setCardBackgroundColor(0xFF00B4D8.toInt())
                    tarjetas[i].strokeWidth = 0
                    textos[i].setTextColor(0xFFFFFFFF.toInt())
                } else {
                    tarjetas[i].setCardBackgroundColor(0xFFF8FAFC.toInt())
                    tarjetas[i].strokeColor = 0xFFE2E8F0.toInt()
                    tarjetas[i].strokeWidth = (1 * densidad).toInt()
                    textos[i].setTextColor(0xFF334155.toInt())
                }
            }
        }

        fun encenderSwitchLucesSilencioso() {
            ignoreSwitchListeners = true
            try {
                switchLuces.isChecked = true
                actualizarVisibilidadIluminacion(true)
            } finally {
                ignoreSwitchListeners = false
            }
        }

        fun guardarEstadoPersistente() {
            val config = ConfigEstado(
                modoPrincipal = modoPrincipalActual,
                subModo = subModoActual,
                colorRgb = colorRgbActual,
                sensibilidad = sensibilidadActual,
                lucesEncendidas = switchLuces.isChecked,
                micActivo = switchMic.isChecked,
                pirActivo = switchPir.isChecked,
                seguridadMaster = false,
                seguridadPermanente = switchSeguridadPermanente.isChecked,
                tiempoPirValor = etSeguridadTiempo.text.toString().ifEmpty { "5" },
                tiempoPirEsMinuto = esMinutoActivo,
                brilloLed = sbBrilloLed.progress.coerceIn(10, 255)
            )
            if (!dbExecutor.isShutdown) {
                try {
                    dbExecutor.execute {
                        synchronized(dbHelper) {
                            dbHelper.guardarEstadoConfiguracion(config)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        fun enviarTiempoPirConfigurado() {
            if (!permitirEnvioSeguridad()) return
            val valorInt = etSeguridadTiempo.text.toString().toIntOrNull() ?: 5
            val comandos = mutableListOf<Pair<String, String>>()

            val esFijo = if (switchSeguridadPermanente.isChecked) 1 else 0
            comandos.add(Pair("/api/actuador?pir_fijo=$esFijo", "PIR Fijo: $esFijo"))

            if (switchSeguridadPermanente.isChecked) {
                comandos.add(Pair("/api/actuador?pir_min=60", "Tiempo PIR: Fijo"))
                comandos.add(Pair("/api/actuador?seg_tipo=0", "Modo Alerta Permanente"))
            } else {
                if (esMinutoActivo) {
                    val minVal = valorInt.coerceAtLeast(1)
                    comandos.add(Pair("/api/actuador?pir_min=$minVal", "Tiempo PIR: $minVal min"))
                } else {
                    val secVal = valorInt.coerceAtLeast(1)
                    comandos.add(Pair("/api/actuador?pir_sec=$secVal", "Tiempo PIR: $secVal seg"))
                }
            }
            enviarComandosSecuenciales(comandos, tvSubtituloConexion, cardHeroDevice)
            guardarEstadoPersistente()
        }

        fun actualizarUnidadTiempoVisual(esMinuto: Boolean) {
            esMinutoActivo = esMinuto
            val densidad = resources.displayMetrics.density
            if (esMinuto) {
                cardTiempoMin.setCardBackgroundColor(0xFF00B4D8.toInt())
                cardTiempoMin.strokeWidth = 0
                tvTiempoMin.setTextColor(0xFFFFFFFF.toInt())
                cardTiempoSeg.setCardBackgroundColor(0xFFFFFFFF.toInt())
                cardTiempoSeg.strokeColor = 0xFFCBD5E1.toInt()
                cardTiempoSeg.strokeWidth = (1 * densidad).toInt()
                tvTiempoSeg.setTextColor(0xFF64748B.toInt())
            } else {
                cardTiempoSeg.setCardBackgroundColor(0xFF00B4D8.toInt())
                cardTiempoSeg.strokeWidth = 0
                tvTiempoSeg.setTextColor(0xFFFFFFFF.toInt())
                cardTiempoMin.setCardBackgroundColor(0xFFFFFFFF.toInt())
                cardTiempoMin.strokeColor = 0xFFCBD5E1.toInt()
                cardTiempoMin.strokeWidth = (1 * densidad).toInt()
                tvTiempoMin.setTextColor(0xFF64748B.toInt())
            }
        }

        fun actualizarUnidadTiempo(esMinuto: Boolean) {
            actualizarUnidadTiempoVisual(esMinuto)
            enviarTiempoPirConfigurado()
        }

        // --- RESTAURAR ESTADO GUARDADO DESDE SQLITE ---
        val configGuardada = try {
            dbHelper.obtenerEstadoConfiguracion()
        } catch (_: Exception) {
            null
        }
        if (configGuardada != null) {
            modoPrincipalActual = configGuardada.modoPrincipal
            subModoActual = configGuardada.subModo
            colorRgbActual = configGuardada.colorRgb
            sensibilidadActual = configGuardada.sensibilidad
            esMinutoActivo = configGuardada.tiempoPirEsMinuto

            // Restaurar switches silenciosamente (sin enviar comandos HTTP)
            ignoreSwitchListeners = true
            try {
                switchLuces.isChecked = configGuardada.lucesEncendidas
                switchMic.isChecked = configGuardada.micActivo
                switchPir.isChecked = configGuardada.pirActivo
                switchSeguridadPermanente.isChecked = configGuardada.seguridadPermanente
            } finally {
                ignoreSwitchListeners = false
            }

            // Restaurar visibilidad acordeón para Iluminación y Audio
            actualizarVisibilidadIluminacion(configGuardada.lucesEncendidas)
            actualizarVisibilidadAudio(configGuardada.micActivo)

            // Restaurar Brillo
            val brilloProgreso = configGuardada.brilloLed.coerceIn(10, 255)
            sbBrilloLed.progress = brilloProgreso
            val porcentajeBrillo = ((brilloProgreso.toFloat() / 255f) * 100f).toInt()
            tvPorcentajeBrillo.text = "$porcentajeBrillo%"

            // Restaurar texto, indicador y despliegue del PIR (acordeón)
            if (configGuardada.pirActivo) {
                tvEstadoPir.text = "Sensor HC-SR501: Vigilancia activa (Monitoreando)"
                tvEstadoPir.setTextColor(0xFF0284C7.toInt())
                layoutConfigPir.visibility = View.VISIBLE
            } else {
                tvEstadoPir.text = "Sensor HC-SR501: Sin movimiento detectado"
                tvEstadoPir.setTextColor(0xFF64748B.toInt())
                layoutConfigPir.visibility = View.GONE
            }

            // Restaurar Temporizador
            etSeguridadTiempo.setText(configGuardada.tiempoPirValor)
            etSeguridadTiempo.isEnabled = !configGuardada.seguridadPermanente
            cardTiempoMin.isEnabled = !configGuardada.seguridadPermanente
            cardTiempoSeg.isEnabled = !configGuardada.seguridadPermanente
            val alphaTimer = if (configGuardada.seguridadPermanente) 0.5f else 1.0f
            etSeguridadTiempo.alpha = alphaTimer
            cardTiempoMin.alpha = alphaTimer
            cardTiempoSeg.alpha = alphaTimer
            actualizarUnidadTiempoVisual(configGuardada.tiempoPirEsMinuto)

            // Restaurar Sensibilidad UI
            when (configGuardada.sensibilidad) {
                "suave" -> actualizarSensibilidadVisual(cardSensSuave)
                "fiesta" -> actualizarSensibilidadVisual(cardSensFiesta)
                else -> actualizarSensibilidadVisual(cardSensEstandar)
            }

            // Restaurar Modo Principal & Sub-panel UI
            panelSubModosColor.visibility = View.GONE
            panelSubModosRitmo.visibility = View.GONE
            panelSubModosNeon.visibility = View.GONE
            panelSubModosAmbiente.visibility = View.GONE
            panelSubModosSeguridad.visibility = View.GONE

            when (configGuardada.modoPrincipal) {
                "color" -> {
                    resaltarTarjetaActiva(cardColor)
                    panelSubModosColor.visibility = View.VISIBLE
                    val colorCardMap = mapOf(
                        "FF0000" to btnColorRojo,
                        "FF4500" to btnColorNaranja,
                        "FFD000" to btnColorAmarillo,
                        "76FF03" to btnColorLima,
                        "00E676" to btnColorVerde,
                        "00BFA5" to btnColorTurquesa,
                        "00E5FF" to btnColorCian,
                        "2979FF" to btnColorAzul,
                        "AA00FF" to btnColorMorado,
                        "FF007F" to btnColorRosa,
                        "FF1744" to btnColorCoral,
                        "FFAB40" to btnColorCalido,
                        "FFFFFF" to btnColorBlanco
                    )
                    colorCardMap[configGuardada.colorRgb]?.let { seleccionarSelectorColor(it) } ?: seleccionarSelectorColor(btnColorRojo)
                }
                "ritmo" -> {
                    resaltarTarjetaActiva(cardRitmo)
                    panelSubModosRitmo.visibility = View.VISIBLE
                    val ritmoCardMap = mapOf(
                        "auto" to cardRitmoAuto,
                        "0" to cardRitmoVu,
                        "1" to cardRitmoEspectro,
                        "3" to cardRitmoBeat,
                        "9" to cardRitmoFuego,
                        "4" to cardRitmoDestellos,
                        "6" to cardRitmoPulso
                    )
                    ritmoCardMap[configGuardada.subModo]?.let { seleccionarSubModoGeneral(todasLasTarjetasRitmo, it, true) } ?: seleccionarSubModoGeneral(todasLasTarjetasRitmo, cardRitmoAuto, true)
                }
                "neon" -> {
                    resaltarTarjetaActiva(cardNeon)
                    panelSubModosNeon.visibility = View.VISIBLE
                    val neonCardMap = mapOf(
                        "0" to cardNeonClasico,
                        "clasico" to cardNeonClasico,
                        "1" to cardNeonArcoiris,
                        "2" to cardNeonCyberpunk,
                        "3" to cardNeonRespiracion,
                        "4" to cardNeonPlasma
                    )
                    neonCardMap[configGuardada.subModo]?.let { seleccionarSubModoGeneral(todasLasTarjetasNeon, it, true) } ?: seleccionarSubModoGeneral(todasLasTarjetasNeon, cardNeonClasico, true)
                }
                "ambiente" -> {
                    resaltarTarjetaActiva(cardAmbiente)
                    panelSubModosAmbiente.visibility = View.VISIBLE
                    val ambCardMap = mapOf(
                        "0" to cardAmbAuto,
                        "1" to cardAmbTormenta,
                        "2" to cardAmbFuego,
                        "3" to cardAmbOceano,
                        "4" to cardAmbNaturaleza,
                        "5" to cardAmbAurora,
                        "6" to cardAmbAtardecer
                    )
                    ambCardMap[configGuardada.subModo]?.let { seleccionarSubModoGeneral(todasLasTarjetasAmbiente, it, true) } ?: seleccionarSubModoGeneral(todasLasTarjetasAmbiente, cardAmbAuto, true)
                }
                "multicolor" -> resaltarTarjetaActiva(cardMulticolor)
                "seguridad" -> {
                    resaltarTarjetaActiva(cardSeguridad)
                    panelSubModosSeguridad.visibility = View.VISIBLE
                    val segCardMap = mapOf(
                        "baliza" to cardSegBaliza,
                        "estrobo" to cardSegEstrobo,
                        "alerta" to cardSegAlerta,
                        "tv" to cardSegTv
                    )
                    segCardMap[configGuardada.subModo]?.let { seleccionarSubModoGeneral(todasLasTarjetasSeguridad, it, false) } ?: seleccionarSubModoGeneral(todasLasTarjetasSeguridad, cardSegBaliza, false)
                }
            }
        }

        // Actualizar contador visual de cola offline al iniciar
        actualizarContadorColaOffline()

        // Verificar permisos y conectar inteligentemente
        if (verificarYPedirPermisos()) {
            iniciarConexionInteligentemente()
        }

        // SeekBar Brillo Global (10 a 255 directos sin alterar el efecto activo)
        sbBrilloLed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val valorReal = progress.coerceAtLeast(10)
                val porcentaje = ((valorReal.toFloat() / 255f) * 100f).toInt()
                tvPorcentajeBrillo.text = "$porcentaje%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val valorReal = (seekBar?.progress ?: 255).coerceAtLeast(10)
                val porcentaje = ((valorReal.toFloat() / 255f) * 100f).toInt()
                enviarComandoEsp32("/api/actuador?brillo=$valorReal", "Brillo: $porcentaje%", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        })

        // SeekBar Velocidad Neón
        sbVelocidadNeon.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val velVal = (progress + 1).coerceIn(1, 10)
                tvVelocidadNeonVal.text = velVal.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val velVal = ((seekBar?.progress ?: 4) + 1).coerceIn(1, 10)
                enviarComandoEsp32("/api/actuador?neon_vel=$velVal", "Velocidad Neón: $velVal", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        })

        // Temporizador listeners & Debounce 500ms en etSeguridadTiempo
        cardTiempoMin.setOnClickListener {
            vibrarToque(it)
            actualizarUnidadTiempo(true)
        }
        cardTiempoSeg.setOnClickListener {
            vibrarToque(it)
            actualizarUnidadTiempo(false)
        }
        switchSeguridadPermanente.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreSwitchListeners) return@setOnCheckedChangeListener
            etSeguridadTiempo.isEnabled = !isChecked
            cardTiempoMin.isEnabled = !isChecked
            cardTiempoSeg.isEnabled = !isChecked
            val alpha = if (isChecked) 0.5f else 1.0f
            etSeguridadTiempo.alpha = alpha
            cardTiempoMin.alpha = alpha
            cardTiempoSeg.alpha = alpha
            enviarTiempoPirConfigurado()
            guardarEstadoPersistente()
        }

        etSeguridadTiempo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pirDebounceRunnable?.let { handlerPirDebounce.removeCallbacks(it) }
                pirDebounceRunnable = Runnable {
                    enviarTiempoPirConfigurado()
                }
                handlerPirDebounce.postDelayed(pirDebounceRunnable!!, 500)
            }
        })

        etSeguridadTiempo.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                enviarTiempoPirConfigurado()
                guardarEstadoPersistente()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            }
            false
        }

        cardHeroDevice.setOnClickListener {
            vibrarToque(it)
            if (permitirEnvioSeguridad()) {
                if (verificarYPedirPermisos()) {
                    iniciarConexionInteligentemente()
                }
            }
        }

        // --- MODOS PRINCIPALES ---
        cardColor.setOnClickListener {
            vibrarToque(it)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "color"
                subModoActual = "FF0000"
                colorRgbActual = "FF0000"
                resaltarTarjetaActiva(cardColor)
                panelSubModosRitmo.visibility = View.GONE
                panelSubModosNeon.visibility = View.GONE
                panelSubModosAmbiente.visibility = View.GONE
                panelSubModosSeguridad.visibility = View.GONE
                panelSubModosColor.visibility = View.VISIBLE
                encenderSwitchLucesSilencioso()
                seleccionarSelectorColor(btnColorRojo)
                enviarComandosSecuenciales(
                    listOf(
                        Pair("/api/actuador?r=255&g=0&b=0", "Color Fijo: Rojo"),
                        Pair("/api/actuador?modo=manual", "Modo Manual")
                    ),
                    tvSubtituloConexion, cardHeroDevice
                )
                guardarEstadoPersistente()
            }
        }

        cardRitmo.setOnClickListener {
            vibrarToque(it)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "ritmo"
                subModoActual = "auto"
                resaltarTarjetaActiva(cardRitmo)
                panelSubModosColor.visibility = View.GONE
                panelSubModosNeon.visibility = View.GONE
                panelSubModosAmbiente.visibility = View.GONE
                panelSubModosSeguridad.visibility = View.GONE
                panelSubModosRitmo.visibility = View.VISIBLE
                encenderSwitchLucesSilencioso()
                seleccionarSubModoGeneral(todasLasTarjetasRitmo, cardRitmoAuto, true)
                enviarComandoEsp32("/api/actuador?estilo=auto", "Modo Ritmo Audio-Reactivo", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        cardMulticolor.setOnClickListener {
            vibrarToque(it)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "multicolor"
                subModoActual = "1"
                resaltarTarjetaActiva(cardMulticolor)
                panelSubModosRitmo.visibility = View.GONE
                panelSubModosColor.visibility = View.GONE
                panelSubModosNeon.visibility = View.GONE
                panelSubModosAmbiente.visibility = View.GONE
                panelSubModosSeguridad.visibility = View.GONE
                encenderSwitchLucesSilencioso()
                enviarComandoEsp32("/api/actuador?modo=multicolor", "Modo Multicolor", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        cardNeon.setOnClickListener {
            vibrarToque(it)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "neon"
                subModoActual = "0"
                resaltarTarjetaActiva(cardNeon)
                panelSubModosRitmo.visibility = View.GONE
                panelSubModosColor.visibility = View.GONE
                panelSubModosAmbiente.visibility = View.GONE
                panelSubModosSeguridad.visibility = View.GONE
                panelSubModosNeon.visibility = View.VISIBLE
                encenderSwitchLucesSilencioso()
                seleccionarSubModoGeneral(todasLasTarjetasNeon, cardNeonClasico, true)
                enviarComandoEsp32("/api/actuador?neon_tipo=0", "Modo Neón Clásico", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        cardAmbiente.setOnClickListener {
            vibrarToque(it)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "ambiente"
                subModoActual = "0"
                resaltarTarjetaActiva(cardAmbiente)
                panelSubModosRitmo.visibility = View.GONE
                panelSubModosColor.visibility = View.GONE
                panelSubModosNeon.visibility = View.GONE
                panelSubModosSeguridad.visibility = View.GONE
                panelSubModosAmbiente.visibility = View.VISIBLE
                encenderSwitchLucesSilencioso()
                seleccionarSubModoGeneral(todasLasTarjetasAmbiente, cardAmbAuto, true)
                enviarComandoEsp32("/api/actuador?amb_tipo=0", "Modo Auto Ambiente", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        cardSeguridad.setOnClickListener {
            vibrarToque(it)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "seguridad"
                subModoActual = "baliza"
                resaltarTarjetaActiva(cardSeguridad)
                panelSubModosRitmo.visibility = View.GONE
                panelSubModosNeon.visibility = View.GONE
                panelSubModosColor.visibility = View.GONE
                panelSubModosAmbiente.visibility = View.GONE
                panelSubModosSeguridad.visibility = View.VISIBLE
                encenderSwitchLucesSilencioso()
                seleccionarSubModoGeneral(todasLasTarjetasSeguridad, cardSegBaliza, false)
                enviarComandoEsp32("/api/actuador?seg_tipo=0", "Seguridad: Baliza Policial", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        // --- EVENTOS CÍRCULOS DE COLORES (13 COLORES PUROS SATURADOS) ---
        fun seleccionarColorHex(hex: String, nombre: String, card: MaterialCardView, r: Int, g: Int, b: Int) {
            vibrarToque(card)
            if (permitirEnvioSeguridad()) {
                colorRgbActual = hex
                seleccionarSelectorColor(card)
                encenderSwitchLucesSilencioso()
                if (modoPrincipalActual == "neon") {
                    enviarComandoEsp32("/api/actuador?r=$r&g=$g&b=$b", "Color Neón: $nombre", tvSubtituloConexion, cardHeroDevice)
                } else {
                    modoPrincipalActual = "color"
                    subModoActual = hex
                    enviarComandosSecuenciales(
                        listOf(
                            Pair("/api/actuador?r=$r&g=$g&b=$b", "Color: $nombre"),
                            Pair("/api/actuador?modo=manual", "Modo Manual")
                        ),
                        tvSubtituloConexion, cardHeroDevice
                    )
                }
                guardarEstadoPersistente()
            }
        }

        btnColorRojo.setOnClickListener { seleccionarColorHex("FF0000", "Rojo Puro", btnColorRojo, 255, 0, 0) }
        btnColorNaranja.setOnClickListener { seleccionarColorHex("FF4500", "Naranja Puro", btnColorNaranja, 255, 69, 0) }
        btnColorAmarillo.setOnClickListener { seleccionarColorHex("FFD000", "Amarillo Puro", btnColorAmarillo, 255, 208, 0) }
        btnColorLima.setOnClickListener { seleccionarColorHex("76FF03", "Lima Puro", btnColorLima, 118, 255, 3) }
        btnColorVerde.setOnClickListener { seleccionarColorHex("00E676", "Verde Puro", btnColorVerde, 0, 230, 118) }
        btnColorTurquesa.setOnClickListener { seleccionarColorHex("00BFA5", "Turquesa Puro", btnColorTurquesa, 0, 191, 165) }
        btnColorCian.setOnClickListener { seleccionarColorHex("00E5FF", "Cian Puro", btnColorCian, 0, 229, 255) }
        btnColorAzul.setOnClickListener { seleccionarColorHex("2979FF", "Azul Puro", btnColorAzul, 41, 121, 255) }
        btnColorMorado.setOnClickListener { seleccionarColorHex("AA00FF", "Morado Puro", btnColorMorado, 170, 0, 255) }
        btnColorRosa.setOnClickListener { seleccionarColorHex("FF007F", "Rosa Puro", btnColorRosa, 255, 0, 127) }
        btnColorCoral.setOnClickListener { seleccionarColorHex("FF1744", "Coral Puro", btnColorCoral, 255, 23, 68) }
        btnColorCalido.setOnClickListener { seleccionarColorHex("FFAB40", "Cálido Puro", btnColorCalido, 255, 171, 64) }
        btnColorBlanco.setOnClickListener { seleccionarColorHex("FFFFFF", "Blanco Puro", btnColorBlanco, 255, 255, 255) }

        // 14ª Tarjeta: Botón "Más Colores" con Rueda Cromática HSV (AlertDialog)
        btnColorMasColores.setOnClickListener {
            vibrarToque(it)
            if (isFinishing || isDestroyed) return@setOnClickListener

            val densidad = resources.displayMetrics.density
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (12 * densidad).toInt(), 0, (12 * densidad).toInt())
            }

            val colorPreview = View(this).apply {
                val pSize = (45 * densidad).toInt()
                layoutParams = LinearLayout.LayoutParams(pSize, pSize).apply {
                    bottomMargin = (14 * densidad).toInt()
                }
                val (r, g, b) = obtenerRgbDesdeHex(colorRgbActual)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(r, g, b))
                    setStroke((2 * densidad).toInt(), 0xFFCBD5E1.toInt())
                }
                background = shape
            }

            val wheelView = ColorWheelView(this).apply {
                val wSize = (260 * densidad).toInt()
                layoutParams = LinearLayout.LayoutParams(wSize, wSize)
            }

            container.addView(colorPreview)
            container.addView(wheelView)

            val dialog = AlertDialog.Builder(this)
                .setTitle("Selector de Color Libre")
                .setView(container)
                .setPositiveButton("Listo") { d, _ ->
                    d.dismiss()
                    guardarEstadoPersistente()
                }
                .create()

            wheelView.onColorSelected = { r, g, b, hex ->
                colorRgbActual = hex
                (colorPreview.background as? GradientDrawable)?.setColor(Color.rgb(r, g, b))
                seleccionarSelectorColor(null) // Deselecciona botones fijos para indicar color personalizado

                val ahora = System.currentTimeMillis()
                if (ahora - ultimoEnvioWheelMs >= 120) {
                    ultimoEnvioWheelMs = ahora
                    encenderSwitchLucesSilencioso()
                    if (modoPrincipalActual == "neon") {
                        enviarComandoEsp32("/api/actuador?r=$r&g=$g&b=$b", "Color Libre Neón", tvSubtituloConexion, cardHeroDevice)
                    } else {
                        modoPrincipalActual = "color"
                        subModoActual = hex
                        enviarComandoEsp32("/api/actuador?r=$r&g=$g&b=$b", "Color Libre", tvSubtituloConexion, cardHeroDevice)
                    }
                }
            }

            dialog.show()
        }

        // --- EVENTOS SUB-MODOS RITMO ---
        fun seleccionarSubRitmo(estilo: String, nombre: String, card: MaterialCardView) {
            vibrarToque(card)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "ritmo"
                subModoActual = estilo
                seleccionarSubModoGeneral(todasLasTarjetasRitmo, card, true)
                encenderSwitchLucesSilencioso()
                enviarComandoEsp32("/api/actuador?estilo=$estilo", "Ritmo: $nombre", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        cardRitmoAuto.setOnClickListener { seleccionarSubRitmo("auto", "Auto-Rotación", cardRitmoAuto) }
        cardRitmoVu.setOnClickListener { seleccionarSubRitmo("0", "VU Meter", cardRitmoVu) }
        cardRitmoEspectro.setOnClickListener { seleccionarSubRitmo("1", "Espectro RGB", cardRitmoEspectro) }
        cardRitmoBeat.setOnClickListener { seleccionarSubRitmo("3", "Arcoíris Beat", cardRitmoBeat) }
        cardRitmoDestellos.setOnClickListener { seleccionarSubRitmo("4", "Destellos", cardRitmoDestellos) }
        cardRitmoPulso.setOnClickListener { seleccionarSubRitmo("6", "Pulso Bass", cardRitmoPulso) }
        cardRitmoFuego.setOnClickListener { seleccionarSubRitmo("9", "Fuego Rítmico", cardRitmoFuego) }

        // --- EVENTOS SUB-MODOS NEÓN ---
        fun seleccionarSubNeon(tipoInt: Int, sub: String, nombre: String, card: MaterialCardView) {
            vibrarToque(card)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "neon"
                subModoActual = sub
                seleccionarSubModoGeneral(todasLasTarjetasNeon, card, true)
                encenderSwitchLucesSilencioso()
                enviarComandoEsp32("/api/actuador?neon_tipo=$tipoInt", "Neón: $nombre", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        cardNeonClasico.setOnClickListener { seleccionarSubNeon(0, "0", "Clásico", cardNeonClasico) }
        cardNeonArcoiris.setOnClickListener { seleccionarSubNeon(1, "1", "Arcoíris", cardNeonArcoiris) }
        cardNeonCyberpunk.setOnClickListener { seleccionarSubNeon(2, "2", "Cyberpunk", cardNeonCyberpunk) }
        cardNeonRespiracion.setOnClickListener { seleccionarSubNeon(3, "3", "Respiración", cardNeonRespiracion) }
        cardNeonPlasma.setOnClickListener { seleccionarSubNeon(4, "4", "Plasma", cardNeonPlasma) }

        // --- EVENTOS SUB-MODOS AMBIENTE ---
        fun seleccionarSubAmbiente(tipoInt: Int, sub: String, nombre: String, card: MaterialCardView) {
            vibrarToque(card)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "ambiente"
                subModoActual = sub
                seleccionarSubModoGeneral(todasLasTarjetasAmbiente, card, true)
                encenderSwitchLucesSilencioso()
                enviarComandoEsp32("/api/actuador?amb_tipo=$tipoInt", "Ambiente: $nombre", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        cardAmbAuto.setOnClickListener { seleccionarSubAmbiente(0, "0", "Auto Ambiente", cardAmbAuto) }
        cardAmbTormenta.setOnClickListener { seleccionarSubAmbiente(1, "1", "Tormenta", cardAmbTormenta) }
        cardAmbFuego.setOnClickListener { seleccionarSubAmbiente(2, "2", "Fuego", cardAmbFuego) }
        cardAmbOceano.setOnClickListener { seleccionarSubAmbiente(3, "3", "Océano", cardAmbOceano) }
        cardAmbNaturaleza.setOnClickListener { seleccionarSubAmbiente(4, "4", "Naturaleza", cardAmbNaturaleza) }
        cardAmbAurora.setOnClickListener { seleccionarSubAmbiente(5, "5", "Aurora", cardAmbAurora) }
        cardAmbAtardecer.setOnClickListener { seleccionarSubAmbiente(6, "6", "Atardecer", cardAmbAtardecer) }

        // --- MODOS SEGURIDAD ---
        fun seleccionarSubSeguridad(tipoInt: Int, sub: String, nombre: String, card: MaterialCardView) {
            vibrarToque(card)
            if (permitirEnvioSeguridad()) {
                modoPrincipalActual = "seguridad"
                subModoActual = sub
                seleccionarSubModoGeneral(todasLasTarjetasSeguridad, card, false)
                enviarComandoEsp32("/api/actuador?seg_tipo=$tipoInt", "Seguridad: $nombre", tvSubtituloConexion, cardHeroDevice)
                guardarEstadoPersistente()
            }
        }

        cardSegBaliza.setOnClickListener { seleccionarSubSeguridad(0, "baliza", "Baliza Policial", cardSegBaliza) }
        cardSegEstrobo.setOnClickListener { seleccionarSubSeguridad(1, "estrobo", "Estrobo Cegador", cardSegEstrobo) }
        cardSegAlerta.setOnClickListener { seleccionarSubSeguridad(2, "alerta", "Alerta Silenciosa", cardSegAlerta) }
        cardSegTv.setOnClickListener { seleccionarSubSeguridad(3, "tv", "Simulación TV", cardSegTv) }

        // --- SENSIBILIDAD Y SWITCHES ---
        fun obtenerComandosReanudacionEfecto(): List<Pair<String, String>> {
            val (rBase, gBase, bBase) = obtenerRgbDesdeHex(colorRgbActual)
            return when (modoPrincipalActual) {
                "color" -> listOf(
                    Pair("/api/actuador?r=$rBase&g=$gBase&b=$bBase", "Color RGB"),
                    Pair("/api/actuador?modo=manual", "Modo Manual")
                )
                "ritmo" -> {
                    val estilo = if (subModoActual.isEmpty()) "auto" else subModoActual
                    listOf(Pair("/api/actuador?estilo=$estilo", "Ritmo Audio: $estilo"))
                }
                "neon" -> {
                    val tipoInt = when (subModoActual) {
                        "1" -> 1
                        "2" -> 2
                        "3" -> 3
                        "4" -> 4
                        else -> 0
                    }
                    listOf(Pair("/api/actuador?neon_tipo=$tipoInt", "Modo Neón"))
                }
                "ambiente" -> {
                    val tipoInt = subModoActual.toIntOrNull() ?: 0
                    listOf(Pair("/api/actuador?amb_tipo=$tipoInt", "Modo Ambiente"))
                }
                "multicolor" -> listOf(Pair("/api/actuador?modo=multicolor", "Modo Multicolor"))
                "seguridad" -> {
                    val tipoInt = when (subModoActual) {
                        "estrobo" -> 1
                        "alerta" -> 2
                        "tv" -> 3
                        else -> 0
                    }
                    listOf(Pair("/api/actuador?seg_tipo=$tipoInt", "Seguridad"))
                }
                else -> listOf(Pair("/api/actuador?modo=manual", "Tira LED Encendida"))
            }
        }

        cardSensEstandar.setOnClickListener {
            vibrarToque(it)
            sensibilidadActual = "estandar"
            actualizarSensibilidadVisual(cardSensEstandar)
            enviarComandoEsp32("/api/actuador?sens=35", "Sensibilidad: Estándar", tvSubtituloConexion, cardHeroDevice)
            guardarEstadoPersistente()
        }
        cardSensSuave.setOnClickListener {
            vibrarToque(it)
            sensibilidadActual = "suave"
            actualizarSensibilidadVisual(cardSensSuave)
            enviarComandoEsp32("/api/actuador?sens=15", "Sensibilidad: Suave", tvSubtituloConexion, cardHeroDevice)
            guardarEstadoPersistente()
        }
        cardSensFiesta.setOnClickListener {
            vibrarToque(it)
            sensibilidadActual = "fiesta"
            actualizarSensibilidadVisual(cardSensFiesta)
            enviarComandoEsp32("/api/actuador?sens=85", "Sensibilidad: Fiesta", tvSubtituloConexion, cardHeroDevice)
            guardarEstadoPersistente()
        }

        switchLuces.setOnCheckedChangeListener { _, isChecked ->
            actualizarVisibilidadIluminacion(isChecked)
            if (ignoreSwitchListeners) return@setOnCheckedChangeListener
            if (isChecked) {
                val comandosReanudacion = obtenerComandosReanudacionEfecto()
                enviarComandosSecuenciales(comandosReanudacion, tvSubtituloConexion, cardHeroDevice)
            } else {
                enviarComandosSecuenciales(
                    listOf(
                        Pair("/api/actuador?r=0&g=0&b=0", "Apagando RGB"),
                        Pair("/api/actuador?modo=manual", "Tira LED Apagada")
                    ),
                    tvSubtituloConexion, cardHeroDevice
                )
            }
            guardarEstadoPersistente()
        }

        switchMic.setOnCheckedChangeListener { _, isChecked ->
            actualizarVisibilidadAudio(isChecked)
            if (ignoreSwitchListeners) return@setOnCheckedChangeListener
            if (isChecked) enviarComandoEsp32("/api/actuador?mic_on=1", "Micrófono Activado", tvSubtituloConexion, cardHeroDevice)
            else enviarComandoEsp32("/api/actuador?mic_on=0", "Micrófono Desactivado", tvSubtituloConexion, cardHeroDevice)
            guardarEstadoPersistente()
        }

        switchPir.setOnCheckedChangeListener { _, isChecked ->
            layoutConfigPir.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (ignoreSwitchListeners) return@setOnCheckedChangeListener
            if (isChecked) {
                tvEstadoPir.text = "Sensor HC-SR501: Vigilancia activa (Monitoreando)"
                tvEstadoPir.setTextColor(0xFF0284C7.toInt())
                val esFijo = if (switchSeguridadPermanente.isChecked) 1 else 0
                val valorInt = etSeguridadTiempo.text.toString().toIntOrNull() ?: 5
                val unidadParam = if (esMinutoActivo) "pir_min=$valorInt" else "pir_sec=$valorInt"
                enviarComandosSecuenciales(
                    listOf(
                        Pair("/api/actuador?pir_alarma=1", "Alarma PIR Activada"),
                        Pair("/api/actuador?pir_fijo=$esFijo", "PIR Fijo: $esFijo"),
                        Pair("/api/actuador?$unidadParam", "Duración PIR")
                    ),
                    tvSubtituloConexion, cardHeroDevice
                )
            } else {
                tvEstadoPir.text = "Sensor HC-SR501: Sin movimiento detectado"
                tvEstadoPir.setTextColor(0xFF64748B.toInt())
                enviarComandoEsp32("/api/actuador?pir_alarma=0", "Alarma PIR Desactivada", tvSubtituloConexion, cardHeroDevice)
            }
            guardarEstadoPersistente()
        }

        btnBack.setOnClickListener {
            vibrarToque(it)
            monitoreando = false
            val prefs = getSharedPreferences("LuminicoSession", MODE_PRIVATE)
            prefs.edit().remove("usuario_activo").apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Iniciar monitoreo bidireccional del ESP32 en tiempo real (polling acelerado 120 ms)
        iniciarMonitoreoSensores(
            tvSubtituloConexion, cardHeroDevice, tvEstadoPir, tvMetricPir, tvMetricAudio,
            tvMetricLdr, tvMetricEstilo, tvNivelAudio, pbAudioLevel,
            pbGraves, pbMedios, pbAgudos, tvGravesPct, tvMediosPct, tvAgudosPct
        )
    }

    override fun onDestroy() {
        monitoreando = false
        try {
            dbExecutor.shutdownNow()
        } catch (_: Exception) {}
        super.onDestroy()
        desconectarNetworkCallback()
    }

    private fun obtenerRgbDesdeHex(hex: String): Triple<Int, Int, Int> {
        return when (hex.uppercase()) {
            "FF4500" -> Triple(255, 69, 0)     // Naranja Puro
            "FFD000" -> Triple(255, 208, 0)    // Amarillo Puro
            "76FF03" -> Triple(118, 255, 3)    // Lima Puro
            "00E676" -> Triple(0, 230, 118)    // Verde Puro
            "00BFA5" -> Triple(0, 191, 165)    // Turquesa Puro
            "00E5FF" -> Triple(0, 229, 255)    // Cian Puro
            "2979FF" -> Triple(41, 121, 255)   // Azul Puro
            "AA00FF" -> Triple(170, 0, 255)    // Morado Puro
            "FF007F" -> Triple(255, 0, 127)    // Rosa Puro
            "FF1744" -> Triple(255, 23, 68)     // Coral Puro
            "FFAB40" -> Triple(255, 171, 64)   // Cálido Puro
            "FFFFFF" -> Triple(255, 255, 255)  // Blanco Puro
            else -> Triple(255, 0, 0)          // FF0000 (Rojo Puro default)
        }
    }

    private fun mostrarToastRapido(mensaje: String, duracion: Int = Toast.LENGTH_SHORT) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            toastActual?.cancel()
            toastActual = Toast.makeText(this, mensaje, duracion)
            toastActual?.show()
        }
    }

    private fun vibrarToque(view: View) {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrarVinculacionExitosa() {
        try {
            val patron = longArrayOf(0, 120, 90, 220)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createWaveform(patron, -1))
            } else {
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createWaveform(patron, -1))
            }
        } catch (_: Exception) {}
    }

    private fun actualizarContadorColaOffline() {
        val cantidad = synchronized(dbHelper) {
            dbHelper.obtenerCantidadTareasPendientes()
        }
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val tvColaOffline = findViewById<TextView>(R.id.tvColaOffline)
            if (cantidad > 0) {
                tvColaOffline.visibility = View.VISIBLE
                tvColaOffline.text = "$cantidad comando${if (cantidad > 1) "s" else ""} pendiente${if (cantidad > 1) "s" else ""} en cola"
            } else {
                tvColaOffline.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------------
    // Detección inteligente antes de pedir WifiNetworkSpecifier (Multi-Phone compatible)
    // ------------------------------------------------------------------------
    private fun iniciarConexionInteligentemente() {
        if (conectandoWifi) return
        conectandoWifi = true

        val tvSubtituloConexion = findViewById<TextView>(R.id.tvSubtituloConexion)
        val cardHeroDevice = findViewById<MaterialCardView>(R.id.cardHeroDevice)

        tvSubtituloConexion.text = "WS2812B • BUSCANDO..."
        cardHeroDevice.strokeColor = 0xFF38BDF8.toInt()

        Thread {
            val yaConectado = probarConectividadActual()
            if (yaConectado) {
                conectandoWifi = false
                if (isFinishing || isDestroyed) return@Thread
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    tvSubtituloConexion.text = "WS2812B • EN LÍNEA"
                    cardHeroDevice.strokeColor = 0xFF22C55E.toInt()
                    if (!estaEnLinea) {
                        estaEnLinea = true
                        vibrarVinculacionExitosa()
                    }
                }
                consultarSensoresEsp32(
                    tvSubtituloConexion, cardHeroDevice,
                    findViewById(R.id.tvEstadoPir), findViewById(R.id.tvMetricPir),
                    findViewById(R.id.tvMetricAudio), findViewById(R.id.tvMetricLdr),
                    findViewById(R.id.tvMetricEstilo), findViewById(R.id.tvNivelAudio),
                    findViewById(R.id.pbAudioLevel),
                    findViewById(R.id.pbGraves), findViewById(R.id.pbMedios), findViewById(R.id.pbAgudos),
                    findViewById(R.id.tvGravesPct), findViewById(R.id.tvMediosPct), findViewById(R.id.tvAgudosPct)
                )
            } else {
                if (isFinishing || isDestroyed) return@Thread
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    conectarAlEsp32ConSpecifier()
                }
            }
        }.start()
    }

    private fun probarConectividadActual(): Boolean {
        try {
            connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val allNetworks = connectivityManager?.allNetworks ?: emptyArray()
            val wifiNetwork = allNetworks.firstOrNull { net ->
                val caps = connectivityManager?.getNetworkCapabilities(net)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } ?: connectivityManager?.activeNetwork

            if (wifiNetwork != null) {
                try {
                    connectivityManager?.bindProcessToNetwork(wifiNetwork)
                } catch (_: Exception) {}
            }

            synchronized(httpLock) {
                var conexion: HttpURLConnection? = null
                try {
                    val url = URL("$esp32Ip/api/sensores")
                    conexion = url.openConnection() as HttpURLConnection
                    conexion.requestMethod = "GET"
                    conexion.connectTimeout = 350
                    conexion.readTimeout = 350
                    return conexion.responseCode == 200
                } catch (_: Exception) {
                    return false
                } finally {
                    conexion?.disconnect()
                }
            }
        } catch (_: Exception) {
            return false
        }
    }

    // ------------------------------------------------------------------------
    // Validación y solicitud dinámica de permisos (Android 13+ / 14+)
    // ------------------------------------------------------------------------
    private fun verificarYPedirPermisos(): Boolean {
        val permisosRequeridos = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permisosRequeridos.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val faltantes = permisosRequeridos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (faltantes.isNotEmpty()) {
            requestPermissionsLauncher.launch(faltantes.toTypedArray())
            false
        } else {
            true
        }
    }

    // ------------------------------------------------------------------------
    // Conexión Wi-Fi mediante WifiNetworkSpecifier (Multi-Phone safe)
    // ------------------------------------------------------------------------
    private fun conectarAlEsp32ConSpecifier() {
        val tvSubtituloConexion = findViewById<TextView>(R.id.tvSubtituloConexion)
        val cardHeroDevice = findViewById<MaterialCardView>(R.id.cardHeroDevice)

        try {
            desconectarNetworkCallback()

            connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

            val requestBuilder = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid("ESP32_Luminico_IoT")
                    .setWpa2Passphrase("Inacap2026")
                    .build()
                requestBuilder.setNetworkSpecifier(specifier)
            }

            val request = requestBuilder.build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    conectandoWifi = false
                    try {
                        connectivityManager?.bindProcessToNetwork(network)
                        if (isFinishing || isDestroyed) return
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            tvSubtituloConexion.text = "WS2812B • EN LÍNEA"
                            cardHeroDevice.strokeColor = 0xFF22C55E.toInt()
                            if (!estaEnLinea) {
                                estaEnLinea = true
                                vibrarVinculacionExitosa()
                            }
                        }
                        mostrarToastRapido("Conectado a la red ESP32")
                    } catch (_: Exception) {}
                }

                override fun onLost(network: Network) {
                    conectandoWifi = false
                    try {
                        connectivityManager?.bindProcessToNetwork(null)
                        if (isFinishing || isDestroyed) return
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            estaEnLinea = false
                            tvSubtituloConexion.text = "WS2812B • OFFLINE"
                            cardHeroDevice.strokeColor = 0xFFEF4444.toInt()
                        }
                    } catch (_: Exception) {}
                }

                override fun onUnavailable() {
                    conectandoWifi = false
                    if (isFinishing || isDestroyed) return
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        estaEnLinea = false
                        tvSubtituloConexion.text = "WS2812B • OFFLINE"
                        cardHeroDevice.strokeColor = 0xFFEF4444.toInt()
                    }
                    mostrarToastRapido("Red Wi-Fi ESP32 no encontrada")
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    connectivityManager?.requestNetwork(request, networkCallback!!)
                } else {
                    connectivityManager?.registerNetworkCallback(request, networkCallback!!)
                }
            } catch (_: Exception) {
                conectandoWifi = false
                estaEnLinea = false
            }
        } catch (_: Exception) {
            conectandoWifi = false
            estaEnLinea = false
            tvSubtituloConexion.text = "WS2812B • OFFLINE"
            cardHeroDevice.strokeColor = 0xFFEF4444.toInt()
        }
    }

    private fun desconectarNetworkCallback() {
        try {
            if (networkCallback != null && connectivityManager != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
                networkCallback = null
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------------
    // Cálculo Logarítmico Calibrado en Decibelios (dB SPL)
    // ------------------------------------------------------------------------
    private fun calcularDecibeliosEstimados(micAdc: Int): Int {
        if (micAdc <= 20) return 30
        val db = (30.0 + 20.0 * log10(micAdc.toDouble() / 20.0)).toInt()
        return db.coerceIn(30, 100)
    }

    // Anti-Saturación equilibrada (250 ms)
    private fun permitirEnvioSeguridad(): Boolean {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoComandoMs < 250) return false
        ultimoComandoMs = ahora
        return true
    }

    // ------------------------------------------------------------------------
    // UNIDAD 3: Comunicación HTTP Secuencial (Secuencia Garantizada de 1 Parámetro por Petición al ESP32)
    // ------------------------------------------------------------------------
    private fun enviarComandosSecuenciales(comandos: List<Pair<String, String>>, tvEstado: TextView, cardHeroDevice: MaterialCardView) {
        Thread {
            synchronized(httpLock) {
                for (comando in comandos) {
                    var exito = false
                    var intentos = 0
                    val maxIntentos = if (estaEnLinea) 2 else 1
                    val timeoutMs = if (estaEnLinea) 750 else 350

                    while (intentos < maxIntentos && !exito) {
                        var conexion: HttpURLConnection? = null
                        try {
                            val url = URL("$esp32Ip${comando.first}")
                            conexion = url.openConnection() as HttpURLConnection
                            conexion.requestMethod = "POST"
                            conexion.doOutput = true
                            conexion.connectTimeout = timeoutMs
                            conexion.readTimeout = timeoutMs

                            if (conexion.responseCode == 200) {
                                exito = true
                                if (!isFinishing && !isDestroyed) {
                                    runOnUiThread {
                                        if (isFinishing || isDestroyed) return@runOnUiThread
                                        tvEstado.text = "WS2812B • EN LÍNEA"
                                        cardHeroDevice.strokeColor = 0xFF22C55E.toInt()
                                        if (!estaEnLinea) {
                                            estaEnLinea = true
                                            vibrarVinculacionExitosa()
                                        }
                                    }
                                }
                                mostrarToastRapido("ESP32: ${comando.second}")
                                sincronizarTareasOffline()
                                Thread.sleep(60)
                            }
                        } catch (_: Exception) {
                            intentos++
                            if (intentos < maxIntentos) Thread.sleep(80)
                        } finally {
                            conexion?.disconnect()
                        }
                    }

                    if (!exito) {
                        synchronized(dbHelper) {
                            dbHelper.guardarTareaOffline(comando.first, comando.second)
                        }
                        actualizarContadorColaOffline()
                        if (!isFinishing && !isDestroyed) {
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                estaEnLinea = false
                                tvEstado.text = "WS2812B • OFFLINE"
                                cardHeroDevice.strokeColor = 0xFFEF4444.toInt()
                            }
                        }
                        mostrarToastRapido("${comando.second} guardado localmente (Offline)")
                    }
                }
            }
        }.start()
    }

    private fun enviarComandoEsp32(ruta: String, nombreAccion: String, tvEstado: TextView, cardHeroDevice: MaterialCardView) {
        enviarComandosSecuenciales(listOf(Pair(ruta, nombreAccion)), tvEstado, cardHeroDevice)
    }

    private fun sincronizarTareasOffline() {
        if (sincronizandoOffline) return
        Thread {
            synchronized(dbHelper) {
                sincronizandoOffline = true
                try {
                    val tareasPendientes = dbHelper.obtenerTareasPendientes()
                    if (tareasPendientes.isNotEmpty()) {
                        mostrarToastRapido("Sincronizando tareas offline...")
                        for (tarea in tareasPendientes) {
                            synchronized(httpLock) {
                                var conexion: HttpURLConnection? = null
                                try {
                                    val url = URL("$esp32Ip${tarea.first}")
                                    conexion = url.openConnection() as HttpURLConnection
                                    conexion.requestMethod = "POST"
                                    conexion.doOutput = true
                                    conexion.connectTimeout = 750
                                    conexion.readTimeout = 750
                                    conexion.responseCode
                                } catch (_: Exception) {
                                    return@synchronized
                                } finally {
                                    conexion?.disconnect()
                                }
                            }
                        }
                        dbHelper.limpiarColaTareas()
                        actualizarContadorColaOffline()
                        mostrarToastRapido("Sincronización completada")
                    }
                } finally {
                    sincronizandoOffline = false
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------------
    // UNIDAD 3: Polling Serial ultrarrápido a 120 ms para espectro frecuencial en tiempo real
    // ------------------------------------------------------------------------
    private fun consultarSensoresEsp32(
        tvSubtitulo: TextView,
        cardHeroDevice: MaterialCardView,
        tvPirEstado: TextView,
        tvMetricPir: TextView,
        tvMetricAudio: TextView,
        tvMetricLdr: TextView,
        tvMetricEstilo: TextView,
        tvNivelAudio: TextView,
        pbAudioLevel: ProgressBar,
        pbGraves: ProgressBar,
        pbMedios: ProgressBar,
        pbAgudos: ProgressBar,
        tvGravesPct: TextView,
        tvMediosPct: TextView,
        tvAgudosPct: TextView
    ) {
        synchronized(httpLock) {
            var conexion: HttpURLConnection? = null
            try {
                val url = URL("$esp32Ip/api/sensores")
                conexion = url.openConnection() as HttpURLConnection
                conexion.requestMethod = "GET"
                conexion.connectTimeout = 350
                conexion.readTimeout = 350

                if (conexion.responseCode == 200) {
                    val respuestaTexto = conexion.inputStream.bufferedReader().use { it.readText() }

                    val json = JSONObject(respuestaTexto)
                    val pir = json.optBoolean("pir", false)
                    val mic = json.optInt("mic", 0)
                    val ldr = json.optInt("ldr", 0)
                    val estilo = json.optString("estilo", "Auto")

                    val bass = json.optInt("bass", 0).coerceIn(0, 100)
                    val mid = json.optInt("mid", 0).coerceIn(0, 100)
                    val treble = json.optInt("treble", 0).coerceIn(0, 100)

                    val dbEstimado = calcularDecibeliosEstimados(mic)
                    val progress = (((dbEstimado - 30).toFloat() / 70f) * 100f).toInt().coerceIn(0, 100)

                    if (!isFinishing && !isDestroyed) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            tvSubtitulo.text = "WS2812B • EN LÍNEA"
                            cardHeroDevice.strokeColor = 0xFF22C55E.toInt()
                            if (!estaEnLinea) {
                                estaEnLinea = true
                                vibrarVinculacionExitosa()
                            }

                            if (pir) {
                                tvPirEstado.text = "¡MOVIMIENTO DETECTADO!"
                                tvPirEstado.setTextColor(0xFFEF4444.toInt())
                                tvMetricPir.text = "DETECTADO"
                                tvMetricPir.setTextColor(0xFFEF4444.toInt())
                            } else {
                                tvPirEstado.text = "Sensor HC-SR501: Sin movimiento detectado"
                                tvPirEstado.setTextColor(0xFF64748B.toInt())
                                tvMetricPir.text = "Limpio"
                                tvMetricPir.setTextColor(0xFF22C55E.toInt())
                            }

                            tvMetricAudio.text = "$mic ADC"
                            tvNivelAudio.text = "$dbEstimado dB"
                            pbAudioLevel.progress = progress

                            tvMetricLdr.text = ldr.toString()
                            tvMetricEstilo.text = estilo

                            pbGraves.progress = bass
                            tvGravesPct.text = "$bass%"
                            pbMedios.progress = mid
                            tvMediosPct.text = "$mid%"
                            pbAgudos.progress = treble
                            tvAgudosPct.text = "$treble%"
                        }
                    }

                    // Auto-sincronización de tareas offline pendientes si la conexión se reestablece
                    if (dbHelper.obtenerTareasPendientes().isNotEmpty()) {
                        sincronizarTareasOffline()
                    }
                } else {
                    if (!isFinishing && !isDestroyed) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            estaEnLinea = false
                            tvSubtitulo.text = "WS2812B • OFFLINE"
                            cardHeroDevice.strokeColor = 0xFFEF4444.toInt()
                            tvNivelAudio.text = "-- dB"
                            pbAudioLevel.progress = 0
                            tvMetricPir.text = "--"
                            tvMetricPir.setTextColor(0xFF64748B.toInt())
                            tvMetricAudio.text = "--"
                            tvMetricLdr.text = "--"
                            tvMetricEstilo.text = "--"
                            pbGraves.progress = 0
                            tvGravesPct.text = "0%"
                            pbMedios.progress = 0
                            tvMediosPct.text = "0%"
                            pbAgudos.progress = 0
                            tvAgudosPct.text = "0%"
                        }
                    }
                }
            } catch (_: Exception) {
                if (!isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        estaEnLinea = false
                        tvSubtitulo.text = "WS2812B • OFFLINE"
                        cardHeroDevice.strokeColor = 0xFFEF4444.toInt()
                        tvNivelAudio.text = "-- dB"
                        pbAudioLevel.progress = 0
                        tvMetricPir.text = "--"
                        tvMetricPir.setTextColor(0xFF64748B.toInt())
                        tvMetricAudio.text = "--"
                        tvMetricLdr.text = "--"
                        tvMetricEstilo.text = "--"
                        pbGraves.progress = 0
                        tvGravesPct.text = "0%"
                        pbMedios.progress = 0
                        tvMediosPct.text = "0%"
                        pbAgudos.progress = 0
                        tvAgudosPct.text = "0%"
                    }
                }
            } finally {
                conexion?.disconnect()
            }
        }
    }

    private fun iniciarMonitoreoSensores(
        tvSubtitulo: TextView,
        cardHeroDevice: MaterialCardView,
        tvPirEstado: TextView,
        tvMetricPir: TextView,
        tvMetricAudio: TextView,
        tvMetricLdr: TextView,
        tvMetricEstilo: TextView,
        tvNivelAudio: TextView,
        pbAudioLevel: ProgressBar,
        pbGraves: ProgressBar,
        pbMedios: ProgressBar,
        pbAgudos: ProgressBar,
        tvGravesPct: TextView,
        tvMediosPct: TextView,
        tvAgudosPct: TextView
    ) {
        Thread {
            while (monitoreando && !isFinishing && !isDestroyed) {
                consultarSensoresEsp32(
                    tvSubtitulo, cardHeroDevice, tvPirEstado, tvMetricPir, tvMetricAudio,
                    tvMetricLdr, tvMetricEstilo, tvNivelAudio, pbAudioLevel,
                    pbGraves, pbMedios, pbAgudos, tvGravesPct, tvMediosPct, tvAgudosPct
                )
                try {
                    Thread.sleep(120) // Frecuencia de refresco acelerada a 120 ms
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.start()
    }
}