package dev.steenbakker.mobile_scanner.objects

/**
 * Parameters for native view scanner start callback.
 * This is a simplified version without texture-related fields.
 */
class NativeViewStartParameters(
    val width: Double,
    val height: Double,
    val currentTorchState: Int,
    val numberOfCameras: Int,
    val cameraDirection: Int?,
)
