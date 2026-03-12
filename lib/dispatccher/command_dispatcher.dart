import 'dart:async';
import '../voip/vivoka_sdk.dart';

typedef VivokaCommandHandler = bool Function(String cmd);

class VivokaCommandDispatcher {
  VivokaCommandDispatcher._();
  static final VivokaCommandDispatcher I = VivokaCommandDispatcher._();

  StreamSubscription? _sub;

  // stack => last registered = highest priority (current screen)
  final List<_Entry> _stack = [];

  void ensureStarted() {
    if (_sub != null) return;

    _sub = VivokaSdkFlutter.events().listen((e) {
      if (e.type != 'command') return;
      final cmd = (e.text ?? '').trim().toLowerCase();
      if (cmd.isEmpty) return;

      // top-most handler gets the first chance
      for (var i = _stack.length - 1; i >= 0; i--) {
        final handled = _stack[i].handler(cmd);
        if (handled) break;
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
