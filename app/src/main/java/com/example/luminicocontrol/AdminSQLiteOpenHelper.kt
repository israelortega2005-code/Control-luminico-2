package com.example.luminicocontrol

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

// Modelo de datos para la configuración de estado persistente
data class ConfigEstado(
    val modoPrincipal: String = "color",
    val subModo: String = "rojo",
    val colorRgb: String = "EF4444",
    val sensibilidad: String = "estandar",
    val lucesEncendidas: Boolean = true,
    val micActivo: Boolean = true,
    val pirActivo: Boolean = false,
    val seguridadMaster: Boolean = false,
    val seguridadPermanente: Boolean = false,
    val tiempoPirValor: String = "5",
    val tiempoPirEsMinuto: Boolean = true,
    val brilloLed: Int = 255
)

class AdminSQLiteOpenHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "LuminicoDB"
        private const val DATABASE_VERSION = 5
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Tabla de usuarios para autenticación con contraseñas cifradas en SHA-256
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS usuarios (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT UNIQUE, " +
                    "password_hash TEXT)"
        )

        // Tabla de cola de tareas offline para reintentos inteligentes
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cola_tareas (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "ruta TEXT, " +
                    "accion TEXT)"
        )

        // Tabla de configuración de estado persistente (Versión 4+)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS configuracion_estado (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "modo_principal TEXT, " +
                    "sub_modo TEXT, " +
                    "color_rgb TEXT, " +
                    "sensibilidad TEXT, " +
                    "luces_encendidas INTEGER, " +
                    "mic_activo INTEGER, " +
                    "pir_activo INTEGER, " +
                    "seguridad_master INTEGER, " +
                    "seguridad_permanente INTEGER, " +
                    "tiempo_pir_valor TEXT, " +
                    "tiempo_pir_es_minuto INTEGER, " +
                    "brillo_led INTEGER DEFAULT 255)"
        )

        // Insertar usuario administrador por defecto si la base de datos está vacía
        val hashDefault = cifrarSHA256("1234")
        db.execSQL("INSERT OR IGNORE INTO usuarios (username, password_hash) VALUES ('inacap', '$hashDefault')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS cola_tareas (id INTEGER PRIMARY KEY AUTOINCREMENT, ruta TEXT, accion TEXT)")
        }
        if (oldVersion < 3) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS configuracion_estado (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "modo_principal TEXT, " +
                        "sub_modo TEXT, " +
                        "color_rgb TEXT, " +
                        "sensibilidad TEXT, " +
                        "luces_encendidas INTEGER, " +
                        "mic_activo INTEGER, " +
                        "pir_activo INTEGER, " +
                        "seguridad_master INTEGER, " +
                        "seguridad_permanente INTEGER, " +
                        "tiempo_pir_valor TEXT, " +
                        "tiempo_pir_es_minuto INTEGER)"
            )
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE configuracion_estado ADD COLUMN brillo_led INTEGER DEFAULT 255")
            } catch (_: Exception) {}
        }
        if (oldVersion < 5) {
            onCreate(db)
        }
    }

    // Cifrado SHA-256 (Seguridad ISO 27000)
    fun cifrarSHA256(texto: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun registrarUsuario(username: String, passwordPlana: String): Boolean {
        val db = this.writableDatabase
        val valores = ContentValues().apply {
            put("username", username.trim())
            put("password_hash", cifrarSHA256(passwordPlana))
        }
        val resultado = db.insert("usuarios", null, valores)
        db.close()
        return resultado != -1L
    }

    fun validarUsuario(username: String, passwordPlana: String): Boolean {
        val db = this.readableDatabase
        val hashIngresado = cifrarSHA256(passwordPlana)
        val cursor = db.rawQuery(
            "SELECT * FROM usuarios WHERE username = ? AND password_hash = ?",
            arrayOf(username.trim(), hashIngresado)
        )
        val existe = cursor.count > 0
        cursor.close()
        db.close()
        return existe
    }

    // --- FUNCIONES OFFLINE DEPURADAS PARA EVITAR SATURACIÓN POR CATEGORÍA ---

    fun guardarTareaOffline(ruta: String, accion: String) {
        val db = this.writableDatabase
        try {
            // Depurar inteligentemente según la categoría del parámetro para evitar saturar la cola
            when {
                ruta.contains("brillo=") -> db.delete("cola_tareas", "ruta LIKE ?", arrayOf("%brillo=%"))
                ruta.contains("sens=") -> db.delete("cola_tareas", "ruta LIKE ?", arrayOf("%sens=%"))
                ruta.contains("pir_alarma=") -> db.delete("cola_tareas", "ruta LIKE ?", arrayOf("%pir_alarma=%"))
                ruta.contains("pir_min=") -> db.delete("cola_tareas", "ruta LIKE ?", arrayOf("%pir_min=%"))
                else -> db.delete(
                    "cola_tareas",
                    "ruta NOT LIKE '%brillo=%' AND ruta NOT LIKE '%sens=%' AND ruta NOT LIKE '%pir_alarma=%' AND ruta NOT LIKE '%pir_min=%'",
                    null
                )
            }

            val valores = ContentValues().apply {
                put("ruta", ruta)
                put("accion", accion)
            }
            db.insert("cola_tareas", null, valores)

            // Limitar la cola a un máximo de 10 tareas más recientes para no saturar al ESP32 al reconectar
            db.execSQL("DELETE FROM cola_tareas WHERE id NOT IN (SELECT id FROM cola_tareas ORDER BY id DESC LIMIT 10)")
        } catch (_: Exception) {
        } finally {
            db.close()
        }
    }

    fun obtenerTareasPendientes(): List<Pair<String, String>> {
        val listaTareas = mutableListOf<Pair<String, String>>()
        val db = this.readableDatabase
        try {
            val cursor = db.rawQuery("SELECT ruta, accion FROM cola_tareas ORDER BY id ASC", null)
            if (cursor.moveToFirst()) {
                do {
                    val ruta = cursor.getString(0)
                    val accion = cursor.getString(1)
                    listaTareas.add(Pair(ruta, accion))
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (_: Exception) {
        } finally {
            db.close()
        }
        return listaTareas
    }

    fun obtenerCantidadTareasPendientes(): Int {
        var cantidad = 0
        val db = this.readableDatabase
        try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM cola_tareas", null)
            if (cursor.moveToFirst()) {
                cantidad = cursor.getInt(0)
            }
            cursor.close()
        } catch (_: Exception) {
            cantidad = 0
        } finally {
            db.close()
        }
        return cantidad
    }

    fun limpiarColaTareas() {
        val db = this.writableDatabase
        try {
            db.delete("cola_tareas", null, null)
        } catch (_: Exception) {
        } finally {
            db.close()
        }
    }

    // --- PERSISTENCIA DE CONFIGURACIÓN DE ESTADO ---

    fun guardarEstadoConfiguracion(config: ConfigEstado) {
        val db = this.writableDatabase
        val valores = ContentValues().apply {
            put("modo_principal", config.modoPrincipal)
            put("sub_modo", config.subModo)
            put("color_rgb", config.colorRgb)
            put("sensibilidad", config.sensibilidad)
            put("luces_encendidas", if (config.lucesEncendidas) 1 else 0)
            put("mic_activo", if (config.micActivo) 1 else 0)
            put("pir_activo", if (config.pirActivo) 1 else 0)
            put("seguridad_master", if (config.seguridadMaster) 1 else 0)
            put("seguridad_permanente", if (config.seguridadPermanente) 1 else 0)
            put("tiempo_pir_valor", config.tiempoPirValor)
            put("tiempo_pir_es_minuto", if (config.tiempoPirEsMinuto) 1 else 0)
            put("brillo_led", config.brilloLed)
        }

        val cursor = db.rawQuery("SELECT id FROM configuracion_estado LIMIT 1", null)
        if (cursor.moveToFirst()) {
            val id = cursor.getInt(0)
            db.update("configuracion_estado", valores, "id = ?", arrayOf(id.toString()))
        } else {
            db.insert("configuracion_estado", null, valores)
        }
        cursor.close()
        db.close()
    }

    fun obtenerEstadoConfiguracion(): ConfigEstado? {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM configuracion_estado LIMIT 1", null)
        var config: ConfigEstado? = null

        if (cursor.moveToFirst()) {
            val modoPrincipal = cursor.getString(cursor.getColumnIndexOrThrow("modo_principal"))
            val subModo = cursor.getString(cursor.getColumnIndexOrThrow("sub_modo"))
            val colorRgb = cursor.getString(cursor.getColumnIndexOrThrow("color_rgb"))
            val sensibilidad = cursor.getString(cursor.getColumnIndexOrThrow("sensibilidad"))
            val lucesEncendidas = cursor.getInt(cursor.getColumnIndexOrThrow("luces_encendidas")) == 1
            val micActivo = cursor.getInt(cursor.getColumnIndexOrThrow("mic_activo")) == 1
            val pirActivo = cursor.getInt(cursor.getColumnIndexOrThrow("pir_activo")) == 1
            val seguridadMaster = cursor.getInt(cursor.getColumnIndexOrThrow("seguridad_master")) == 1
            val seguridadPermanente = cursor.getInt(cursor.getColumnIndexOrThrow("seguridad_permanente")) == 1
            val tiempoPirValor = cursor.getString(cursor.getColumnIndexOrThrow("tiempo_pir_valor"))
            val tiempoPirEsMinuto = cursor.getInt(cursor.getColumnIndexOrThrow("tiempo_pir_es_minuto")) == 1
            val brilloLedIndex = cursor.getColumnIndex("brillo_led")
            val brilloLed = if (brilloLedIndex != -1) cursor.getInt(brilloLedIndex) else 255

            config = ConfigEstado(
                modoPrincipal, subModo, colorRgb, sensibilidad,
                lucesEncendidas, micActivo, pirActivo, seguridadMaster,
                seguridadPermanente, tiempoPirValor, tiempoPirEsMinuto, brilloLed
            )
        }
        cursor.close()
        db.close()
        return config
    }
}