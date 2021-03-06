package net.unit8.waitt;

import static java.util.logging.Level.*;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.AprLifecycleListener;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.profiles.DefaultProfileManager;
import org.apache.maven.profiles.ProfileManager;
import org.apache.maven.project.DefaultProjectBuilderConfiguration;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuilderConfiguration;
import org.codehaus.plexus.PlexusContainer;

/**
 * Web Application Integration Test Tool maven plugin.
 *
 * @author kawasima
 */
@SuppressWarnings("unchecked")
@Mojo(name = "run")
public class RunMojo extends AbstractMojo {
    @Parameter
    private int port;

    @Parameter(defaultValue = "8080")
    private int startPort;

    @Parameter(defaultValue = "9000")
    private int endPort;

    @Parameter(defaultValue = "")
    private String contextPath;

    @Parameter(defaultValue = "")
    private String path;

    @Component
    protected MavenProject project;

    @Component
    protected MavenProjectBuilder projectBuilder;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    protected MavenSession session;

    @Parameter(defaultValue = "target/coverage")
    protected File coverageReportDirectory;

    @Component
    private ArtifactResolver artifactResolver;

    @Component
    private ArtifactFactory artifactFactory;

    @Component
    private ArtifactMetadataSource metadataSource;

    @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
    private ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
    private List<ArtifactRepository> remoteRepositories;

    @Parameter(defaultValue = "${descriptor}")
    private PluginDescriptor descriptor;

    protected final ProjectBuilderConfiguration projectBuilderConfiguration = new DefaultProjectBuilderConfiguration();

    protected String appBase;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private void readArtifacts(String subDirectory, List<Artifact> artifacts, List<File> classpathFiles)
            throws MojoExecutionException {
        PlexusContainer container = session.getContainer();
        Properties execution = session.getExecutionProperties();
        ProfileManager profileManager = new DefaultProfileManager(container, execution);

        File modulePom = (subDirectory == null || subDirectory.isEmpty()) ? new File("pom.xml") : new File(subDirectory, "pom.xml");
        try {
            MavenProject subProject = projectBuilder.buildWithDependencies(modulePom, localRepository, profileManager);
            subProject.setRemoteArtifactRepositories(remoteRepositories);
            if ("war".equals(subProject.getPackaging())) {
                appBase = (subDirectory == null || subDirectory.isEmpty()) ?
                        new File("src/main/webapp").getAbsolutePath() :
                        new File(subDirectory, "src/main/webapp").getAbsolutePath();
            }
            artifacts.addAll(subProject.getCompileArtifacts());
            artifacts.addAll(subProject.getRuntimeArtifacts());
            classpathFiles.add(new File(subProject.getBuild().getOutputDirectory()));
        } catch (Exception e) {
            throw new MojoExecutionException("module(" + subDirectory + ") build failure", e);
        }
    }

