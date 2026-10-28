// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
}

// Automatically materialize debug.keystore from debug.keystore.base64 on freshly cloned machines
val debugKeystoreFile = file("debug.keystore")
val debugKeystoreBase64File = file("debug.keystore.base64")
if (!debugKeystoreFile.exists() && debugKeystoreBase64File.exists()) {
  val decodedBytes = java.util.Base64.getMimeDecoder().decode(debugKeystoreBase64File.readText())
  debugKeystoreFile.writeBytes(decodedBytes)
}

