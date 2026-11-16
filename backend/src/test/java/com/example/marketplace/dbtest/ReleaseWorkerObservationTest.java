package com.example.marketplace.dbtest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseWorkerObservationTest {
    private static final String PREFIX="a".repeat(64)+"|sha256:"+"b".repeat(64)+"|2026-11-16T00:00:00.123456789Z|";
    @Test void nativeArgvTemplateHasNoDoubleQuotesOrEnvironmentSelection() {
        assertFalse(ReleaseWorkerObservation.FORMAT.contains("\""));assertFalse(ReleaseWorkerObservation.FORMAT.contains(".Env"));
        assertTrue(ReleaseWorkerObservation.FORMAT.contains("`marketplace.p12d.run`"));
        assertEquals(8,ReleaseWorkerObservation.FORMAT.chars().filter(value->value=='|').count());
    }
    @Test void selectedFieldsPreserveFullIncarnationAndStoppedLivenessExactly() {
        var actual=ReleaseWorkerObservation.parse(PREFIX+"false|0|worker|owned-project|owned-run|R1\r\n");
        assertEquals("a".repeat(64),actual.path("id").asText());assertEquals("2026-11-16T00:00:00.123456789Z",actual.path("started").asText());
        assertFalse(actual.path("running").asBoolean());assertEquals(0,actual.path("pid").asLong());assertEquals("owned-run",actual.path("runId").asText());
    }
    @Test void unexpectedFieldsRecordsOrInvalidLivenessAreRejected() {
        String valid=PREFIX+"true|1|worker|owned-project|owned-run|R1";
        assertThrows(AssertionError.class,()->ReleaseWorkerObservation.parse(valid+"|unexpected"));
        assertThrows(AssertionError.class,()->ReleaseWorkerObservation.parse(valid+"\n"+valid));
        assertThrows(AssertionError.class,()->ReleaseWorkerObservation.parse(valid.replace("|true|","|unknown|")));
        assertThrows(AssertionError.class,()->ReleaseWorkerObservation.parse(valid.replace("|1|","|one|")));
    }
}
