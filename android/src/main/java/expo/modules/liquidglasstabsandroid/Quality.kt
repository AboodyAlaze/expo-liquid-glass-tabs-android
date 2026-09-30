package expo.modules.liquidglasstabsandroid

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * HIGH   → full effect (refraction, blur, chroma, tilt light)
 * MEDIUM → same glass, without the costly extras (no blur, no chroma, no gyroscope)
 * LOW    → no shader at all: drawn frosted glass, near-zero cost. Animations stay the same.
 */
enum class Quality {
  HIGH, MEDIUM, LOW;

  companion object {
    fun resolve(context: Context, requested: String?): Quality = when (requested?.lowercase()) {
      "high" -> HIGH
      "medium" -> MEDIUM
      "low" -> LOW
      else -> auto(context)
    }

    private fun auto(context: Context): Quality {
      // No AGSL before Android 13
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return LOW

      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      if (am.isLowRamDevice) return LOW

      var tier = when {
        // Devices the OS itself certifies as performant
        Build.VERSION.MEDIA_PERFORMANCE_CLASS >= Build.VERSION_CODES.S -> HIGH
        Runtime.getRuntime().availableProcessors() >= 8 && am.memoryClass >= 256 -> HIGH
        else -> MEDIUM
      }

      // Battery saver → step down one level
      val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      if (pm.isPowerSaveMode) tier = if (tier == HIGH) MEDIUM else LOW
      return tier
    }
  }
}
