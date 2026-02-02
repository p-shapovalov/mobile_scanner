import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_native_view_android/flutter_native_view_android.dart';
import 'package:mobile_scanner/src/mobile_scanner_controller.dart';
import 'package:mobile_scanner/src/objects/barcode_capture.dart';

/// A key used to identify the mobile scanner native view.
const String kMobileScannerNativeViewKey = 'mobile_scanner_native_view';

/// A widget that displays the mobile scanner using a native SurfaceView
/// below the transparent Flutter layer.
///
/// This widget provides better performance than the texture-based approach
/// by rendering the camera preview directly using a native Android SurfaceView.
///
/// **Important:** This widget only works on Android and requires the app's
/// MainActivity to extend `NativeViewFlutterActivity` and register the
/// mobile scanner native view factory.
///
/// Example usage in MainActivity.kt:
/// ```kotlin
/// class MainActivity : NativeViewFlutterActivity() {
///     override fun onRegisterNativeViews() {
///         registerNativeViewFactory("mobile_scanner_native_view") {
///             MobileScannerNativeViewFactory.createNativeView("mobile_scanner_native_view")
///         }
///     }
/// }
/// ```
class MobileScannerNativeView extends StatefulWidget {
  /// Create a new [MobileScannerNativeView] using the provided [controller].
  const MobileScannerNativeView({
    this.controller,
    this.onDetect,
    this.onDetectError = _onDetectErrorHandler,
    this.overlayBuilder,
    this.placeholderBuilder,
    this.viewKey = kMobileScannerNativeViewKey,
    super.key,
  });

  /// The controller for the camera preview.
  ///
  /// Note: For native view mode, the controller is only used to listen
  /// for barcode events. The camera is started/stopped by the native view.
  final MobileScannerController? controller;

  /// The function that signals when new codes were detected by the
  /// [controller].
  final void Function(BarcodeCapture barcodes)? onDetect;

  /// The error handler equivalent for the [onDetect] function.
  final void Function(Object error, StackTrace stackTrace) onDetectError;

  /// The builder for the overlay above the camera preview.
  final LayoutWidgetBuilder? overlayBuilder;

  /// The placeholder builder for the camera preview.
  final WidgetBuilder? placeholderBuilder;

  /// The unique key identifying the native view to control.
  ///
  /// This must match the key registered in the MainActivity.
  final String viewKey;

  @override
  State<MobileScannerNativeView> createState() =>
      _MobileScannerNativeViewState();

  static void _onDetectErrorHandler(Object error, StackTrace stackTrace) {
    // Do nothing.
  }
}

class _MobileScannerNativeViewState extends State<MobileScannerNativeView>
    with WidgetsBindingObserver {
  MobileScannerController? _controller;

  StreamSubscription<BarcodeCapture>? _subscription;
  bool _isNativeViewReady = false;

  MobileScannerController get controller {
    return _controller ??= widget.controller ?? MobileScannerController();
  }

  @override
  void initState() {
    super.initState();

    if (widget.controller == null) {
      WidgetsBinding.instance.addObserver(this);
    }

    // Set up listeners for native view mode - this enables receiving
    // barcode events without calling controller.start()
    controller.setupListenersForNativeView();

    // Listen for barcode events from the native view
    // The native view uses the same event channel as the regular scanner
    if (widget.onDetect != null) {
      _subscription = controller.barcodes.listen(
        widget.onDetect,
        onError: widget.onDetectError,
        cancelOnError: false,
      );
    }
  }

  @override
  void dispose() {
    if (widget.controller == null) {
      WidgetsBinding.instance.removeObserver(this);
    }

    unawaited(_subscription?.cancel());
    _subscription = null;

    if (widget.controller == null) {
      unawaited(_controller?.dispose());
    }
    super.dispose();
  }
  
  void _onNativeViewReady() {
    if (mounted && !_isNativeViewReady) {
      setState(() {
        _isNativeViewReady = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // Only use native view on Android
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const Center(
        child: Text('Native view is only supported on Android'),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final overlay = widget.overlayBuilder?.call(context, constraints);

        final Widget nativeViewWidget = _MobileScannerNativeViewWidget(
          viewKey: widget.viewKey,
          onViewReady: _onNativeViewReady,
        );

        // Show placeholder on top until native view is ready
        // Only show placeholder if explicitly provided by the user
        final showPlaceholder =
            !_isNativeViewReady && widget.placeholderBuilder != null;
        final placeholder =
            showPlaceholder ? widget.placeholderBuilder!.call(context) : null;

        return Stack(
          alignment: Alignment.center,
          children: <Widget>[
            nativeViewWidget,
            if (placeholder != null)
              Positioned.fill(child: placeholder),
            if (overlay != null)
              IgnorePointer(child: overlay),
          ],
        );
      },
    );
  }
}

/// Internal widget that extends NativeViewWidget to manage the native view
/// lifecycle.
class _MobileScannerNativeViewWidget extends NativeViewWidget {
  const _MobileScannerNativeViewWidget({
    required this.viewKey,
    this.onViewReady,
  });

  @override
  final String viewKey;

  final VoidCallback? onViewReady;

  @override
  void onViewShown() {
    debugPrint('MobileScannerNativeView: view shown');
    onViewReady?.call();
  }

  @override
  void onViewHidden() {
    debugPrint('MobileScannerNativeView: view hidden');
  }
}
