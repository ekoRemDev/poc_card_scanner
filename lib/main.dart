import 'dart:io';

import 'package:card_scanner/card_scanner.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'baseline/flutter_scanner_page.dart';
import 'scan_record.dart';

void main() => runApp(const CardScannerSpikeApp());

class CardScannerSpikeApp extends StatelessWidget {
  const CardScannerSpikeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Card Scanner Spike',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: const AddCardPage(),
    );
  }
}

/// "Add card" form + spike harness: scan the same cards with each engine and
/// compare time-to-lock / fields found. History is in-memory only.
class AddCardPage extends StatefulWidget {
  const AddCardPage({super.key});

  @override
  State<AddCardPage> createState() => _AddCardPageState();
}

class _AddCardPageState extends State<AddCardPage> {
  final _scanner = CardScanner();
  final _number = TextEditingController();
  final _expiry = TextEditingController();
  final _name = TextEditingController();
  final _history = <ScanRecord>[];
  ScannerAvailability? _availability;
  bool _debugOcr = false;

  @override
  void initState() {
    super.initState();
    for (final c in [_number, _expiry, _name]) {
      c.addListener(() => setState(() {}));
    }
    _scanner.availability().then(
      (a) => mounted ? setState(() => _availability = a) : null,
      onError: (_) {},
    );
  }

  @override
  void dispose() {
    _number.dispose();
    _expiry.dispose();
    _name.dispose();
    super.dispose();
  }

  void _apply(ScanRecord? record) {
    if (record == null) return;
    setState(() {
      _history.insert(0, record);
      _number.text = CardScanResult.formatPan(record.number);
      _expiry.text = record.expiry?.formatted ?? '';
      _name.text = record.holderName ?? '';
    });
  }

  Future<void> _scanSdk(AndroidScanEngine engine) async {
    try {
      final result = await _scanner.scan(
        CardScanOptions(
          androidEngine: engine,
          // In the spike we want to know when Google Pay is unavailable,
          // not silently fall back.
          fallbackToNative: false,
          timeout: const Duration(seconds: 60),
          debugShowRecognizedText: _debugOcr,
        ),
      );
      if (result != null) _apply(ScanRecord.fromSdk(result));
    } on CardScanException catch (e) {
      _snack('${e.code.name}: ${e.message ?? ''}');
    }
  }

  Future<void> _scanBaseline() async =>
      _apply(await FlutterScannerPage.open(context));

