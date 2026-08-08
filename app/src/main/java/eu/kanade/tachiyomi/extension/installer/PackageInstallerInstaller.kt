package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import android.content.Intent
import androidx.core.content.FileProvider
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.InstallStep
import java.io.File

/**
 * Instalador de extensiones que NUNCA usa PackageInstaller (sesiones).
 * En MIUI/HyperOS (Xiaomi/Poco/Redmi) las sesiones de instalación de apps de
 * terceros están bloqueadas y lanzan "Index 0 requested, with a size of 0".
 * Por eso aquí simplemente abrimos el APK con el instalador del sistema,
 * igual que si el usuario tocara el archivo en el administrador de archivos.
 */
class PackageInstallerInstaller(private val service: Service) : Installer(service) {

    // Siempre listo
    override var ready = true

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        if (openSystemInstaller(entry)) {
            // El estado real de "instalada" se actualiza solo mediante el
            // broadcast ACTION_PACKAGE_ADDED cuando el usuario acepta en el sistema.
            continueQueue(InstallStep.Idle)
        } else {
            snackString("No se pudo abrir el instalador del sistema")
            continueQueue(InstallStep.Error)
        }
    }

    /**
     * Abre el APK descargado con el instalador de paquetes del sistema.
     */
    private fun openSystemInstaller(entry: Entry): Boolean {
        return try {
            val uri = if (entry.uri.scheme == "file") {
                val file = File(entry.uri.path ?: return false)
                // Probamos los dos authorities de FileProvider más comunes
                listOf("${service.packageName}.provider", "${service.packageName}.fileprovider")
                    .firstNotNullOfOrNull { authority ->
                        try {
                            FileProvider.getUriForFile(service, authority, file)
                        } catch (e: Exception) {
                            null
                        }
                    } ?: return false
            } else {
                entry.uri
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            Logger.log("System installer opened for ${entry.downloadId}")
            true
        } catch (e: Exception) {
            Logger.log("Failed to open system installer\n$e")
            false
        }
    }

    override fun cancelEntry(entry: Entry): Boolean = true
}
