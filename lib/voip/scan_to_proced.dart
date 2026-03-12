import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:matrix/matrix.dart';
import 'package:matrix_call/voip/vivoka_sdk.dart';
import 'package:matrix_call/voip/voip_service.dart';
import 'package:provider/provider.dart';

import '../command_mixin.dart';
import '../main.dart';
import 'barcode_scanner.dart';
import 'call_screen.dart';
import 'login_screen.dart';

class ScanToProceedScreen extends StatefulWidget {
  static const route = '/scanToProceed';

  const ScanToProceedScreen({super.key});
  @override
  State<ScanToProceedScreen> createState() => _ScanToProceedScreenState();
}

class _ScanToProceedScreenState extends State<ScanToProceedScreen> with VivokaRouteCommands {
  @override
  bool onVivokaCommand(String cmd) {
    if (cmd == 'scan') {
      scanCode();
      return true;
    }
    if (cmd == 'continue') {
      startCall(user.text);
      return true;
    }
    if (cmd == 'logout') {
      _logout();
      return true;
    }
    if (cmd == 'done') {
      FocusManager.instance.primaryFocus?.unfocus();
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
    return false;
  }

  final user = TextEditingController();
  FocusNode userFocus = FocusNode();
  FocusNode passwordFocus = FocusNode();

  bool loading = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    SchedulerBinding.instance.addPostFrameCallback((_) async {
      await context.read<VoipService>().start();
    });
  }

  Future<void> scanCode() async {
    final nav = appNavigatorKey.currentState;
    if (nav != null) {
      final String? code = await nav.pushNamed<String?>(BarcodeScanner.route);
      if (code != null) {
        user.text = code;
        startCall(code);
      }
    }
  }

  bool isValidMatrixId(String? input) {
    if (input == null) return false;
    final regex = RegExp(r'^@[a-zA-Z0-9._=-]+:matrix\.dropslab\.com$');
    return regex.hasMatch(input);
  }

  void startCall(String? code) {
    if (code == null) return;

    if (isValidMatrixId(code)) {
      final client = context.read<Client>();
      Room room = client.rooms.firstWhere(
        (e) => e.directChatMatrixID == code,
        orElse: () => Room(id: '-1', client: client),
      );
      if (room.id != '-1') {
        _startVideoCall(room);
      }
    }
  }

  Future<void> _startVideoCall(Room room) async {
    final other = room.directChatMatrixID;
    final nav = appNavigatorKey.currentState;
    if (!mounted) return;
    await context.read<VoipService>().callUser(roomId: room.id, userId: other ?? '', type: CallType.kVideo);

    if (!mounted) return;
    if (nav == null) return;
    nav.pushNamed(CallScreen.route, arguments: room);
  }

  Future<void> _logout() async {
    FocusManager.instance.primaryFocus?.unfocus();
    final client = context.read<Client>();
    await context.read<VoipService>().stop();
    await client.logout();

    if (!mounted) return;
    final nav = appNavigatorKey.currentState;
    if (nav != null) {
      nav.pushNamedAndRemoveUntil(LoginPage.route, (_) => false);
    }
  }

  @override
  void dispose() {
    //  _subscription?.cancel();
    super.dispose();
  }



  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Scan to Proceed'),
        actions: [
          IconButton(
            onPressed: _logout,
            icon: const Icon(Icons.logout, color: Colors.grey),
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          children: [
            Spacer(),
            TextField(
              controller: user,
              focusNode: userFocus,
              readOnly: loading,
              decoration: const InputDecoration(border: OutlineInputBorder(), labelText: 'Scanned Code'),
            ),
            Spacer(),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              spacing: 10,
              children: [
                ElevatedButton(
                  onPressed: () => startCall(user.text),
                  child: loading ? const CircularProgressIndicator() : const Text('Continue'),
                ),
                ElevatedButton(onPressed: scanCode, child: const Text('Scan')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
