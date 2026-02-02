import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// Example of a mobile scanner using native Android SurfaceView.
///
/// This example demonstrates how to use the native view approach for better
/// performance on Android. The camera preview is rendered directly using a
/// native SurfaceView below the transparent Flutter layer.
///
/// Note: This example only works on Android. On other platforms, it shows
/// a message indicating that native view is not supported.
class MobileScannerNativeViewExample extends StatefulWidget {
  /// Creates a [MobileScannerNativeViewExample].
  const MobileScannerNativeViewExample({super.key});

  @override
  State<MobileScannerNativeViewExample> createState() =>
      _MobileScannerNativeViewExampleState();
}

class _MobileScannerNativeViewExampleState
    extends State<MobileScannerNativeViewExample> {
  Barcode? _barcode;

  final MobileScannerController controller = MobileScannerController(
    // cameraResolution:
    //     WidgetsBinding.instance.platformDispatcher.views.first.physicalSize +
    //     Offset(0, 0),
  );

  @override
  void dispose() {
    unawaited(controller.dispose());
    super.dispose();
  }

  Widget _barcodePreview(Barcode? value) {
    if (value == null) {
      return const Text(
        'Scan something!',
        overflow: TextOverflow.fade,
        style: TextStyle(color: Colors.white),
      );
    }

    return Text(
      value.displayValue ?? 'No display value.',
      overflow: TextOverflow.fade,
      style: const TextStyle(color: Colors.white),
    );
  }

  void _handleBarcode(BarcodeCapture barcodes) {
    if (mounted) {
      setState(() {
        _barcode = barcodes.barcodes.firstOrNull;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // Only show native view on Android
    if (!Platform.isAndroid) {
      return Scaffold(
        appBar: AppBar(title: const Text('Native View Scanner')),
        body: const Center(
          child: Text(
            'Native view is only supported on Android.\n'
            'On other platforms, use the regular MobileScanner widget.',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }

    return Scaffold(
      backgroundColor: Colors.transparent,
      extendBodyBehindAppBar: true,
      body: Stack(
        children: [
          // Native view area - MobileScanner automatically uses native view on Android
          Positioned.fill(
            child: MobileScanner(
              onDetect: _handleBarcode,
              controller: controller,
            ),
          ),
          // Overlay UI on top of the native view
          Align(
            alignment: Alignment.bottomCenter,
            child: Container(
              alignment: Alignment.bottomCenter,
              height: 100,
              margin: const EdgeInsets.only(bottom: 32),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color.fromRGBO(0, 0, 0, 0.7),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  // Toggle torch button
                  ValueListenableBuilder(
                    valueListenable: controller,
                    builder: (context, state, child) {
                      final IconData torchIcon = switch (state.torchState) {
                        TorchState.on => Icons.flash_on,
                        TorchState.off => Icons.flash_off,
                        TorchState.auto => Icons.flash_auto,
                        TorchState.unavailable => Icons.no_flash,
                      };
                      return IconButton(
                        color: Colors.white,
                        iconSize: 32,
                        icon: Icon(torchIcon),
                        onPressed:
                            state.torchState == TorchState.unavailable
                                ? null
                                : controller.toggleTorch,
                      );
                    },
                  ),
                  // Barcode preview
                  Expanded(child: Center(child: _barcodePreview(_barcode))),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
