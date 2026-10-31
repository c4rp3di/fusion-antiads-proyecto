package com.vigia.core.seguridad

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Entrega a Room la fábrica de SQLCipher con la clave custodiada por el Keystore. Cada app define sus entidades:
 *
 *     Room.databaseBuilder(ctx, MiBd::class.java, "mi.db")
 *         .openHelperFactory(BaseDatosCifrada.fabrica(almacen, "mi.db"))
 *         .build()
 */
object BaseDatosCifrada {
    fun fabrica(almacen: AlmacenClaves, nombreBd: String): SupportOpenHelperFactory {
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(almacen.obtenerClaveBd(nombreBd))
    }
}
