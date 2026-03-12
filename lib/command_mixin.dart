import 'package:flutter/widgets.dart';
import '../main.dart';
import 'dispatccher/command_dispatcher.dart';

mixin VivokaRouteCommands<T extends StatefulWidget> on State<T> implements RouteAware {
  Object? _token;

  /// screen-specific handler implement karo
  bool onVivokaCommand(String cmd);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route is PageRoute) {
      routeObserver.subscribe(this, route);
    }
  }

  void _enable() {
    if (_token != null) return;
    _token = VivokaCommandDispatcher.I.register(onVivokaCommand,
        debugName: T.toString());
  }

  void _disable() {
    final t = _token;
    if (t != null) {
      VivokaCommandDispatcher.I.unregister(t);
      _token = null;
    }
  }

  @override
  void didPush() => _enable();

  @override
  void didPop() => _disable();

  @override
  void didPushNext() => _disable(); // another screen on top

  @override
  void didPopNext() => _enable();   // back to this screen

  @override
  void dispose() {
    _disable();
    routeObserver.unsubscribe(this);
    super.dispose();
  }
}