    /**
     * Start tomcat.
     *
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        initLogger();
        List<URL> classpathUrls = resolveClasspaths();
        ClassLoader parentClassLoader = new ParentLastClassLoader(
                classpathUrls.toArray(new URL[classpathUrls.size()]),
                Thread.currentThread().getContextClassLoader());
        if (appBase == null)
            appBase = new File("src/main/webapp").getAbsolutePath();
        getLog().info("App base: " + appBase);
        Tomcat tomcat = new Tomcat();
        if (port == 0)
            scanPort();
        tomcat.setPort(port);

        if (contextPath == null || contextPath.equals("/"))
            contextPath = "";
        System.setProperty("catalina.home", ".");
        tomcat.setBaseDir(".");
        tomcat.getHost().setAppBase(appBase);

        StandardServer server = (StandardServer) tomcat.getServer();
        AprLifecycleListener listener = new AprLifecycleListener();
        server.addLifecycleListener(listener);
        tomcat.getConnector().setURIEncoding("UTF-8");
        tomcat.getConnector().setUseBodyEncodingForURI(true);

        try {
            Context context = tomcat.addWebapp(contextPath, appBase);
            final WebappLoader webappLoader = new WebappLoader(parentClassLoader);
            webappLoader.setLoaderClass("net.unit8.waitt.CoberturaClassLoader");
            webappLoader.setDelegate(((StandardContext) context).getDelegate());
            context.setLoader(webappLoader);
            context.setSessionCookieDomain(null);

            initCoverageContext(tomcat);

            WaittServlet waittServlet = new WaittServlet(server, executorService);
            Context adminContext = tomcat.addContext("/waitt", "");
            adminContext.setParentClassLoader(Thread.currentThread().getContextClassLoader());
            Tomcat.addServlet(adminContext, "waittServlet", waittServlet);
            adminContext.addServletMapping("/*", "waittServlet");

            initCoverageMonitor(webappLoader);
            tomcat.start();
            Desktop.getDesktop().browse(URI.create("http://localhost:" + port + contextPath + path));
            server.addLifecycleListener(new LifecycleListener() {
                @Override
                public void lifecycleEvent(LifecycleEvent event) {
                    if (event.getType().equals(Lifecycle.BEFORE_STOP_EVENT)) {
                        executorService.shutdownNow();
                        getLog().info("Stop monitoring threads.");
                    }
                }
            });
            server.await();
        } catch (Exception e) {
            throw new MojoExecutionException("Tomcat start failure", e);
        }
    }

    private void initLogger() {
        Logger logger = Logger.getLogger("");
        logger.setLevel(ALL);
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
        }
        final Log mavenLogger = getLog();

        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLoggerName().startsWith("sun.awt."))
                    return;
                Level lv = logRecord.getLevel();
                if (Arrays.asList(ALL, CONFIG, FINE, FINER, FINEST).contains(lv)) {
                    mavenLogger.debug(logRecord.getMessage());
                } else if (lv.equals(INFO)) {
                    mavenLogger.info(logRecord.getMessage());
                } else if (lv.equals(WARNING)) {
                    Throwable t = logRecord.getThrown();
                    if (t == null)
                        mavenLogger.warn(logRecord.getMessage());
                    else
                        mavenLogger.warn(logRecord.getMessage(), t);
                } else if (lv.equals(SEVERE)) {
                    Throwable t = logRecord.getThrown();
                    if (t == null)
                        mavenLogger.error(logRecord.getMessage());
                    else
                        mavenLogger.error(logRecord.getMessage(), t);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws SecurityException {
            }
        });
    }

    private List<URL> resolveClasspaths() throws MojoExecutionException {
        TargetPackages.getInstance().set(
                PackageScanner.scan(new File(project.getBuild().getSourceDirectory())));
        List<Artifact> artifacts = new ArrayList<Artifact>();
        List<File> classpathFiles = new ArrayList<File>();
        projectBuilderConfiguration.setLocalRepository(localRepository);

        if (project.getModel().getModules().isEmpty()) {
            readArtifacts("", artifacts, classpathFiles);
        } else {
            for (String module : project.getModel().getModules()) {
                readArtifacts(module, artifacts, classpathFiles);
            }
        }
        List<URL> classpathUrls = new ArrayList<URL>();
        Set<String> uniqueArtifacts = new HashSet<String>();

        try {
            for (File classpathFile : classpathFiles) {
                URL url = classpathFile.toURI().toURL();
                classpathUrls.add(url);
            }
            for (URL url : ((URLClassLoader) Thread.currentThread().getContextClassLoader()).getURLs()) {
                if (url.toString().contains("/org/ow2/asm/")
                        || url.toString().contains("/waitt-maven-plugin/")
                        || url.toString().contains("/net/sourceforge/cobertura/")) {
                    classpathUrls.add(url);
                }
            }
            for (Artifact artifact : artifacts) {
                if ("provided".equals(artifact.getScope()))
                    continue;

                String versionlessKey = ArtifactUtils.versionlessKey(artifact);
                if (!uniqueArtifacts.contains(versionlessKey)) {
                    classpathUrls.add(artifact.getFile().toURI().toURL());
                    uniqueArtifacts.add(versionlessKey);
                }
            }
            return classpathUrls;
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Error during setting up classpath", e);
        }
    }

    private void initCoverageContext(Tomcat tomcat) {
        /* Cobertura coverage report */
        if (!coverageReportDirectory.exists()) {
            // ignore error when create coverage directory.
            assert (coverageReportDirectory.mkdirs());
        }

        Context coverageContext = tomcat.addContext("/coverage", coverageReportDirectory.getAbsolutePath());
        coverageContext.setParentClassLoader(Thread.currentThread().getContextClassLoader());
        Wrapper defaultServlet = coverageContext.createWrapper();
        defaultServlet.setName("default");
        defaultServlet.setServletClass("org.apache.catalina.servlets.DefaultServlet");
        defaultServlet.addInitParameter("debug", "0");
        defaultServlet.addInitParameter("listings", "false");
        defaultServlet.setLoadOnStartup(1);
        coverageContext.addChild(defaultServlet);
        coverageContext.addServletMapping("/", "default");
        coverageContext.addWelcomeFile("index.html");
    }

    private void initCoverageMonitor(final WebappLoader webappLoader) {
        final CoverageMonitorConfiguration config = new CoverageMonitorConfiguration();
        config.setCoverageReportDirectory(coverageReportDirectory);
        config.setSourceDirectory(new File(project.getBuild().getSourceDirectory()));
        executorService.execute(
            new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        ClassLoader cl = webappLoader.getClassLoader();
                        if (cl != null) {
                            try {
                                Class<?> monitorClass = cl.loadClass("net.unit8.waitt.CoverageMonitor");
                                Constructor<?> constructor = monitorClass.getConstructor(
                                        WebappLoader.class,
                                        CoverageMonitorConfiguration.class);
                                executorService.execute((Runnable) constructor.newInstance(webappLoader, config));
                            } catch (Exception e) {
                                getLog().warn(e);
                            }
                            break;
                        }
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            assert false;
                        }
                    }
                }
            }
        );
    }

    protected void scanPort() {
        for (int p = startPort; p <= endPort; p++) {
            try {
                Socket sock = new Socket("localhost", p);
                sock.close();
            } catch (IOException e) {
                port = p;
                return;
            }
        }
        throw new RuntimeException("Can't find available port from " + startPort + " to " + endPort);
    }
}