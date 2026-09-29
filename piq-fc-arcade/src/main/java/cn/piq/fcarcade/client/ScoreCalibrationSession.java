package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ScoreCalibrationSession {
    static final String TARGET_ROM_SHA256 =
            "BB6029AF4DFD6404772DC8C788113BA74D45B52FD8F120DDB7A607C9ECF45922";
    private static final int MAX_REPORT_CANDIDATES = 512;
    private static final int MAX_CHANGED_ADDRESSES = 64;
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final List<Sample> samples = new ArrayList<>();

    int capture(NesCore core, int visibleScore) {
        byte[] ram = new byte[NesCore.CPU_RAM_BYTES];
        core.copyCpuRam(ram);
        addSample(visibleScore, ram);
        return samples.size();
    }

    void addSample(int visibleScore, byte[] ram) {
        if (visibleScore < 0) {
            throw new IllegalArgumentException("分数不能小于 0");
        }
        if (ram.length != NesCore.CPU_RAM_BYTES) {
            throw new IllegalArgumentException("CPU RAM 快照必须为 2048 字节");
        }
        samples.add(new Sample(visibleScore, ram.clone()));
    }

    int sampleCount() {
        return samples.size();
    }

    FinishResult finish(Path logDirectory, String romSha256) throws IOException {
        if (samples.size() < 3) {
            throw new IllegalStateException("至少需要标记 3 次不同阶段的分数");
        }
        if (samples.stream().map(Sample::visibleScore).distinct().count() < 2) {
            throw new IllegalStateException("至少需要 2 个不同的可见分数");
        }

        Analysis analysis = analyze(samples);
        Files.createDirectories(logDirectory);
        Path report = logDirectory.resolve(
                "piq-fc-scorecal-" + FILE_TIME.format(LocalDateTime.now()) + ".txt");
        Files.writeString(
                report,
                buildReport(romSha256, analysis),
                StandardCharsets.UTF_8);
        return new FinishResult(
                report.toAbsolutePath(),
                samples.size(),
                analysis.candidates().size());
    }

    static Analysis analyze(List<Sample> samples) {
        List<String> candidates = new ArrayList<>();
        int[] divisors = {1, 10, 100};
        for (int divisor : divisors) {
            if (!allScoresDivisibleBy(samples, divisor)) continue;
            scanByteValues(samples, divisor, candidates);
            scanMultiByteValues(samples, divisor, candidates);
            scanDigitArrays(samples, divisor, candidates);
        }

        List<ChangedAddress> changed = new ArrayList<>();
        for (int address = 0; address < NesCore.CPU_RAM_BYTES; address++) {
            int changes = 0;
            for (int sample = 1; sample < samples.size(); sample++) {
                if (samples.get(sample - 1).unsigned(address)
                        != samples.get(sample).unsigned(address)) {
                    changes++;
                }
            }
            if (changes > 0) changed.add(new ChangedAddress(address, changes));
        }
        changed.sort(Comparator.comparingInt(ChangedAddress::changes)
                .reversed()
                .thenComparingInt(ChangedAddress::address));
        return new Analysis(List.copyOf(candidates), List.copyOf(changed));
    }

    private static void scanByteValues(
            List<Sample> samples,
            int divisor,
            List<String> candidates
    ) {
        for (int address = 0; address < NesCore.CPU_RAM_BYTES; address++) {
            int currentAddress = address;
            if (matches(samples, divisor, sample -> sample.unsigned(currentAddress))) {
                addCandidate(candidates, "U8", divisor, address, 1);
            }
            if (matches(samples, divisor, sample -> bcd(sample.unsigned(currentAddress)))) {
                addCandidate(candidates, "BCD8", divisor, address, 1);
            }
        }
    }

    private static void scanMultiByteValues(
            List<Sample> samples,
            int divisor,
            List<String> candidates
    ) {
        for (int address = 0; address < NesCore.CPU_RAM_BYTES - 1; address++) {
            int currentAddress = address;
            if (matches(samples, divisor, sample ->
                    sample.unsigned(currentAddress)
                            | sample.unsigned(currentAddress + 1) << 8)) {
                addCandidate(candidates, "LE16", divisor, address, 2);
            }
            if (matches(samples, divisor, sample ->
                    sample.unsigned(currentAddress) << 8
                            | sample.unsigned(currentAddress + 1))) {
                addCandidate(candidates, "BE16", divisor, address, 2);
            }
            if (matches(samples, divisor, sample ->
                    packedBcd(sample, currentAddress, 2, true))) {
                addCandidate(candidates, "BCD16_LE", divisor, address, 2);
            }
            if (matches(samples, divisor, sample ->
                    packedBcd(sample, currentAddress, 2, false))) {
                addCandidate(candidates, "BCD16_BE", divisor, address, 2);
            }
        }
        for (int address = 0; address < NesCore.CPU_RAM_BYTES - 2; address++) {
            int currentAddress = address;
            if (matches(samples, divisor, sample ->
                    sample.unsigned(currentAddress)
                            | sample.unsigned(currentAddress + 1) << 8
                            | sample.unsigned(currentAddress + 2) << 16)) {
                addCandidate(candidates, "LE24", divisor, address, 3);
            }
            if (matches(samples, divisor, sample ->
                    sample.unsigned(currentAddress) << 16
                            | sample.unsigned(currentAddress + 1) << 8
                            | sample.unsigned(currentAddress + 2))) {
                addCandidate(candidates, "BE24", divisor, address, 3);
            }
            if (matches(samples, divisor, sample ->
                    packedBcd(sample, currentAddress, 3, true))) {
                addCandidate(candidates, "BCD24_LE", divisor, address, 3);
            }
            if (matches(samples, divisor, sample ->
                    packedBcd(sample, currentAddress, 3, false))) {
                addCandidate(candidates, "BCD24_BE", divisor, address, 3);
            }
        }
        for (int address = 0; address < NesCore.CPU_RAM_BYTES - 3; address++) {
            int currentAddress = address;
            if (matches(samples, divisor, sample ->
                    packedBcd(sample, currentAddress, 4, true))) {
                addCandidate(candidates, "BCD32_LE", divisor, address, 4);
            }
            if (matches(samples, divisor, sample ->
                    packedBcd(sample, currentAddress, 4, false))) {
                addCandidate(candidates, "BCD32_BE", divisor, address, 4);
            }
        }
    }

    private static int packedBcd(
            Sample sample,
            int address,
            int length,
            boolean littleEndian
    ) {
        int value = 0;
        for (int index = 0; index < length; index++) {
            int sourceIndex = littleEndian ? length - index - 1 : index;
            int pair = bcd(sample.unsigned(address + sourceIndex));
            if (pair < 0) return -1;
            value = value * 100 + pair;
        }
        return value;
    }

    private static void scanDigitArrays(
            List<Sample> samples,
            int divisor,
            List<String> candidates
    ) {
        for (int length = 2; length <= 6; length++) {
            for (int address = 0;
                 address <= NesCore.CPU_RAM_BYTES - length;
                 address++) {
                int currentAddress = address;
                int currentLength = length;
                if (matches(samples, divisor, sample -> decimalDigits(
                        sample, currentAddress, currentLength, false, false))) {
                    addCandidate(candidates, "DIGITS", divisor, address, length);
                }
                if (matches(samples, divisor, sample -> decimalDigits(
                        sample, currentAddress, currentLength, true, false))) {
                    addCandidate(candidates, "DIGITS_REVERSED", divisor, address, length);
                }
                if (matches(samples, divisor, sample -> decimalDigits(
                        sample, currentAddress, currentLength, false, true))) {
                    addCandidate(candidates, "ASCII_DIGITS", divisor, address, length);
                }
                if (matches(samples, divisor, sample -> decimalDigits(
                        sample, currentAddress, currentLength, true, true))) {
                    addCandidate(candidates, "ASCII_DIGITS_REVERSED", divisor, address, length);
                }
            }
        }
    }

    private static int decimalDigits(
            Sample sample,
            int address,
            int length,
            boolean reversed,
            boolean ascii
    ) {
        int value = 0;
        for (int index = 0; index < length; index++) {
            int sourceIndex = reversed ? length - index - 1 : index;
            int digit = sample.unsigned(address + sourceIndex) - (ascii ? '0' : 0);
            if (digit < 0 || digit > 9) return -1;
            value = value * 10 + digit;
        }
        return value;
    }

    private static int bcd(int value) {
        int high = value >>> 4;
        int low = value & 0x0F;
        return high <= 9 && low <= 9 ? high * 10 + low : -1;
    }

    private static boolean matches(
            List<Sample> samples,
            int divisor,
            Decoder decoder
    ) {
        for (Sample sample : samples) {
            int decoded = decoder.decode(sample);
            if (decoded < 0 || decoded != sample.visibleScore() / divisor) {
                return false;
            }
        }
        return true;
    }

    private static boolean allScoresDivisibleBy(List<Sample> samples, int divisor) {
        return samples.stream().allMatch(sample -> sample.visibleScore() % divisor == 0);
    }

    private static void addCandidate(
            List<String> candidates,
            String encoding,
            int divisor,
            int address,
            int length
    ) {
        if (candidates.size() >= MAX_REPORT_CANDIDATES) return;
        candidates.add(String.format(
                Locale.ROOT,
                "%s divisor=%d address=$%04X length=%d",
                encoding,
                divisor,
                address,
                length));
    }

    private String buildReport(String romSha256, Analysis analysis) {
        StringBuilder report = new StringBuilder();
        report.append("Game Console FC score calibration report\n")
                .append("ROM SHA-256: ").append(romSha256).append('\n')
                .append("Samples: ").append(samples.size()).append("\n\n")
                .append("Visible scores:\n");
        for (int index = 0; index < samples.size(); index++) {
            report.append("  #").append(index + 1)
                    .append(": ").append(samples.get(index).visibleScore())
                    .append('\n');
        }

        report.append("\nExact candidates (maximum ")
                .append(MAX_REPORT_CANDIDATES).append("):\n");
        if (analysis.candidates().isEmpty()) {
            report.append("  <none>\n");
        } else {
            analysis.candidates().forEach(candidate ->
                    report.append("  ").append(candidate).append('\n'));
        }

        report.append("\nMost frequently changed addresses:\n");
        analysis.changedAddresses().stream()
                .limit(MAX_CHANGED_ADDRESSES)
                .forEach(changed -> {
                    report.append(String.format(
                            Locale.ROOT,
                            "  $%04X changes=%d values=",
                            changed.address(),
                            changed.changes()));
                    for (Sample sample : samples) {
                        report.append(String.format(
                                Locale.ROOT,
                                "%02X ",
                                sample.unsigned(changed.address())));
                    }
                    report.append('\n');
                });

        report.append("\nRaw CPU RAM snapshots (2048 bytes each):\n");
        for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
            Sample sample = samples.get(sampleIndex);
            report.append("\n#").append(sampleIndex + 1)
                    .append(" visibleScore=").append(sample.visibleScore()).append('\n');
            for (int address = 0; address < NesCore.CPU_RAM_BYTES; address += 16) {
                report.append(String.format(Locale.ROOT, "%04X: ", address));
                for (int offset = 0; offset < 16; offset++) {
                    report.append(String.format(
                            Locale.ROOT,
                            "%02X ",
                            sample.unsigned(address + offset)));
                }
                report.append('\n');
            }
        }
        return report.toString();
    }

    record Sample(int visibleScore, byte[] ram) {
        Sample {
            ram = ram.clone();
        }

        int unsigned(int address) {
            return Byte.toUnsignedInt(ram[address]);
        }
    }

    record ChangedAddress(int address, int changes) {
    }

    record Analysis(List<String> candidates, List<ChangedAddress> changedAddresses) {
    }

    record FinishResult(Path reportPath, int sampleCount, int candidateCount) {
    }

    @FunctionalInterface
    private interface Decoder {
        int decode(Sample sample);
    }
}
