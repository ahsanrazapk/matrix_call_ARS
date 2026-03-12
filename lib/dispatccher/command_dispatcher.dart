import 'dart:async';
import '../voip/vivoka_sdk.dart';

typedef VivokaCommandHandler = bool Function(String cmd);
typedef VivokaGlobalHandler = void Function(String cmd);

class VivokaCommandDispatcher {
  VivokaCommandDispatcher._();
  static final VivokaCommandDispatcher I = VivokaCommandDispatcher._();

  StreamSubscription? _sub;

  final List<_Entry> _stack = [];

  VivokaGlobalHandler? _globalFallback;

  Object setGlobalFallback(VivokaGlobalHandler? handler) {
    ensureStarted();
    _globalFallback = handler;
    return Object();
  }

  void ensureStarted() {
    if (_sub != null) return;

    _sub = VivokaSdkFlutter.events().listen((e) {
      if (e.type != 'command') return;
      final cmd = (e.text ?? '').trim().toLowerCase();
      if (cmd.isEmpty) return;

      bool handled = false;
      for (var i = _stack.length - 1; i >= 0; i--) {
        if (_stack[i].handler(cmd)) {
          handled = true;
          break;
        }
      }

      if (!handled) {
        _globalFallback?.call(cmd);
      }
    });
  }

  Object register(VivokaCommandHandler handler, {String? debugName}) {
    ensureStarted();
    final token = Object();
    _stack.add(_Entry(token, handler, debugName));
    return token;
  }

  void unregister(Object token) {
    _stack.removeWhere((e) => identical(e.token, token));
  }

  Future<void> stopAll() async {
    _stack.clear();
    _globalFallback = null;
    await _sub?.cancel();
    _sub = null;
  }
}

class _Entry {
  final Object token;
  final VivokaCommandHandler handler;
  final String? debugName;
  _Entry(this.token, this.handler, this.debugName);
}
