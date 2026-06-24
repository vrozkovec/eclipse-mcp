package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.FailureTrace;
import org.eclipse.jdt.junit.model.ITestElement.Result;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.ui.PlatformUI;

/**
 * Launches a single JUnit test class (optionally a single method) in the Eclipse JUnit runner
 * and blocks until the session finishes, returning per-test outcomes.
 *
 * <p>Always runs tests using Eclipse JDT's JUnit 6 loader
 * ({@code org.eclipse.jdt.junit.loader.junit6}). Since Eclipse JDT 3.14, JUnit 5 and JUnit 6
 * have distinct loaders, and each loader's pre-launch check requires that specific major
 * version on the project's build path — using the JUnit 5 loader against a JUnit 6 project
 * (or vice versa) fails with "Cannot find Testable on project build path." This tool is pinned
 * to JUnit 6 because that is the supported version in this workspace.</p>
 *
 * <p>Unlike {@link RunTestsTool}, this tool sets the class FQN via
 * {@link IJavaLaunchConfigurationConstants#ATTR_MAIN_TYPE_NAME} and uses
 * {@code org.eclipse.jdt.junit.TESTNAME} only for the optional method name — matching what
 * JDT's own JUnit launch shortcut produces.</p>
 *
 * <p>Test outcomes are captured via {@link JUnitCore#addTestRunListener(TestRunListener)}.
 * The listener filters events by {@link ITestRunSession#getTestRunName()} (which JDT sets to
 * the launch config name) so concurrent JUnit sessions don't bleed into the result. Designed
 * for short test runs (seconds, not minutes); a 5-minute safety timeout is applied.</p>
 */
public class RunTestClassTool implements Tool {

	private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

	private static final String JUNIT_LAUNCH_TYPE = "org.eclipse.jdt.junit.launchconfig";
	private static final String ATTR_CONTAINER = "org.eclipse.jdt.junit.CONTAINER";
	private static final String ATTR_TEST_KIND = "org.eclipse.jdt.junit.TEST_KIND";
	private static final String ATTR_TESTNAME = "org.eclipse.jdt.junit.TESTNAME";
	private static final String ATTR_KEEPRUNNING = "org.eclipse.jdt.junit.KEEPRUNNING_ATTR";

	/**
	 * Eclipse JDT's JUnit 6 loader id (added in JDT 3.14). Eclipse keeps separate loaders for
	 * JUnit 5 and JUnit 6 — each loader's pre-launch check rejects mismatched JUnit versions
	 * (e.g. the junit5 loader insists on JUnit major=5, the junit6 loader insists on major=6).
	 * This tool is pinned to JUnit 6.
	 */
	private static final String LOADER_JUNIT6 = "org.eclipse.jdt.junit.loader.junit6";

	/**
	 * Validates arguments, registers a {@link TestRunListener}, launches the JUnit configuration
	 * on the UI thread, then blocks the request thread on the listener's latch until the session
	 * completes (or the safety timeout fires).
	 *
	 * @param arguments map containing {@code projectName}, {@code className}, and optional
	 *                  {@code testMethod}
	 * @return result map describing the session outcome and per-test results
	 * @throws Exception if the launch cannot be created, saved, or launched
	 */
	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String projectName = (String) arguments.get("projectName");
		String className = (String) arguments.get("className");
		String testMethod = (String) arguments.get("testMethod");

		if (projectName == null || projectName.trim().isEmpty()) {
			throw new IllegalArgumentException("projectName is required");
		}
		if (className == null || className.trim().isEmpty()) {
			throw new IllegalArgumentException("className is required");
		}

		String configName = "MCP-RunTestClass-" + projectName + "-" + className
				+ (testMethod != null && !testMethod.trim().isEmpty() ? "-" + testMethod : "");

		ResultCollector collector = new ResultCollector(configName);
		JUnitCore.addTestRunListener(collector);
		try {
			PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
				try {
					setupAndLaunch(projectName, className, testMethod, configName);
					return null;
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});

