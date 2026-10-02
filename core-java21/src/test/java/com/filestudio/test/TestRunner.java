package com.filestudio.test;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/** 直接调用 JUnit Platform Launcher 运行 com.filestudio 下所有测试，绕过 Gradle 测试执行器。 */
public final class TestRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequest req = request()
                .selectors(selectPackage("com.filestudio"))
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(req);
        TestExecutionSummary s = listener.getSummary();
        PrintWriter w = new PrintWriter(System.out);
        s.printTo(w);
        s.printFailuresTo(w);
        w.flush();
        int rc = s.getFailures().isEmpty() ? 0 : 1;
        System.out.println("TOTAL=" + s.getTestsFoundCount()
                + " PASSED=" + s.getTestsSucceededCount()
                + " FAILED=" + s.getTestsFailedCount()
                + " EXIT=" + rc);
        System.exit(rc);
    }
}
