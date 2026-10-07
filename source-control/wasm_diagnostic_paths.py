"""Redact user-profile prefixes in WASM diagnostic data without relocating bytes.

This is an explicit release transform, not a runtime loader or save migrator.
Rust source-location strings under a Cargo registry are detected automatically;
explicit reviewed build-workspace prefixes may also be supplied. All other
sections and every byte outside the matched prefixes must remain unchanged.
The resulting module has a NEW digest; callers must update its compatibility
identity and retain incompatible historical saves separately.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

USER_PREFIX = re.compile(rb"[A-Za-z]:[\\/]Users[\\/][^\\/\x00\r\n]{1,100}(?=[\\/]\.cargo[\\/]registry[\\/])")


def varuint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 35, 7):
        if offset >= len(data):
            raise ValueError('Truncated section length')
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError('Invalid section length')


def sections(data: bytes):
    if data[:8] != b'\x00asm\x01\x00\x00\x00':
        raise ValueError('Expected a WebAssembly 1 module')
    offset = 8
    while offset < len(data):
        kind = data[offset]
        size, start = varuint(data, offset + 1)
        end = start + size
        if end > len(data):
            raise ValueError('Truncated section')
        yield kind, start, end
        offset = end


def redact(data: bytes, prefixes: tuple[bytes, ...] = ()) -> tuple[bytes, dict]:
    spans = list(sections(data))
    result = bytearray(data)
    edits = []
    matches = [(m.start(), m.end()) for m in USER_PREFIX.finditer(data)]
    for prefix in prefixes:
        if len(prefix) < 10 or b'\x00' in prefix:
            raise ValueError('Expected an explicit reviewed absolute build prefix')
        for match in re.finditer(re.escape(prefix), data):
            # This utility is not a general binary-path rewriter. Confirm a
            # Rust source suffix, without changing it or exposing it in reports.
            suffix = data[match.end():match.end() + 500].split(b'\x00')[0]
            if not re.search(rb'[\\/][^\x00]*\.rs', suffix):
                raise ValueError('Explicit prefix is not a Rust source location')
            matches.append((match.start(), match.end()))
    matches = sorted(set(matches))
    previous_end = 0
    for match_start, match_end in matches:
        if match_start < previous_end:
            raise ValueError('Overlapping prefix replacements')
        previous_end = match_end
        containers = [(kind, start, end) for kind, start, end in spans
                      if start <= match_start and match_end <= end]
        if len(containers) != 1 or containers[0][0] != 11:
            raise ValueError('Source path is outside a data section; refuse mutation')
        # Equal-length valid ASCII retains all addresses, lengths and offsets.
        replacement = b'/build/' + b'_' * (match_end - match_start - 7)
        assert len(replacement) == match_end - match_start
        result[match_start:match_end] = replacement
        edits.append(dict(offset=match_start, length=len(replacement)))
    if not edits:
        raise ValueError('No eligible user-profile diagnostic paths')
    result = bytes(result)
    assert len(result) == len(data)
    for kind, start, end in spans:
        if kind != 11:
            assert result[start:end] == data[start:end]
    cursor = 0
    for edit in edits:
        assert result[cursor:edit['offset']] == data[cursor:edit['offset']]
        cursor = edit['offset'] + edit['length']
    assert result[cursor:] == data[cursor:]
    assert not USER_PREFIX.search(result)
    return result, dict(bytes=len(data), replacements=edits,
                        originalSha256=hashlib.sha256(data).hexdigest(),
                        sha256=hashlib.sha256(result).hexdigest(),
                        executableSectionsUnchanged=True,
                        saveMigration=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--prefix', action='append', default=[],
                        help='Reviewed absolute build workspace prefix; repeat if necessary')
    args = parser.parse_args()
    result, receipt = redact(args.input.read_bytes(), tuple(p.encode('utf8') for p in args.prefix))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('xb') as stream:
        stream.write(result)
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
