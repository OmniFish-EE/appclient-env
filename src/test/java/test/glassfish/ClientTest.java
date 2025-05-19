package test.glassfish;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.stream.Stream;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Create the client ear, download it and run the client
 */
@ExtendWith(ArquillianExtension.class)
public class ClientTest {

    static final String APP_NAME = "app-client";
    static final String APP_CLIENT_MODULE_NAME = "client-main";

    @Deployment
    public static EnterpriseArchive createDeploymentVehicle() throws IOException {
        System.out.println("\n\n\n\u001B[1m****************  Deploying EAR with appclient module \n\u001B[0m");

        return ShrinkWrap.create(EnterpriseArchive.class, APP_NAME + ".ear")
                // AppClient module
                .addAsModule(ShrinkWrap.create(JavaArchive.class, APP_CLIENT_MODULE_NAME + ".jar")
                        .addClasses(ClientMain.class)
                        .addAsManifestResource(new StringAsset("Main-Class: " + ClientMain.class.getName() + "\n"), "MANIFEST.MF"))

                // Dummy web archive module
                .addAsModule(ShrinkWrap.create(WebArchive.class, "client-web.war")
                        .addAsResource(ClientTest.class.getResource("/index.html"), "index.html"))

                // Application XML to describe the EAR layout
                .addAsManifestResource(ClientTest.class.getResource("/application.xml"), "application.xml");
    }

    @Test
    @RunAsClient
    public void testAppClient() throws Exception {
        System.out.println("Start testAppClient");
        runClient();
        System.out.println("Finished testAppClient");
    }

    void runClient() throws Exception {
        String glassfishHome = System.getProperty("glassfish.home") + "/glassfish";

        System.out.println("Glassfish home: " + glassfishHome);

        String appClientJarPath = downloadClientStubJar(glassfishHome, APP_NAME);

        System.out.println("\n\n\n\u001B[1m****************  Running stubbed appclient jar \n\u001B[0m");

        String[] startAppClientCmdLine =
             getStartAppClientCmdLine(
                glassfishHome,
                appClientJarPath,
                APP_CLIENT_MODULE_NAME,
                System.getProperty("appclient.suspend") != null? "y" : "n");

        Process appClientProcess = startReadingClientProcess(Runtime.getRuntime().exec(startAppClientCmdLine, null, null));

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

    String[] getStartAppClientCmdLine(String glassfishHome, String clientJar, String appClientModuleName, String suspend) {
        String cmdLine = """
          java
              -agentlib:jdwp=transport=dt_socket,server=y,suspend=${suspend},address=9008

              -Dcom.sun.aas.installRoot=${glassfishHome}
              -Djava.security.policy=${glassfishHome}/bin/lib/appclient/client.policy
              -Djava.security.auth.login.config=${glassfishHome}/lib/appclient/appclientlogin.conf
              -Djava.system.class.loader=org.glassfish.appclient.client.acc.agent.ACCAgentClassLoader

              -javaagent:${glassfishHome}/lib/gf-client.jar=arg=-configxml,arg=${glassfishHome}/domains/domain1/config/glassfish-acc.xml,client=jar=${clientJar},arg=-name,arg=${appClientModuleName},arg=-textauth,arg=-user,arg=jee,arg=-password,arg=j2ee,arg=-xml,arg=${glassfishHome}/domains/domain1/config/glassfish-acc.xml

              -classpath ${glassfishHome}/lib/gf-client.jar:${clientJar}

              org.glassfish.appclient.client.AppClientGroupFacade

                """;

        String command[] = Arrays.stream(cmdLine.split("\n"))
              .map(e -> e.replace("${glassfishHome}", glassfishHome))
              .map(e -> e.replace("${clientJar}", clientJar))
              .map(e -> e.replace("${appClientModuleName}", appClientModuleName))
              .map(e -> e.replace("${suspend}", suspend))
              .map(e -> e.trim())
              .filter(e -> !e.isEmpty())
              .flatMap(e -> Stream.of(e.split(" +")))
              .toArray(String[]::new);

        System.out.println("Starting appclient using command:");
        stream(command).forEach(e -> System.out.println(e));

        return command;
    }

    String downloadClientStubJar(String glassfishHome, String appName) throws IOException, InterruptedException {
        System.out.println("\n\n\n\u001B[1m**************** Downloading stubbed appclient jar from server\u001B[0m");

        String[] clientCmdLine = {
            glassfishHome + "/bin/asadmin",
            "get-client-stubs", "--appName",
            appName,
            "target" };

        int exit = startReadingClientProcess(Runtime.getRuntime().exec(clientCmdLine, null, null))
                    .waitFor();

        System.out.println("getClientStubJar(), exit=" + exit);

        if (exit != 0) {
            throw new IllegalStateException("Failed to get client stub jar");
        }

        File clientStubJar = new File("target/" + appName + "Client.jar");
        if (!clientStubJar.exists()) {
            throw new IllegalStateException("Client stub jar not found: " + clientStubJar.getAbsolutePath());
        }

        return clientStubJar.getAbsolutePath();
    }

    Process startReadingClientProcess(Process process) {
        System.out.println("\nCreated process" + process.info());
        System.out.println("\nprocess(%d)".formatted(process.pid()));

        new Thread(() -> readClientProcess(process.getInputStream(), false), "stdout reader").start();
        new Thread(() -> readClientProcess(process.getErrorStream(), true), "stderr reader").start();

        return process;
    }

    void readClientProcess(InputStream inputStream, boolean errReader) {

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, UTF_8));

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