			boolean finished = collector.latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return buildResult(projectName, className, testMethod, configName, collector, finished);
		} finally {
			JUnitCore.removeTestRunListener(collector);
		}
	}

	/**
	 * Resolves the project and target type, builds a JUnit launch configuration pinned to the
	 * JUnit 6 loader, saves it, and launches it in run mode. Must run on the UI thread.
	 */
	private void setupAndLaunch(String projectName, String className, String testMethod, String configName)
			throws CoreException {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.exists() || !project.isOpen()) {
			throw new IllegalArgumentException("Project not found or not open: " + projectName);
		}
		IJavaProject javaProject = JavaCore.create(project);
		if (!javaProject.exists()) {
			throw new IllegalArgumentException("Not a Java project: " + projectName);
		}

		IType type = javaProject.findType(className);
		if (type == null) {
			throw new IllegalArgumentException(
					"Type '" + className + "' not found in project " + projectName);
		}

		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationWorkingCopy config = launchManager
				.getLaunchConfigurationType(JUNIT_LAUNCH_TYPE)
				.newInstance(null, configName);

		config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
		config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, className);
		config.setAttribute(ATTR_CONTAINER, "");
		config.setAttribute(ATTR_TEST_KIND, LOADER_JUNIT6);
		config.setAttribute(ATTR_KEEPRUNNING, false);
		if (testMethod != null && !testMethod.trim().isEmpty()) {
			config.setAttribute(ATTR_TESTNAME, testMethod);
		}

		ILaunchConfiguration saved = config.doSave();
		saved.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());
	}

	/**
	 * Assembles the response map from the listener's collected state, including per-test entries
	 * and roll-up counts.
	 */
	private Map<String, Object> buildResult(String projectName, String className, String testMethod,
			String configName, ResultCollector collector, boolean finished) {
		Map<String, Object> result = new HashMap<>();
		result.put("configurationName", configName);
		result.put("projectName", projectName);
		result.put("className", className);
		result.put("testMethod", testMethod);
		result.put("testKind", LOADER_JUNIT6);
		result.put("launchMode", ILaunchManager.RUN_MODE);

		List<Map<String, Object>> tests = collector.snapshot();
		result.put("tests", tests);

		int passed = 0;
		int failed = 0;
		int errored = 0;
		int ignored = 0;
		for (Map<String, Object> t : tests) {
			switch ((String) t.get("result")) {
			case "OK" -> passed++;
			case "FAILURE" -> failed++;
			case "ERROR" -> errored++;
			case "IGNORED" -> ignored++;
			default -> {
			}
			}
		}
		result.put("passed", passed);
		result.put("failed", failed);
		result.put("errored", errored);
		result.put("ignored", ignored);
		result.put("total", tests.size());
		result.put("elapsedMillis", collector.elapsedMillis());

		if (!finished) {
			result.put("status", "timeout");
			result.put("outcome", "TIMEOUT");
			result.put("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
		} else {
			result.put("status", "completed");
			result.put("outcome", String.valueOf(collector.sessionResult));
		}
		return result;
	}

	/**
	 * Captures test outcomes for one specific JUnit launch configuration. Filters incoming
	 * session/test events by {@link ITestRunSession#getTestRunName()} — which JDT sets to the
	 * launch config name — so concurrent unrelated sessions are ignored.
	 */
	private static final class ResultCollector extends TestRunListener {

		private final String configName;
		final CountDownLatch latch = new CountDownLatch(1);
		final List<Map<String, Object>> tests = new CopyOnWriteArrayList<>();
		volatile Result sessionResult;
		volatile long startedAt;
		volatile long finishedAt;
		volatile ITestRunSession ownSession;

		ResultCollector(String configName) {
			this.configName = configName;
		}

		private boolean isOurs(ITestRunSession session) {
			if (session == null) {
				return false;
			}
			if (ownSession != null) {
				return session == ownSession;
			}
			return configName.equals(session.getTestRunName());
		}

		@Override
		public void sessionStarted(ITestRunSession session) {
			if (isOurs(session)) {
				ownSession = session;
				startedAt = System.currentTimeMillis();
			}
		}

		@Override
		public void testCaseFinished(ITestCaseElement test) {
			if (test == null || ownSession == null) {
				return;
			}
			if (rootSession(test) != ownSession) {
				return;
			}

			Map<String, Object> entry = new HashMap<>();
			entry.put("className", test.getTestClassName());
			entry.put("methodName", test.getTestMethodName());
			entry.put("result", String.valueOf(test.getTestResult(false)));
			FailureTrace trace = test.getFailureTrace();
			if (trace != null) {
				if (trace.getTrace() != null) {
					entry.put("trace", trace.getTrace());
				}
				if (trace.getExpected() != null) {
					entry.put("expected", trace.getExpected());
				}
				if (trace.getActual() != null) {
					entry.put("actual", trace.getActual());
				}
			}
			tests.add(entry);
		}

		@Override
		public void sessionFinished(ITestRunSession session) {
			if (session == ownSession) {
				finishedAt = System.currentTimeMillis();
				sessionResult = session.getTestResult(true);
				latch.countDown();
			}
		}

		private static ITestRunSession rootSession(ITestElement element) {
			ITestElement current = element;
			while (current != null && !(current instanceof ITestRunSession)) {
				current = current.getParentContainer();
			}
			return (ITestRunSession) current;
		}

		long elapsedMillis() {
			if (startedAt == 0) {
				return 0;
			}
			long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
			return end - startedAt;
		}

		List<Map<String, Object>> snapshot() {
			return new ArrayList<>(tests);
		}
	}
}
