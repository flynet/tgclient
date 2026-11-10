package com.digitrooms.tgclient;

import it.tdlight.client.QrCodeTerminal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one thing that silently breaks QR login: tdlight-java calls zxing to render
 * the code but does not depend on it. Without com.google.zxing:core on the classpath the
 * library prints "QR code library is missing" and only the raw link survives.
 */
class QrLoginTest {

    @Test
    void rendersAnAsciiQrCode() {
        String qr = QrCodeTerminal.getQr("tg://login?token=AQAAAAExampleTokenForRenderingOnly");

        assertTrue(qr != null && !qr.isBlank(), "zxing produced no QR code");
        long lines = qr.lines().count();
        assertTrue(lines >= 20, "QR looks too small to scan: " + lines + " lines");
        assertTrue(qr.chars().anyMatch(c -> c == '█' || c == '▀' || c == '▄' || c == '#'),
                "QR does not contain block characters, rendering changed");
    }
}
