import 'package:card_scanner/card_scanner.dart';
import 'package:flutter/material.dart';

void main() => runApp(const MaterialApp(home: ExamplePage()));

/// Minimal integration example. See the root `poc_card_scanner` app for the
/// engine-comparison (spike) harness.
class ExamplePage extends StatefulWidget {
  const ExamplePage({super.key});

  @override
  State<ExamplePage> createState() => _ExamplePageState();
}

class _ExamplePageState extends State<ExamplePage> {
  final _scanner = CardScanner();
  String _status = 'Tap scan';

  Future<void> _scan(AndroidScanEngine engine) async {
    try {
      final result = await _scanner.scan(CardScanOptions(
        androidEngine: engine,
        timeout: const Duration(seconds: 60),
      ));
      setState(() => _status = result == null
          ? 'Cancelled'
          : '${result.maskedNumber}  ${result.expiry ?? ''}  '
              '${result.holderName ?? ''}\n${result.brand.label} via '
              '${result.engine.label} in ${result.elapsed.inMilliseconds} ms');
    } on CardScanException catch (e) {
      setState(() => _status = 'Error: ${e.code.name} ${e.message ?? ''}');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('card_scanner example')),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_status, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => _scan(AndroidScanEngine.native),
              child: const Text('Scan card'),
            ),
            TextButton(
              onPressed: () => _scan(AndroidScanEngine.googlePay),
              child: const Text('Scan with Google Pay (Android)'),
            ),
          ],
        ),
      ),
    );
  }
}
