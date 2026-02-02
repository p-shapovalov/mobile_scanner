package dev.steenbakker.mobile_scanner

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** MobileScannerPlugin */
class MobileScannerPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    private var activityPluginBinding: ActivityPluginBinding? = null
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var barcodeHandler: BarcodeHandler? = null
    private var methodChannel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.flutterPluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.flutterPluginBinding = null
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        val binaryMessenger = this.flutterPluginBinding!!.binaryMessenger

        barcodeHandler = BarcodeHandler(binaryMessenger)

        // Set up method channel for handling method calls
        methodChannel = MethodChannel(
            binaryMessenger,
            "dev.steenbakker.mobile_scanner/scanner/method"
        )
        methodChannel?.setMethodCallHandler(this)

        // Initialize the native view for native view support
        MobileScannerNativeView.initialize(
            activityPluginBinding.activity,
            barcodeHandler!!
        )

        this.activityPluginBinding = activityPluginBinding
    }

    override fun onDetachedFromActivity() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        MobileScannerNativeView.dispose()
        barcodeHandler = null
        activityPluginBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "toggleTorch" -> {
                MobileScannerNativeView.toggleTorchStatic()
                result.success(null)
            }
            "setScale" -> {
                val scale = call.arguments as? Double ?: 0.0
                MobileScannerNativeView.setScaleStatic(scale)
                result.success(null)
            }
            "resetScale" -> {
                MobileScannerNativeView.resetScaleStatic()
                result.success(null)
            }
            "setFocus" -> {
                val x = (call.argument<Double>("dx") ?: 0.0).toFloat()
                val y = (call.argument<Double>("dy") ?: 0.0).toFloat()
                MobileScannerNativeView.setFocusStatic(x, y)
                result.success(null)
            }
            "updateScanWindow" -> {
                val rect = call.argument<List<Double>>("rect")
                val scanWindow = rect?.map { it.toFloat() }
                MobileScannerNativeView.updateScanWindowStatic(scanWindow)
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }
}
