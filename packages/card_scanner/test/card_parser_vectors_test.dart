import 'dart:convert';
import 'dart:io';

import 'package:card_scanner/card_scanner.dart';
import 'package:flutter_test/flutter_test.dart';

/// Same vectors are run by the Swift (XCTest) and Kotlin (JUnit) parsers.
void main() {
  final json = jsonDecode(
    File('test_vectors/card_parser_vectors.json').readAsStringSync(),
  ) as Map<String, dynamic>;
  final parser = CardParser(
    clock: () => DateTime.parse(json['referenceDate'] as String),
  );

  for (final v in (json['vectors'] as List).cast<Map<String, dynamic>>()) {
    test(v['description'], () {
      final lines = (v['lines'] as List).cast<String>();
      final expected = v['expected'] as Map<String, dynamic>;
      final result = parser.parse(lines);
      expect(result.number, expected['number'], reason: 'number');
      final expiry = result.expiry;
      expect(
        expiry == null
            ? null
            : '${expiry.month.toString().padLeft(2, '0')}/${expiry.year}',
        expected['expiry'],
        reason: 'expiry',
      );
      expect(result.holderName, expected['holderName'], reason: 'holderName');
    });
  }
}
