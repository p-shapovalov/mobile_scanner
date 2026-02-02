package dev.steenbakker.mobile_scanner_example

import dev.steenbakker.mobile_scanner.MobileScannerNativeView
import io.flutter.plugins.nativeview.NativeViewFlutterActivity

/**
 * Main activity that hosts native views below a transparent Flutter view.
 *
 * This activity extends NativeViewFlutterActivity to enable native view support
 * for the mobile scanner, providing better performance than the texture-based approach.
 */
class MainActivity : NativeViewFlutterActivity() {

    override fun onRegisterNativeViews() {
        // Register the mobile scanner native view
        registerNativeViewFactory("mobile_scanner_native_view") {
            MobileScannerNativeView()
        }
    }
}
