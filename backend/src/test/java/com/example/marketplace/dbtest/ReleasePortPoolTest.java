package com.example.marketplace.dbtest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleasePortPoolTest {
    private static final String ID="11111111-1111-4111-8111-111111111111";
    private static final String OBSERVATION="""
        {"schemaVersion":1,"status":"passed","mode":"live-current","observationId":"11111111-1111-4111-8111-111111111111",
         "runtime":{"accepted":true,"version":"7.6.9","edition":"Core","executable":"C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe"},
         "windows":{"ipv4":{"start":1024,"end":15000},"ipv6":{"start":1024,"end":15000}},
         "dockerHost":{"alreadyRunning":true,"pid":192,"namespace":"net:[4026532283]","observedNamespace":"net:[4026532283]","start":32768,"end":60999}}
        """;

    @Test void derivesInclusivePoolFromAllObservedHostRanges() throws Exception {
        var pool=ReleasePortPool.fromObservation(JsonMapper.builder().build().readTree(OBSERVATION),ID);
        assertEquals(List.of(new ReleasePortPool.Range(20000,32767),new ReleasePortPool.Range(61000,65535)),pool.intervals());
        assertEquals(20000,pool.portAt(0));assertEquals(32767,pool.portAt(12767));
        assertEquals(61000,pool.portAt(12768));assertEquals(65535,pool.portAt(pool.size()-1));
    }
    @Test void unionsOverlappingAdjacentAndClippedRangesWithoutDiscardingSafePorts() {
        var pool=ReleasePortPool.subtract(20000,65535,List.of(new ReleasePortPool.Range(10000,25000),new ReleasePortPool.Range(25001,33000),new ReleasePortPool.Range(32768,60999),new ReleasePortPool.Range(65000,65535)));
        assertEquals(List.of(new ReleasePortPool.Range(61000,64999)),pool.intervals());assertEquals(4000,pool.size());
        assertEquals(List.of(new ReleasePortPool.Range(10,20)),ReleasePortPool.subtract(10,20,List.of()).intervals());
    }
    @Test void malformedMissingNullCoercedAndReversedRangesFailClosed() throws Exception {
        var mapper=JsonMapper.builder().build();
        for(String invalid:List.of(OBSERVATION.replace("\"start\":1024","\"start\":null"),OBSERVATION.replace("\"start\":1024","\"start\":\"1024\""),
                OBSERVATION.replace("\"start\":1024","\"start\":true"),OBSERVATION.replace("\"start\":1024","\"start\":1024.5"),
                OBSERVATION.replace("\"start\":1024","\"start\":0"),OBSERVATION.replace("\"end\":15000","\"end\":65536"),
                OBSERVATION.replace("\"start\":32768","\"start\":62000"),OBSERVATION.replace("\"end\":60999","\"other\":60999"),"{}")) {
            var value=mapper.readTree(invalid);assertThrows(IllegalArgumentException.class,()->ReleasePortPool.fromObservation(value,ID));
        }
    }
    @Test void failedOldOrUnverifiedNamespaceObservationCannotSupplyFallbackRanges() throws Exception {
        var mapper=JsonMapper.builder().build();
        for(String invalid:List.of(OBSERVATION.replace("\"status\":\"passed\"","\"status\":\"failed\""),
                OBSERVATION.replace("\"mode\":\"live-current\"","\"mode\":\"guard-synthetic\""),
                OBSERVATION.replace("\"alreadyRunning\":true","\"alreadyRunning\":false"),OBSERVATION.replace("\"pid\":192","\"pid\":0"),
                OBSERVATION.replace("\"observedNamespace\":\"net:[4026532283]\"","\"observedNamespace\":\"net:[999]\""),
                OBSERVATION.replace("\"accepted\":true","\"accepted\":false"),OBSERVATION.replace("\"version\":\"7.6.9\"","\"version\":\"5.1\""))) {
            var value=mapper.readTree(invalid);assertThrows(IllegalArgumentException.class,()->ReleasePortPool.fromObservation(value,ID));
        }
        var good=mapper.readTree(OBSERVATION);assertThrows(IllegalArgumentException.class,()->ReleasePortPool.fromObservation(good,"22222222-2222-4222-8222-222222222222"));
    }
    @Test void emptyEligiblePoolAndInvalidBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->ReleasePortPool.subtract(20000,65535,List.of(new ReleasePortPool.Range(1,65535))));
        assertThrows(IllegalArgumentException.class,()->ReleasePortPool.subtract(0,20,List.of()));
        assertThrows(IllegalArgumentException.class,()->new ReleasePortPool.Range(30,20));
        assertThrows(IllegalArgumentException.class,()->new ReleasePortPool.Range(1,65536));
    }
    @Test void everyCandidateIsUniqueInsideTheDerivedComplementAndIndexBoundsAreStrict() throws Exception {
        var pool=ReleasePortPool.fromObservation(JsonMapper.builder().build().readTree(OBSERVATION),ID);var seen=new HashSet<Integer>();
        for(int index=0;index<pool.size();index++) {int port=pool.portAt(index);assertTrue(port>=20000&&port<=65535);assertFalse(port>=32768&&port<=60999);assertTrue(seen.add(port));}
        assertEquals(pool.size(),seen.size());assertThrows(IndexOutOfBoundsException.class,()->pool.portAt(-1));assertThrows(IndexOutOfBoundsException.class,()->pool.portAt(pool.size()));
    }
    @Test void allocatedAndOccupiedCandidatesAreSkippedWithoutClosingTheirListener() throws Exception {
        try(var occupied=listener();var available=listener()) {
            int held=occupied.getLocalPort(),free=available.getLocalPort(),reserved=held==65535?held-1:held+1;
            if(reserved==free) {reserved=held>2?held-2:held+2;}
            available.close();var pool=ReleasePortPool.subtract(1,65535,List.of());var allocated=new HashSet<Integer>();allocated.add(reserved);
            int[] indexes={held-1,reserved-1,free-1};var attempt=new AtomicInteger();
            int chosen=ReleaseRecoveryIT.isolatedPort(pool,allocated,()->indexes[Math.min(attempt.getAndIncrement(),2)]);
            assertEquals(free,chosen);assertEquals(3,attempt.get());assertEquals(2,allocated.size());assertTrue(allocated.contains(reserved));assertTrue(allocated.contains(free));assertListenerUsable(occupied);
        }
    }
    @Test void oneHundredUnavailableAttemptsFailWithoutExpandingThePoolOrTouchingTheListener() throws Exception {
        try(var occupied=listener()) {
            var pool=ReleasePortPool.subtract(occupied.getLocalPort(),occupied.getLocalPort(),List.of());var attempts=new AtomicInteger();var allocated=new HashSet<Integer>();
            assertThrows(IllegalStateException.class,()->ReleaseRecoveryIT.isolatedPort(pool,allocated,()->{attempts.incrementAndGet();return 0;}));
            assertEquals(100,attempts.get());assertTrue(allocated.isEmpty());assertListenerUsable(occupied);
        }
    }
    private static ServerSocket listener() throws Exception {var socket=new ServerSocket();socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),0));socket.setSoTimeout(1000);return socket;}
    private static void assertListenerUsable(ServerSocket listener) throws Exception {
        assertFalse(listener.isClosed());
        try(var client=new Socket()) {client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),listener.getLocalPort()),1000);try(var accepted=listener.accept()) {assertTrue(accepted.isConnected());}}
    }
}
