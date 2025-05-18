package test.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Create the client ear, unpack it and run the client
 */
@ExtendWith(ArquillianExtension.class)
public class ClientTest {

    @TargetsContainer("glassfish")
    @Deployment(name = "app-client", order = 2)
    public static EnterpriseArchive createDeploymentVehicle() throws IOException {
        // Client jar
        JavaArchive clientJar = ShrinkWrap.create(JavaArchive.class, "client-main.jar").addClasses(ClientMain.class)
                .addAsManifestResource(new StringAsset("Main-Class: " + ClientMain.class.getName() + "\n"), "MANIFEST.MF");

        EnterpriseArchive ear = ShrinkWrap.create(EnterpriseArchive.class, "app-client.ear").addAsModule(clientJar)
                .addAsModule(ShrinkWrap.create(WebArchive.class, "client-web.war")
                        .addAsResource(ClientTest.class.getResource("/index.html"), "index.html"))
                .addAsManifestResource(ClientTest.class.getResource("/application.xml"), "application.xml");

        // Unpack the ear for the appclient runner
        Path earPath = Paths.get("target", "app-client.ear");
        Files.createDirectories(earPath);

        clientJar.as(ZipExporter.class).exportTo(earPath.resolve("client-main.jar").toFile(), true);

        return ear;
    }

    @Test
    @RunAsClient
    public void testAppClient() throws Exception {
        System.out.println("\n\n\n ****************  Running testAppClient");
        runClient();
        System.out.println("Finished testAppClient");
    }

    private void runClient() throws Exception {
        String glassfishHome = System.getProperty("glassfish.home") + "/glassfish";

        Files.list(Paths.get("target/app-client.ear")).forEach(path -> {
            System.out.println("Unpacked file: " + path);
        });


        String[] clientCmdLine = { glassfishHome + "/glassfish/bin/appclient", "-jar", "target/app-client.ear/client-main.jar" };
        clientCmdLine = getClientCmdLine(
                            glassfishHome,
                            "target/app-client.ear/client-main.jar",
                            System.getProperty("appclient.suspend") != null? "y" : "n");

        String[] clientEnvp = null;
        File clientDir = null;

        Process appClientProcess = Runtime.getRuntime().exec(clientCmdLine, clientEnvp, clientDir);

        System.out.println("\nCreated process" + appClientProcess.info());
        System.out.println("\nprocess(%d).envp: %s".formatted(appClientProcess.pid(), Arrays.toString(clientEnvp)));
        BufferedReader outputReader = new BufferedReader(new InputStreamReader(appClientProcess.getInputStream(), UTF_8));
        BufferedReader errorReader = new BufferedReader(new InputStreamReader(appClientProcess.getErrorStream(), UTF_8));

        final Thread readOutputThread = new Thread(() -> readClientProcess(outputReader, false), "stdout reader");
        readOutputThread.start();

        final Thread readErrorThread = new Thread(() -> readClientProcess(errorReader, true), "stderr reader");
        readErrorThread.start();

        System.out.println("\n\n *********** Started process reader threads \n\n");

        boolean timeout = appClientProcess.waitFor(1000, SECONDS);
        if (timeout) {
            System.out.println("AppClient process finished");
        } else {
            System.out.println("AppClient process timed out");
            appClientProcess.destroy();
            throw new RuntimeException("AppClient process timed out");
        }

        assertEquals(0, appClientProcess.exitValue(), "AppClient process exited with non-zero code");
    }

    public String[] getClientCmdLine(String glassfishHome, String clientJar, String suspend) {

        String cmdLine = """
          java
              -agentlib:jdwp=transport=dt_socket,server=y,suspend=${suspend},address=9008
              -Dcom.sun.aas.installRoot=${glassfishHome}
              -Djava.security.policy=${glassfishHome}/bin/lib/appclient/client.policy
              -Djava.security.auth.login.config=${glassfishHome}/lib/appclient/appclientlogin.conf
              -Djava.system.class.loader=org.glassfish.appclient.client.acc.agent.ACCAgentClassLoader

              -javaagent:${glassfishHome}/lib/gf-client.jar=arg=-configxml,arg=${glassfishHome}/domains/domain1/config/glassfish-acc.xml,client=jar=${clientJar},arg=-name,arg=${clientJarName},arg=-textauth,arg=-user,arg=jee,arg=-password,arg=j2ee,arg=-xml,arg=${glassfishHome}/domains/domain1/config/glassfish-acc.xml

              -classpath
              ${glassfishHome}/lib/gf-client.jar:${clientJar}

              org.glassfish.appclient.client.AppClientGroupFacade

                """;

        String a[] = Arrays.stream(cmdLine.split("\n"))
              .map(e -> e.replace("${glassfishHome}", glassfishHome))
              .map(e -> e.replace("${clientJar}", clientJar))
              .map(e -> e.replace("${clientJarName}", "client-main"))
              .map(e -> e.replace("${suspend}", suspend))
              .map(e -> e.trim())
              .filter(e -> !e.isEmpty())
              .toArray(String[]::new);


        System.out.println("*****************" + a);
        Arrays.stream(a).forEach(e -> System.out.println(e));

        return a;
    }

    private void readClientProcess(BufferedReader reader, boolean errReader) {
        System.out.println("Begin readClientProcess");
        int count = 0;
        try {
            String line = reader.readLine();
            // System.out.println("RCP: " + line);
            while (line != null) {
                count++;
                if (errReader) {
                    System.out.println("[stderr] " + line);
                } else {
                    System.out.println("[stdout] " + line);
                }
                line = reader.readLine();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }

        System.out.println(String.format("Exiting(isStderr=%s), read %d lines", errReader, count));
    }

}
