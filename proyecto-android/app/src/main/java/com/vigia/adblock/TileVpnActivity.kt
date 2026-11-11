package com.vigia.adblock

import android.app.Activity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Actividad invisible que usa el tile para pedir el permiso de VPN (diálogo de Android) la primera vez.
 * Al aceptar arranca la protección; en cualquier caso se cierra sola. Sin interfaz propia.
 */
class TileVpnActivity : AppCompatActivity() {

    private val lanzadorVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        if (resultado.resultCode == Activity.RESULT_OK) {
            try {
                ProteccionControl.encender(this)
            } catch (e: Exception) {
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si la actividad se recrea (giro, etc.) el diálogo ya está en marcha y el resultado llegará al lanzador.
        if (savedInstanceState != null) return
        val intent = ProteccionControl.controlador(this).permisoPendiente()
        if (intent == null) {
            // Ya había permiso (carrera con otro arranque): se enciende directamente.
            try {
                ProteccionControl.encender(this)
            } catch (e: Exception) {
            }
            finish()
        } else {
            lanzadorVpn.launch(intent)
        }
    }
}
