import 'dart:async';
import 'package:flutter/services.dart';

class VivokaEvent {
  final String type; // command | speech | error | status
  final String? text;

  VivokaEvent(this.type, this.text);

  factory VivokaEvent.fromMap(Map<dynamic, dynamic> m) {
    return VivokaEvent(
      (m['type'] as String?) ?? 'status',
      m['text'] as String?,
    );
  }
}

class VivokaSdkFlutter {
  static const MethodChannel _mc = MethodChannel('vivoka_sdk_flutter/methods');
  static const EventChannel _ec = EventChannel('vivoka_sdk_flutter/events');

  static Stream<VivokaEvent>? _stream;

  static Stream<VivokaEvent> events() {
    _stream ??= _ec.receiveBroadcastStream().map((e) {
      return VivokaEvent.fromMap(e as Map);
    });
    return _stream!;
  }

  static Future<void> init() => _mc.invokeMethod('init');
  static Future<void> stopVivoka() => _mc.invokeMethod('stopVivoka');
  static Future<void> resumeVivoka() => _mc.invokeMethod('resumeVivoka');
  static Future<void> release() => _mc.invokeMethod('release');

  static Future<void> setCallMode(bool inCall) =>
      _mc.invokeMethod('setCallMode', inCall);

  static Future<void> toggleTorch(bool enabled) =>
      _mc.invokeMethod('toggleTorch', enabled);}