  void _snack(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> _copyReport() async {
    final csv = [
      ScanRecord.csvHeader,
      ..._history.reversed.map((r) => r.toCsvRow()),
    ].join('\n');
    await Clipboard.setData(ClipboardData(text: csv));
    _snack('Copied ${_history.length} rows (no card numbers)');
  }

  @override
  Widget build(BuildContext context) {
    final digits = _number.text.replaceAll(' ', '');
    final brand = CardParser.detectBrand(digits);
    final numberValid = digits.isNotEmpty && CardParser.luhnValid(digits);
    final gpay = _availability?.googlePay;

    return Scaffold(
      appBar: AppBar(title: const Text('Add card · scanner spike')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _CardPreview(
            number: _number.text,
            expiry: _expiry.text,
            name: _name.text,
            brand: brand,
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () => _scanSdk(AndroidScanEngine.native),
            icon: const Icon(Icons.document_scanner_outlined),
            label: Text(
              Platform.isIOS
                  ? 'Scan card · SDK (Apple Vision)'
                  : 'Scan card · SDK (CameraX + ML Kit)',
            ),
          ),
          if (Platform.isAndroid) ...[
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: () => _scanSdk(AndroidScanEngine.googlePay),
              icon: const Icon(Icons.android),
              label: const Text('Scan card · SDK (Google Pay)'),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                switch (gpay) {
                  null => 'Checking Google Pay card recognition…',
                  true =>
                    'Google Pay card recognition: available on this device',
                  false => 'Google Pay card recognition: NOT available on this device/region',
                },
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall
                    ?.copyWith(color: gpay == false ? Colors.red : null),
              ),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: () => _scanSdk(AndroidScanEngine.doubango),
              icon: const Icon(Icons.science_outlined),
              label: const Text('Scan card · SDK (Doubango, evaluation)'),
            ),
          ],
          SwitchListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('Show raw OCR text while scanning (debug)'),
            subtitle: const Text(
              'SDK scanners only. Shows full digits on screen.',
            ),
            value: _debugOcr,
            onChanged: (v) => setState(() => _debugOcr = v),
          ),
          TextButton.icon(
            onPressed: _scanBaseline,
            icon: const Icon(Icons.compare_arrows),
            label: const Text('Scan card · Flutter baseline (old POC)'),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _number,
            keyboardType: TextInputType.number,
            autofillHints: const [AutofillHints.creditCardNumber],
            decoration: InputDecoration(
              labelText: 'Card number',
              border: const OutlineInputBorder(),
              suffixIcon: digits.isEmpty
                  ? null
                  : Icon(
                      numberValid ? Icons.check_circle : Icons.error_outline,
                      color: numberValid ? Colors.green : Colors.red,
                    ),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _expiry,
                  keyboardType: TextInputType.datetime,
                  autofillHints: const [AutofillHints.creditCardExpirationDate],
                  decoration: const InputDecoration(
                    labelText: 'Expiry (MM/YY)',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              const Expanded(
                child: TextField(
                  keyboardType: TextInputType.number,
                  obscureText: true,
                  autofillHints: [AutofillHints.creditCardSecurityCode],
                  decoration: InputDecoration(
                    labelText: 'CVV',
                    helperText: 'Never scanned',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _name,
            textCapitalization: TextCapitalization.characters,
            autofillHints: const [AutofillHints.creditCardName],
            decoration: const InputDecoration(
              labelText: 'Cardholder name',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 24),
          Row(
            children: [
              Text(
                'Scan history (${_history.length})',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const Spacer(),
              TextButton.icon(
                onPressed: _history.isEmpty ? null : _copyReport,
                icon: const Icon(Icons.copy, size: 18),
                label: const Text('Copy CSV'),
              ),
            ],
          ),
          for (final r in _history) _HistoryTile(record: r),
        ],
      ),
    );
  }
}

class _HistoryTile extends StatelessWidget {
  const _HistoryTile({required this.record});

  final ScanRecord record;

  @override
  Widget build(BuildContext context) {
    final details = [
      record.brand.label,
      'exp ${record.expiry?.formatted ?? '—'}',
      'name ${record.holderName != null ? '✓' : '—'}',
      '${record.elapsed.inMilliseconds} ms',
      if (record.framesProcessed != null) '${record.framesProcessed} frames',
    ].join(' · ');
    return Card(
      child: ListTile(
        dense: true,
        title: Text(
          record.maskedNumber,
          style: const TextStyle(fontFeatures: [FontFeature.tabularFigures()]),
        ),
        subtitle: Text('${record.engine}\n$details'),
        isThreeLine: true,
      ),
    );
  }
}

class _CardPreview extends StatelessWidget {
  const _CardPreview({
    required this.number,
    required this.expiry,
    required this.name,
    required this.brand,
  });

  final String number;
  final String expiry;
  final String name;
  final CardBrand brand;

  @override
  Widget build(BuildContext context) {
    const mono = TextStyle(
      color: Colors.white,
      fontFeatures: [FontFeature.tabularFigures()],
    );
    return AspectRatio(
      aspectRatio: 85.60 / 53.98,
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          gradient: const LinearGradient(
            colors: [Color(0xFF283593), Color(0xFF5C6BC0)],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.credit_card, color: Colors.white70),
                const Spacer(),
                Text(
                  brand == CardBrand.unknown ? '' : brand.label,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const Spacer(),
            Text(
              number.isEmpty ? '•••• •••• •••• ••••' : number,
              style: mono.copyWith(fontSize: 22, letterSpacing: 2),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: Text(
                    name.isEmpty ? 'CARDHOLDER NAME' : name,
                    overflow: TextOverflow.ellipsis,
                    style: mono.copyWith(color: Colors.white70),
                  ),
                ),
                Text(
                  expiry.isEmpty ? 'MM/YY' : expiry,
                  style: mono.copyWith(color: Colors.white70),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
