package com.example.luminicocontrol

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Verificar sesión activa persistente
        val prefs = getSharedPreferences("LuminicoSession", MODE_PRIVATE)
        val usuarioActivo = prefs.getString("usuario_activo", null)
        if (!usuarioActivo.isNullOrEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val dbHelper = AdminSQLiteOpenHelper(this)

        val etUsuario = findViewById<EditText>(R.id.etUsuario)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnRegistrar = findViewById<Button>(R.id.btnRegistrar)

        btnRegistrar.setOnClickListener {
            val user = etUsuario.text.toString().trim()
            val pass = etPassword.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Completa el usuario y la contraseña", Toast.LENGTH_SHORT).show()
            } else if (user.length < 3) {
                Toast.makeText(this, "El usuario debe tener al menos 3 caracteres", Toast.LENGTH_SHORT).show()
            } else if (pass.length < 4) {
                Toast.makeText(this, "La contraseña debe tener mínimo 4 caracteres", Toast.LENGTH_SHORT).show()
            } else {
                val registrado = dbHelper.registrarUsuario(user, pass)
                if (registrado) {
                    Toast.makeText(this, "Usuario guardado con cifrado SHA-256", Toast.LENGTH_SHORT).show()
                    etPassword.text.clear()
                } else {
                    Toast.makeText(this, "El usuario ya existe en la base de datos", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnLogin.setOnClickListener {
            val user = etUsuario.text.toString().trim()
            val pass = etPassword.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Ingresa tu usuario y contraseña", Toast.LENGTH_SHORT).show()
            } else {
                val accesoValido = dbHelper.validarUsuario(user, pass)
                if (accesoValido) {
                    prefs.edit().putString("usuario_activo", user).apply()
                    Toast.makeText(this, "Autenticación correcta", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, "Usuario o contraseña incorrectos", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}