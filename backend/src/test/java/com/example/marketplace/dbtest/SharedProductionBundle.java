package com.example.marketplace.dbtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Consumes checked same-run production bytes; standalone fixtures retain their build path. */
final class SharedProductionBundle {
    private SharedProductionBundle() { }

    static boolean copyIfDeclared(Path root, Path folder, Path target) throws Exception {
        if (System.getenv("D2_PRODUCTION_BUNDLE") == null) { return false; }
        command(root, folder, target, "copy");
        return true;
    }

    static void verifyIfDeclared(Path root, Path folder, Path target) throws Exception {
        if (System.getenv("D2_PRODUCTION_BUNDLE") != null) { command(root, folder, target, "verify-copy"); }
    }

    private static void command(Path root, Path folder, Path target, String action) throws Exception {
        Path receipt = folder.resolve(target.getFileName() + "-production-copy.json");
        var args = List.of(root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe").toString(),
                root.resolve("verification/ProductionBundle.cjs").toString(), action, root.toString(),
                System.getenv("D2_PRODUCTION_BUNDLE"), target.toString(), receipt.toString());
        var child = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(folder.resolve(target.getFileName() + "-production-" + action + ".log").toFile()).start();
        try {
            if (!child.waitFor(60, TimeUnit.SECONDS)) {
                throw new AssertionError("Finite shared production artifact phase exceeded");
            }
            assertEquals(0, child.exitValue(), "Inspect preserved shared production " + action + " log");
        } finally {
            if (child.isAlive()) { com.example.marketplace.jdbcfixture.BrowserChildWatchdog.terminate(child); }
        }
    }
}
