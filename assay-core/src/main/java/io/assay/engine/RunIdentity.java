package io.assay.engine;

import io.assay.json.Json;
import io.assay.model.EvalCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Stable case-identity helpers used by resume, dataset hashing, and run ids. */
final class RunIdentity {

    private RunIdentity() {
    }

    /**
     * Hashes each case so resume can prove the dataset did not change between the two runs.
     *
     * <p>Duplicate ids are rejected here rather than silently overwriting, because a duplicate would
     * make the run's case count disagree with the number of distinct cases it evaluated.
     */
    static Map<String, String> caseManifest(List<EvalCase> cases) {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("evaluation suite must not be empty");
        }
        Map<String, String> manifest = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            String caseId = evalCase.caseId().trim();
            if (caseId.isEmpty()) {
                throw new IllegalArgumentException("evaluation case requires a non-empty case_id");
            }
            if (manifest.containsKey(caseId)) {
                throw new IllegalArgumentException("evaluation suite contains duplicate case_id: " + caseId);
            }
            byte[] canonical = Json.canonical(evalCase.toJson()).getBytes(StandardCharsets.UTF_8);
            manifest.put(caseId, sha256Hex(canonical));
        }
        return manifest;
    }

    /** Order-independent hash of the case set, so two runs can prove they saw the same dataset. */
    static String datasetHash(Map<String, String> caseManifest) {
        StringBuilder material = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(caseManifest).entrySet()) {
            material.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sha256Hex(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(Character.forDigit((item >> 4) & 0xF, 16));
                builder.append(Character.forDigit(item & 0xF, 16));
            }
            return builder.toString().toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required but unavailable", error);
        }
    }

    static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
