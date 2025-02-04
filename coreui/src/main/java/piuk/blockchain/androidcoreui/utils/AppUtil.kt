package piuk.blockchain.androidcoreui.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera

import java.io.File

import info.blockchain.wallet.payload.PayloadManagerWiper
import piuk.blockchain.androidcore.data.access.AccessState
import piuk.blockchain.androidcore.utils.PersistentPrefs
import piuk.blockchain.androidcore.utils.PrefsUtil

class AppUtil(
    private val context: Context,
    private var payloadManager: PayloadManagerWiper,
    private var accessState: AccessState,
    private val prefs: PersistentPrefs
) {
    // getExternalCacheDir can return null if permission for write storage not granted
    // or if running on an emulator
    val receiveQRFilename: String
        get() = context.externalCacheDir.toString() + File.separator + "qr.png"

    val isSane: Boolean
        get() {
            val guid = prefs.getValue(PrefsUtil.KEY_GUID, "")

            if (!guid.matches(REGEX_UUID.toRegex())) {
                return false
            }

            val encryptedPassword = prefs.getValue(PrefsUtil.KEY_ENCRYPTED_PASSWORD, "")
            val pinID = prefs.getValue(PrefsUtil.KEY_PIN_IDENTIFIER, "")

            return !(encryptedPassword.isEmpty() || pinID.isEmpty())
        }

    var sharedKey: String
        get() = prefs.getValue(PrefsUtil.KEY_SHARED_KEY, "")
        set(sharedKey) = prefs.setValue(PrefsUtil.KEY_SHARED_KEY, sharedKey)

    val packageManager: PackageManager
        get() = context.packageManager

    val isCameraOpen: Boolean
        get() {
            var camera: Camera? = null

            try {
                camera = Camera.open()
            } catch (e: RuntimeException) {
                return true
            } finally {
                camera?.release()
            }

            return false
        }

    fun clearCredentials() {
        payloadManager.wipe()
        prefs.clear()
        accessState.forgetWallet()
    }

    fun clearCredentialsAndRestart(launcherActivity: Class<*>) {
        clearCredentials()
        restartApp(launcherActivity)
    }

    fun restartApp(launcherActivity: Class<*>) {
        context.startActivity(
            Intent(context, launcherActivity).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun restartAppWithVerifiedPin(launcherActivity: Class<*>) {
        context.startActivity(
            Intent(context, launcherActivity).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("verified", true)
            }
        )
        AccessState.getInstance().logIn()
    }

    fun deleteQR() {
        // getExternalCacheDir can return null if permission for write storage not granted
        // or if running on an emulator
        val file = File(context.externalCacheDir.toString() + File.separator + "qr.png")
        if (file.exists()) {
            file.delete()
        }
    }

    companion object {

        private const val REGEX_UUID =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

        val isBuySellPermitted: Boolean
            get() = AndroidUtils.is19orHigher()
    }
}
