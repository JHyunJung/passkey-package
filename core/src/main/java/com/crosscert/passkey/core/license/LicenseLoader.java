package com.crosscert.passkey.core.license;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Reads the license JWS string from the configured filesystem path.
 * Intentionally minimal: file presence + UTF-8 read. JWS parsing and
 * validation belong to LicenseVerifier.
 */
@Component
public class LicenseLoader {

    public String load(Path path) {
        try {
            return Files.readString(path).trim();
        } catch (NoSuchFileException e) {
            throw new LicenseVerificationException(
                    "License file not found at " + path, e);
        } catch (IOException e) {
            throw new LicenseVerificationException(
                    "Failed to read license file at " + path, e);
        }
    }
}
