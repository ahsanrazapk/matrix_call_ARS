import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_zxing/flutter_zxing.dart';
import 'package:camera_platform_interface/camera_platform_interface.dart';
import 'package:matrix_call/voip/vivoka_sdk.dart';

import '../command_mixin.dart';
import '../main.dart';

class BarcodeScanner extends StatefulWidget {
  static const route = '/barcodeScanner';

  const BarcodeScanner({super.key});

  @override
  State<BarcodeScanner> createState() => BarcodeScannerState();
}

class BarcodeScannerState extends State<BarcodeScanner> with VivokaRouteCommands {
  CameraController? cameraController;
  double currentZoom = 1.0;
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        elevation: 0,
        scrolledUnderElevation: 0,
        titleSpacing: 3,
        iconTheme: IconThemeData(color: Colors.white),
        backgroundColor: Colors.transparent,
        title: const Text('Back', style: TextStyle(color: Colors.white)),
      ),
      body: RotatedBox(quarterTurns: 3, child: _buildScanner()),
    );
  }

  Widget _buildScanner() {
    return ReaderWidget(
      onScan: onSuccess,
      onScanFailure: (c) => c.error?.isNotEmpty == true ? onError(c.error ?? 'Code is not detected') : null,
      onControllerCreated: _onControllerCreated,
      resolution: ResolutionPreset.high,
      lensDirection: CameraLensDirection.back,
      codeFormat: Format.any,
      showGallery: false,
      cropPercent: 0.7,
      showFlashlight: false,
      showToggleCamera: false,
      scanDelaySuccess: Duration(seconds: 8),
      toggleCameraIcon: const Icon(Icons.camera_alt),

      actionButtonsBackgroundBorderRadius: BorderRadius.circular(10),
    );
  }

  void _onControllerCreated(CameraController? c, Exception? error) {
    if (error != null) {
      onError('Error: $error');
    }
    setState(() {
      cameraController = c;
    });
  }

  void onError(String error) {
    final nav = appNavigatorKey.currentState;
    if (nav != null) {
      nav.pop(null);
    }
  }

  bool _handled = false;

  void onSuccess(Code result) async {
    if (_handled) return;

    if (result.isValid) {
      _handled = true;

      if (!kIsWeb) {
        await HapticFeedback.heavyImpact();
        await HapticFeedback.vibrate();
        await Future.delayed(const Duration(milliseconds: 80));
      }

      final nav = appNavigatorKey.currentState;
      if (nav != null) {
        nav.pop(result.text);
      }
    }
  }

  @override
  void dispose() {
    super.dispose();
    cameraController = null;
  }

  @override
  bool onVivokaCommand(String cmd) {
    if (cmd == 'back') {
      appNavigatorKey.currentState?.pop();
      return true;
    }
    if (cmd case 'flash on' || 'torch on' || 'light on') {
      VivokaSdkFlutter.toggleTorch(true);
      return true;
    }
    if (cmd case 'flash off' || 'torch off' || 'light off') {
      VivokaSdkFlutter.toggleTorch(false);
      return true;
    }
    if (cmd == 'zoom in') {
      if (cameraController != null && cameraController!.value.isInitialized) {
        zoomIn();
      }
      return true;
    }
    if (cmd == 'zoom out') {
      if (cameraController != null && cameraController!.value.isInitialized) {
        zoomOut();
      }
      return true;
    }
    return false;
  }

  Future<void> zoomIn() async {
    final maxZoom = await cameraController!.getMaxZoomLevel();
    currentZoom = (currentZoom + 0.5).clamp(1.0, maxZoom);
    await cameraController!.setZoomLevel(currentZoom);
  }

  Future<void> zoomOut() async {
    final minZoom = await cameraController!.getMinZoomLevel();
    currentZoom = (currentZoom - 0.5).clamp(minZoom, 10.0);

    await cameraController!.setZoomLevel(currentZoom);
  }
}
