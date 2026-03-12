import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:matrix/matrix.dart';
import 'package:matrix_call/voip/scan_to_proced.dart';
import 'package:matrix_call/voip/vivoka_sdk.dart';
import 'package:matrix_call/voip/voip_service.dart';
import 'package:provider/provider.dart';

import '../main.dart';

class LoginPage extends StatefulWidget {
  static const route = '/login';

  const LoginPage({super.key});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  StreamSubscription? _subscription;

  final user = TextEditingController();
  final pass = TextEditingController();

  FocusNode userFocus = FocusNode();
  FocusNode passwordFocus = FocusNode();

  bool loading = false;

  Future<void> _login() async {
    setState(() => loading = true);
    try {
      final client = context.read<Client>();
      await client.checkHomeserver(Uri.https('matrix.dropslab.com', ''));

      await client.login(
        LoginType.mLoginPassword,
        password: pass.text,
        identifier: AuthenticationUserIdentifier(user: user.text.trim()),
      );

      if (!mounted) return;
      await context.read<VoipService>().start();

      if (!mounted) return;
      final nav = appNavigatorKey.currentState;
      if (nav != null) {
        nav.pushNamedAndRemoveUntil(ScanToProceedScreen.route, (_) => false);
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      setState(() => loading = false);
    }
  }

  @override
  void initState() {
    super.initState();

   _subscription = VivokaSdkFlutter.events().listen((e) {
      if (e.type == 'command') {
        if (e.text == 'user name') {
          userFocus.requestFocus();
        } else if (e.text == 'password') {
          passwordFocus.requestFocus();
        } else if (e.text case 'login' || 'log in') {
          FocusManager.instance.primaryFocus?.unfocus();
          _login();
        } else if (e.text == 'done') {
          FocusManager.instance.primaryFocus?.unfocus();
        }
       else if (e.text case 'flash on' || 'torch on' || 'light on') {
          VivokaSdkFlutter.toggleTorch(true);
        }
       else if (e.text case 'flash off' || 'torch off' || 'light off') {
          VivokaSdkFlutter.toggleTorch(false);
        }
      }
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    SchedulerBinding.instance.addPostFrameCallback((_){
      final client = context.read<Client>();
      final nav = appNavigatorKey.currentState;
      if (nav != null && client.isLogged()) {
        nav.pushNamedAndRemoveUntil(ScanToProceedScreen.route,(_)=> false);
      }
    });
  }

  @override
  void dispose() {
   _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Login')),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              TextField(
                controller: user,
                focusNode: userFocus,
                readOnly: loading,
                decoration: const InputDecoration(border: OutlineInputBorder(), labelText: 'Username'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: pass,
                readOnly: loading,
                focusNode: passwordFocus,
                obscureText: true,
                decoration: const InputDecoration(border: OutlineInputBorder(), labelText: 'Password'),
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  onPressed: loading ? null : _login,
                  child: loading ? const LinearProgressIndicator() : const Text('Login'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
